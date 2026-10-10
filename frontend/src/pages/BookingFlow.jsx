import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { BookingApi, QuotaApi, StationApi, TrainApi, newIdempotencyKey } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { useToast } from '../context/ToastContext';
import useCountdown from '../hooks/useCountdown';
import { availabilityOf, formatRemaining, money } from '../utils/format';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import FareBreakdown from '../components/FareBreakdown';
import Field, { CheckRow } from '../components/Field';

const STEPS = ['Class', 'Passengers', 'Review', 'Payment', 'Confirmation'];

/**
 * Every value PassengerDto accepts, not a convenient subset. Window and Aisle were
 * missing, so a passenger in a coach that actually has window seats could not ask
 * for one and was silently given NO_PREFERENCE instead.
 */
const BERTH_OPTIONS = [
  'NO_PREFERENCE',
  'LOWER',
  'MIDDLE',
  'UPPER',
  'SIDE_LOWER',
  'SIDE_UPPER',
  'WINDOW',
  'AISLE',
];

/**
 * The quotas a passenger can pick, in the order they are presented. The backend enforces who may
 * use each one, so this list is a convenience, never the authority: a passenger who ticks "Senior
 * Citizen" at 34 is refused by the server with an explanation rather than being quietly given a
 * full-fare general ticket.
 */
const QUOTA_OPTIONS = [
  { value: 'GENERAL', label: 'General', hint: 'Open to everyone' },
  { value: 'SENIOR', label: 'Senior Citizen', hint: 'Age 60 and above' },
  { value: 'LADIES', label: 'Ladies', hint: 'Female passengers' },
  { value: 'DIVYANG', label: 'Divyang', hint: 'Disability certificate required' },
  { value: 'TATKAL', label: 'Tatkal (simulated)', hint: 'Opens shortly before departure' },
  { value: 'STAFF', label: 'Staff Quota', hint: 'YatraRail staff only' },
];

function emptyPassenger() {
  return { fullName: '', age: '', gender: '', berthPreference: 'NO_PREFERENCE', quota: 'GENERAL', disabilityCertified: false };
}

export default function BookingFlow() {
  const { trainId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const journeyDate = location.state?.journeyDate || new Date().toISOString().slice(0, 10);
  const [step, setStep] = useState(0);
  const [details, setDetails] = useState(null);
  const [stations, setStations] = useState([]);
  const [error, setError] = useState(null);

  const [selectedCoachId, setSelectedCoachId] = useState(null);
  const [passengers, setPassengers] = useState([emptyPassenger()]);
  const [passengerErrors, setPassengerErrors] = useState([]);
  const [allowRac, setAllowRac] = useState(true);
  const [creating, setCreating] = useState(false);
  const [booking, setBooking] = useState(null);

  // One key per booking intent. A transport-level retry of the same submit reuses it, but any
  // edit to the request makes it a new intent, so it gets a new key.
  const [createKey, setCreateKey] = useState(() => newIdempotencyKey('booking'));
  const [payKey, setPayKey] = useState(() => newIdempotencyKey('payment'));

  const [paymentMethod, setPaymentMethod] = useState('UPI');
  const [simulateSuccess, setSimulateSuccess] = useState(true);
  const [paying, setPaying] = useState(false);

  // Server-authoritative quote for the review step; the client never computes fares.
  const [quote, setQuote] = useState(null);
  const [quoting, setQuoting] = useState(false);
  const [quoteError, setQuoteError] = useState(null);

  // Live per-quota seat counts for the selected class, so the passenger sees how much of each
  // quota is left before reaching the review step. Advisory only: the booking path re-checks.
  const [quotaAvailability, setQuotaAvailability] = useState([]);

  // The segment the passenger searched for. When ids are supplied the details call is scoped to
  // that leg, so the class fares shown here match what the quote and the booking will charge.
  const segmentIds = location.state?.sourceStationId && location.state?.destinationStationId
    ? { sourceStationId: location.state.sourceStationId, destinationStationId: location.state.destinationStationId }
    : {};

  useEffect(() => {
    Promise.all([TrainApi.details(trainId, { journeyDate, ...segmentIds }), StationApi.list()])
      .then(([d, s]) => { setDetails(d.data.data); setStations(s.data.data); })
      .catch((err) => setError(extractErrorMessage(err)));
  }, [trainId, journeyDate]); // eslint-disable-line react-hooks/exhaustive-deps

  const sourceStation = useMemo(() => {
    const code = location.state?.sourceStationCode || details?.route?.[0]?.stationCode;
    return stations.find((s) => s.code === code);
  }, [stations, location.state, details]);

  const destinationStation = useMemo(() => {
    const code = location.state?.destinationStationCode || details?.route?.[details.route.length - 1]?.stationCode;
    return stations.find((s) => s.code === code);
  }, [stations, location.state, details]);

  const selectedCoach = details?.classes.find((c) => c.coachId === selectedCoachId);

  const quoteRequest = useMemo(() => ({
    trainId: Number(trainId),
    coachId: selectedCoachId,
    sourceStationId: sourceStation?.id,
    destinationStationId: destinationStation?.id,
    journeyDate,
    allowRac,
    passengers: passengers.length,
    // Quotas and concessions are priced per passenger, so the quote needs who is actually travelling.
    passengerDetails: passengers.map((p) => ({
      fullName: p.fullName,
      age: p.age === '' ? null : Number(p.age),
      gender: p.gender,
      berthPreference: p.berthPreference,
      quota: p.quota,
      disabilityCertified: Boolean(p.disabilityCertified),
    })),
  }), [trainId, selectedCoachId, sourceStation, destinationStation, journeyDate, allowRac, passengers]);

  // Availability for the class picker, refetched whenever the class or the date changes.
  useEffect(() => {
    if (!selectedCoachId) {
      setQuotaAvailability([]);
      return;
    }
    QuotaApi.availability(selectedCoachId, journeyDate)
      .then((res) => setQuotaAvailability(res.data.data || []))
      .catch(() => setQuotaAvailability([]));
  }, [selectedCoachId, journeyDate]);

  const availabilityFor = (quota) => quotaAvailability.find((q) => q.quota === quota);

  const fetchQuote = useCallback(() => {
    if (!quoteRequest.coachId || !quoteRequest.sourceStationId || !quoteRequest.destinationStationId) return;
    setQuoting(true);
    setQuoteError(null);
    BookingApi.quote(quoteRequest)
      .then((res) => setQuote(res.data.data))
      .catch((err) => setQuoteError(extractErrorMessage(err)))
      .finally(() => setQuoting(false));
  }, [quoteRequest]);

  useEffect(() => {
    if (step === 2) fetchQuote();
  }, [step, fetchQuote]);

  // Countdown only matters once a hold actually exists.
  const { remainingMs, expired } = useCountdown(
    booking?.paymentDeadline && ['PAYMENT_PENDING', 'PAYMENT_FAILED'].includes(booking.status)
      ? booking.paymentDeadline
      : null,
  );

  if (error) {
    // Retrying re-runs the same details fetch the failed one did, rather than
    // just clearing the message and leaving an empty page behind.
    return (
      <div className="container page">
        <ErrorState
          message={error}
          onRetry={() => {
            setError(null);
            setDetails(null);
            Promise.all([TrainApi.details(trainId, { journeyDate, ...segmentIds }), StationApi.list()])
              .then(([d, s]) => { setDetails(d.data.data); setStations(s.data.data); })
              .catch((err) => setError(extractErrorMessage(err)));
          }}
        />
      </div>
    );
  }
  if (!details || stations.length === 0) return <Loader label="Preparing your booking..." />;

  const editIntent = () => setCreateKey(newIdempotencyKey('booking'));

  const updatePassenger = (idx, field, value) => {
    editIntent();
    setPassengers((prev) => prev.map((p, i) => (i === idx ? { ...p, [field]: value } : p)));
  };

  const selectCoach = (coachId) => { editIntent(); setSelectedCoachId(coachId); };
  const toggleRac = (value) => { editIntent(); setAllowRac(value); };

  const addPassenger = () => { if (passengers.length < 6) { editIntent(); setPassengers((p) => [...p, emptyPassenger()]); } };
  const removePassenger = (idx) => { editIntent(); setPassengers((prev) => prev.filter((_, i) => i !== idx)); };

  const validatePassengers = () => {
    const errs = passengers.map((p) => {
      const e = {};
      if (!p.fullName.trim()) e.fullName = 'Name is required';
      if (!p.age || p.age < 1 || p.age > 120) e.age = 'Enter a valid age';
      if (!p.gender) e.gender = 'Select gender';
      return e;
    });
    setPassengerErrors(errs);
    return errs.every((e) => Object.keys(e).length === 0);
  };

  const goToReview = () => {
    if (!selectedCoachId) { showToast('Please select a travel class', 'error'); return; }
    if (!validatePassengers()) return;
    setStep(2);
  };

  const submitBooking = async () => {
    setCreating(true);
    try {
      const payload = {
        trainId: Number(trainId),
        coachId: selectedCoachId,
        sourceStationId: sourceStation.id,
        destinationStationId: destinationStation.id,
        journeyDate,
        allowRac,
        passengers: passengers.map((p) => ({
          ...p,
          age: Number(p.age),
          disabilityCertified: Boolean(p.disabilityCertified),
        })),
      };
      const res = await BookingApi.create(payload, createKey);
      setBooking(res.data.data);
      setStep(3);
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setCreating(false);
    }
  };

  const submitPayment = async () => {
    setPaying(true);
    // Reusing the key replays the stored result, so a *settled* attempt (the server answered,
    // success or failure) must get a fresh key or a failed payment could never be retried.
    // A transport failure gets no response, so the key is kept and the retry stays idempotent.
    let serverAnswered = false;
    try {
      const res = await BookingApi.pay(booking.id, { method: paymentMethod, simulateSuccess }, payKey);
      serverAnswered = true;
      setBooking(res.data.data);
      setStep(4);
      showToast(
        res.data.data.status === 'PAYMENT_FAILED'
          ? 'Payment failed — your seats are still held, you can retry.'
          : 'Payment successful!',
        res.data.data.status === 'PAYMENT_FAILED' ? 'error' : 'success',
      );
    } catch (err) {
      serverAnswered = Boolean(err?.response);
      showToast(extractErrorMessage(err), 'error');
    } finally {
      if (serverAnswered) setPayKey(newIdempotencyKey('payment'));
      setPaying(false);
    }
  };

  const holdTone = !booking?.paymentDeadline ? 'hold-timer-muted' : expired ? 'hold-timer-urgent' : remainingMs < 120000 ? 'hold-timer-urgent' : '';

  return (
    <div className="container page" style={{ maxWidth: 820 }}>
      <PageHeader
        title="Book your journey"
        subtitle={`${details.trainName} · ${sourceStation?.name} → ${destinationStation?.name} · ${journeyDate}`}
        icon="ticket"
      />

      {/* aria-current marks the step in progress for assistive technology;
          the tint and the tick only mark it visually. */}
      <ol className="steps" aria-label="Booking progress">
        {STEPS.map((s, i) => (
          <li key={s} className="step-combo">
            <span
              className={`step ${i === step ? 'is-current' : ''} ${i < step ? 'is-done' : ''}`}
              aria-current={i === step ? 'step' : undefined}
            >
              <span className="step-num" aria-hidden="true">{i < step ? '\u2713' : i + 1}</span>
              {s}
              <span className="sr-only">
                {i === step ? ' (current step)' : i < step ? ' (completed)' : ' (not started)'}
              </span>
            </span>
            {i < STEPS.length - 1 && <span className={`step-sep ${i < step ? 'is-done' : ''}`} aria-hidden="true" />}
          </li>
        ))}
      </ol>

      {step === 0 && (
        <div className="card">
          <h2>{details.trainName} <span className="text-muted" style={{ fontWeight: 400, fontSize: '0.9rem' }}>#{details.trainNumber}</span></h2>
          <p>{sourceStation?.name} → {destinationStation?.name} · {journeyDate}</p>
          {details.distanceKm > 0 && <p className="field-hint" style={{ marginTop: 0 }}>Fares shown for this {details.distanceKm} km segment.</p>}
          <h3>Select travel class</h3>
          <div className="flex-col gap-sm">
            {details.classes.map((c) => {
              const avail = availabilityOf(c);
              return (
                <label key={c.coachId} className="surface-soft flex-between" style={{ padding: 14, cursor: 'pointer', border: selectedCoachId === c.coachId ? '2px solid var(--accent-edge)' : '2px solid transparent' }}>
                  <span className="flex gap-md" style={{ alignItems: 'center' }}>
                    <input type="radio" name="coach" checked={selectedCoachId === c.coachId} onChange={() => selectCoach(c.coachId)} />
                    <span><strong>{c.seatClassLabel}</strong> <span className="text-muted">{money(c.fare)} / passenger</span></span>
                  </span>
                  <span className={`${avail.tone === 'green' ? 'text-green' : 'text-orange'} strong`} style={{ fontSize: '0.85rem' }}>
                    {avail.label}
                  </span>
                </label>
              );
            })}
          </div>
          <button type="button" className="btn btn-primary" style={{ marginTop: 20 }} onClick={() => selectedCoachId ? setStep(1) : showToast('Please select a class', 'error')}>Continue</button>
        </div>
      )}

      {step === 1 && (
        <div className="card">
          <h2>Passenger details</h2>
          <p className="text-muted">You can add up to 6 passengers per booking.</p>

          {quotaAvailability.length > 0 && (
            <div className="surface-soft" style={{ padding: 14, marginBottom: 16 }}>
              <strong style={{ fontSize: '0.9rem' }}>Quota availability in {selectedCoach?.seatClassLabel} on {journeyDate}</strong>
              <div className="flex gap-sm" style={{ flexWrap: 'wrap', marginTop: 8 }}>
                {quotaAvailability.map((q) => {
                  const usable = q.open && q.remaining > 0;
                  return (
                    <span
                      key={q.quota}
                      className={`badge ${usable ? 'badge-confirmed' : 'badge-neutral'}`}
                      title={q.unavailableReason || `${q.remaining} of ${q.capacity} left`}
                    >
                      {q.label}: {q.open ? `${q.remaining}/${q.capacity}` : 'closed'}
                    </span>
                  );
                })}
              </div>
              <div className="field-hint" style={{ marginTop: 8 }}>
                Quotas are slices of this class&apos;s seats. If the one you pick is full you are moved to
                general at the full fare, and the booking tells you so.
              </div>
            </div>
          )}

          {passengers.map((p, idx) => (
            <div key={idx} className="surface-soft" style={{ padding: 16, marginBottom: 14 }}>
              <div className="flex-between">
                <strong>Passenger {idx + 1}</strong>
                {passengers.length > 1 && <button type="button" className="btn btn-ghost btn-sm" onClick={() => removePassenger(idx)}>Remove</button>}
              </div>
              <div className="grid grid-4" style={{ marginTop: 10 }}>
                <Field
                  label="Full name"
                  required
                  error={passengerErrors[idx]?.fullName}
                >
                  <input value={p.fullName} onChange={(e) => updatePassenger(idx, 'fullName', e.target.value)} />
                </Field>
                <Field
                  label="Age"
                  required
                  error={passengerErrors[idx]?.age}
                >
                  <input type="number" min="1" max="120" value={p.age} onChange={(e) => updatePassenger(idx, 'age', e.target.value)} />
                </Field>
                <Field
                  label="Gender"
                  required
                  error={passengerErrors[idx]?.gender}
                >
                  <select value={p.gender} onChange={(e) => updatePassenger(idx, 'gender', e.target.value)}>
                    <option value="">Select</option>
                    <option>Female</option><option>Male</option><option>Other</option>
                  </select>
                </Field>
                <Field label="Berth preference" optional>
                  <select value={p.berthPreference} onChange={(e) => updatePassenger(idx, 'berthPreference', e.target.value)}>
                    {BERTH_OPTIONS.map((b) => (
                      <option key={b} value={b}>{b.replace(/_/g, ' ')}</option>
                    ))}
                  </select>
                </Field>
              </div>

              <Field
                label="Booking quota"
                hint={`${QUOTA_OPTIONS.find((q) => q.value === p.quota)?.hint}${availabilityFor(p.quota)?.unavailableReason ? ` · ${availabilityFor(p.quota).unavailableReason}` : ''}`}
              >
                <select value={p.quota} onChange={(e) => updatePassenger(idx, 'quota', e.target.value)}>
                  {QUOTA_OPTIONS.map((q) => {
                    const live = availabilityFor(q.value);
                    const sold = live && (!live.open || live.remaining === 0);
                    return (
                      <option key={q.value} value={q.value}>
                        {q.label}{sold ? ' — currently full' : ''}
                      </option>
                    );
                  })}
                </select>
              </Field>

              {p.quota === 'DIVYANG' && (
                <CheckRow
                  type="checkbox"
                  label="I hold a disability certificate for this passenger"
                  checked={Boolean(p.disabilityCertified)}
                  onChange={(e) => updatePassenger(idx, 'disabilityCertified', e.target.checked)}
                />
              )}
            </div>
          ))}
          {passengers.length < 6 && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={addPassenger}>
              + Add another passenger
            </button>
          )}
          <div className="surface-soft" style={{ padding: 'var(--space-4)', marginTop: 'var(--space-4)' }}>
            <CheckRow
              type="checkbox"
              label="Allow RAC (reserved seat, shares a compartment)"
              meta="If no seat is free, RAC passengers are confirmed with a reserved seat and may share. Uncheck to join the waitlist instead."
              checked={allowRac}
              onChange={(e) => toggleRac(e.target.checked)}
            />
          </div>
          <div className="btn-row" style={{ marginTop: 'var(--space-5)' }}>
            <button type="button" className="btn btn-secondary" onClick={() => setStep(0)}>Back</button>
            <button type="button" className="btn btn-primary" onClick={goToReview}>Continue to Review</button>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="card">
          <h2>Review your booking</h2>
          <div className="surface-soft" style={{ padding: 16, marginBottom: 16 }}>
            <p><strong>{details.trainName}</strong> · #{details.trainNumber}</p>
            <p>{sourceStation?.name} → {destinationStation?.name} · {journeyDate}</p>
            <p>Class: {selectedCoach?.seatClassLabel}{quote?.distanceKm ? ` · ${quote.distanceKm} km` : ''}</p>
          </div>
          <h3>Passengers ({passengers.length})</h3>
          <ul>
            {passengers.map((p, i) => (
              <li key={i}>
                {p.fullName}, {p.age}, {p.gender} — {p.berthPreference.replace(/_/g, ' ')} ·{' '}
                {QUOTA_OPTIONS.find((q) => q.value === p.quota)?.label || p.quota}
              </li>
            ))}
          </ul>

          {quoteError && <div className="notice notice-warn" style={{ marginTop: 12 }}>{quoteError}</div>}

          {quoting && <div className="text-muted" style={{ marginTop: 12 }}>Checking live availability...</div>}

          {quote && (
            <>
              {quote.wouldWaitlist > 0 && (
                <div className="notice notice-warn" style={{ marginTop: 14 }}>
                  <div>
                    <strong>{quote.wouldWaitlist} passenger(s) will be waitlisted</strong>
                    This class is full{!allowRac ? ' and you did not allow RAC' : ' and RAC capacity is used up'}. Waitlisted passengers keep their queue position even if they pay, and are promoted automatically when a seat frees up.
                  </div>
                </div>
              )}
              {quote.wouldRac > 0 && (
                <div className="notice notice-info" style={{ marginTop: 14 }}>
                  <div>
                    <strong>{quote.wouldRac} passenger(s) will get RAC</strong>
                    No seat is free right now, but RAC is available — you get a reserved seat and share the compartment.
                  </div>
                </div>
              )}
              {quote.wouldConfirm > 0 && quote.wouldWaitlist === 0 && (
                <div className="notice notice-good" style={{ marginTop: 14 }}>
                  <div><strong>{quote.wouldConfirm} passenger(s) will get a confirmed seat</strong>Seats are held for 10 minutes after you confirm.</div>
                </div>
              )}

              <FareBreakdown breakdown={quote.fareBreakdown} title="What you will pay" />

              <div className="flex-between surface-soft" style={{ padding: 16, marginTop: 12 }}>
                <div>
                  <div>Gross fare · {money(quote.farePerPassenger)} per passenger before quota concessions</div>
                  <div className="text-muted" style={{ fontSize: '0.82rem' }}>{passengers.length} passenger(s)</div>
                </div>
                <strong className="text-orange">{money(quote.totalFare)}</strong>
              </div>
            </>
          )}

          <div className="flex gap-sm" style={{ marginTop: 20 }}>
            <button type="button" className="btn btn-secondary" onClick={() => setStep(1)}>Back</button>
            <button type="button" className="btn btn-primary" disabled={creating || quoting} onClick={submitBooking}>
              {creating ? 'Creating booking...' : 'Confirm & Proceed to Payment'}
            </button>
          </div>
        </div>
      )}

      {step === 3 && booking && (
        <div className="card">
          <h2>Mock Payment</h2>
          <p className="field-hint">This is a simulated payment — no real transaction occurs and no card data is stored.</p>

          {booking.paymentDeadline && (
            <div className={`hold-timer ${holdTone}`} style={{ marginBottom: 16 }}>
              {expired
                ? 'Hold expired — these seats have been released back to the pool.'
                : `Seats held for ${formatRemaining(remainingMs)} — complete payment before the timer ends.`}
            </div>
          )}

          {booking.status === 'WAITLISTED' && (
            <div className="notice notice-warn" style={{ marginBottom: 16 }}>
              <div><strong>You are on the waitlist</strong>Paying keeps your queue position. You are confirmed automatically as soon as a seat is released.</div>
            </div>
          )}

          <div className="surface-soft flex-between" style={{ padding: 16, marginBottom: 16 }}>
            <span>Amount payable</span>
            <strong>{money(booking.totalFare)}</strong>
          </div>
          <Field label="Payment method" required>
            <select value={paymentMethod} onChange={(e) => setPaymentMethod(e.target.value)}>
              <option value="UPI">UPI</option>
              <option value="CARD">Card</option>
              <option value="NETBANKING">Net Banking</option>
            </select>
          </Field>
          <div className="field">
            <CheckRow
              type="checkbox"
              label="Simulate a successful payment"
              meta="Uncheck to see how a failed payment is handled — your seat hold survives, so you can retry."
              checked={simulateSuccess}
              onChange={(e) => setSimulateSuccess(e.target.checked)}
            />
          </div>
          <button type="button" className="btn btn-success btn-block" disabled={paying || expired} onClick={submitPayment}>
            {paying ? 'Processing...' : `Pay ${money(booking.totalFare)}`}
          </button>
          {expired && (
            <button type="button" className="btn btn-secondary btn-block" style={{ marginTop: 10 }} onClick={() => navigate(`/bookings/${booking.id}`)}>
              View booking status
            </button>
          )}
        </div>
      )}

      {step === 4 && booking && (
        <div className="card">
          <div className="flex-between">
            <h2>Booking {booking.status === 'CANCELLED' ? '' : booking.status === 'PAYMENT_FAILED' ? 'Payment Failed' : 'Complete'}</h2>
            <StatusBadge status={booking.status} />
          </div>
          {booking.status === 'PAYMENT_FAILED' ? (
            <>
              <p>Your payment did not go through, but your seats are still held. Retry from the booking details page before the timer runs out.</p>
              <button type="button" className="btn btn-primary" onClick={() => navigate(`/bookings/${booking.id}`)}>Go to Booking</button>
            </>
          ) : (
            <>
              <p>Your YatraRail booking reference is <strong className="text-orange">{booking.bookingReference}</strong> (not an official Indian Railways PNR).</p>
              {booking.status === 'WAITLISTED' && (
                <div className="notice notice-warn" style={{ marginBottom: 14 }}>
                  <div><strong>You are waitlisted</strong>Your place in the queue is secured. We will notify you the moment a seat is confirmed.</div>
                </div>
              )}
              {booking.status === 'RAC' && (
                <div className="notice notice-info" style={{ marginBottom: 14 }}>
                  <div><strong>RAC confirmed</strong>You have a reserved seat and will travel sharing the compartment.</div>
                </div>
              )}
              <div className="surface-soft" style={{ padding: 16 }}>
                <p><strong>{booking.trainName}</strong> · #{booking.trainNumber}</p>
                <p>{booking.sourceStationName} → {booking.destinationStationName} · {booking.journeyDate}</p>
                <p>Class: {booking.seatClass} · Total fare: {money(booking.totalFare)}</p>
              </div>
              <div className="table-wrap" style={{ marginTop: 14 }}>
                <table><caption className="sr-only">Passengers on the booking being created</caption>
                  <thead><tr><th scope="col">Passenger</th><th scope="col">Quota</th><th scope="col">Allocation</th><th scope="col">Status</th></tr></thead>
                  <tbody>
                    {booking.passengers.map((p, i) => (
                      <tr key={i}>
                        <td>{p.fullName}</td>
                        <td>
                          {p.quotaLabel || p.quota}
                          {p.quotaDiscountPercent > 0 && (
                            <span className="text-muted" style={{ fontSize: '0.8rem' }}> ({p.quotaDiscountPercent}% off)</span>
                          )}
                          {p.quotaNote && <div className="field-hint" style={{ marginTop: 2 }}>{p.quotaNote}</div>}
                        </td>
                        <td>
                          {p.coachCode
                            ? <span className="seat-alloc">{p.coachCode}-{p.seatNumber}{p.berthType ? ` · ${p.berthType.replace(/_/g, ' ')}` : ''}</span>
                            : p.passengerStatus === 'WAITLISTED'
                              ? <span className="text-muted">Queue position {p.waitlistPosition ?? '—'}</span>
                              : <span className="text-muted">Seat assigned on confirmation</span>}
                        </td>
                        <td><StatusBadge status={p.passengerStatus} passenger /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <FareBreakdown breakdown={booking.fareBreakdown} title="Fare breakdown" />
              <div className="flex gap-sm" style={{ marginTop: 20 }}>
                <button type="button" className="btn btn-secondary" onClick={() => window.print()}>Download / Print Ticket</button>
                <button type="button" className="btn btn-primary" onClick={() => navigate('/bookings')}>View All Bookings</button>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
