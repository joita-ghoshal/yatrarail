import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { AssistanceApi, BookingApi, newIdempotencyKey } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { useToast } from '../context/ToastContext';
import useCountdown from '../hooks/useCountdown';
import { formatRemaining, money } from '../utils/format';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import FareBreakdown from '../components/FareBreakdown';
import CancelPassengersModal from '../components/CancelPassengersModal';
import AssistanceRequests from '../components/AssistanceRequests';

export default function BookingDetails() {
  const { bookingId } = useParams();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [booking, setBooking] = useState(null);
  const [error, setError] = useState(null);
  const [showCancelModal, setShowCancelModal] = useState(false);
  const [paying, setPaying] = useState(false);
  const payKeyRef = useRef(newIdempotencyKey('payment'));

  const load = () => {
    setError(null);
    BookingApi.details(bookingId).then((res) => setBooking(res.data.data)).catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, [bookingId]);

  const holdActive = booking && (booking.status === 'PAYMENT_PENDING' || booking.status === 'PAYMENT_FAILED');
  const { remainingMs, expired } = useCountdown(holdActive ? booking.paymentDeadline : null);

  if (error) return <div className="container page"><ErrorState message={error} onRetry={load} /></div>;
  if (!booking) return <Loader label="Loading booking details..." />;

  const canCancel = ['CONFIRMED', 'RAC', 'WAITLISTED'].includes(booking.status);
  const canPay = holdActive && !expired;

  const retryPayment = async () => {
    setPaying(true);
    let serverAnswered = false;
    try {
      const res = await BookingApi.pay(booking.id, { method: booking.payment?.method || 'UPI', simulateSuccess: true }, payKeyRef.current);
      serverAnswered = true;
      setBooking(res.data.data);
      showToast('Payment successful!', 'success');
    } catch (err) {
      serverAnswered = Boolean(err?.response);
      showToast(extractErrorMessage(err), 'error');
    } finally {
      // A key that the server has already seen replays its stored result, so only a
      // transport-level failure may keep it.
      if (serverAnswered) payKeyRef.current = newIdempotencyKey('payment');
      setPaying(false);
    }
  };

  const onCancelled = (res) => {
    setShowCancelModal(false);
    showToast(res.data.message || 'Refund issued', 'success');
    const refunds = res.data.data;
    if (Array.isArray(refunds) && refunds.length === 1 && refunds[0].bookingReference) {
      setBooking((current) => (current ? { ...current, status: 'CANCELLED' } : current));
    }
    BookingApi.details(bookingId).then((r) => setBooking(r.data.data)).catch(() => {});
  };

  const expiredByJob = booking.status === 'EXPIRED';
  const anyStillTravel = booking.passengers.some((p) => p.passengerStatus !== 'CANCELLED' && p.passengerStatus !== 'EXPIRED');
  const canShowTicket = anyStillTravel && !expiredByJob;

  return (
    <div className="container page" style={{ maxWidth: 720 }}>
      <button type="button" className="btn btn-ghost btn-sm" style={{ marginBottom: 12 }} onClick={() => navigate('/bookings')}>← Back to My Bookings</button>
      <PageHeader
        title={booking.bookingReference}
        subtitle="YatraRail-generated reference, not an official Indian Railways PNR"
        icon="ticket"
        actions={<StatusBadge status={booking.status} />}
      />
      <div className="card">
        <div className="surface-soft" style={{ padding: 16 }}>
          <p style={{ margin: 0 }}><strong>{booking.trainName}</strong> · #{booking.trainNumber}</p>
          <p style={{ margin: 0 }}>{booking.sourceStationName} → {booking.destinationStationName}</p>
          <p style={{ margin: 0 }}>
            {booking.journeyDate} · Class: {booking.seatClass}
            {booking.distanceKm > 0 && ` · ${booking.distanceKm} km`}
          </p>
          <p style={{ margin: 0 }}>Fare: {money(booking.farePerPassenger)} per passenger · Total {money(booking.totalFare)}</p>
        </div>

        {booking.fareBreakdown && <FareBreakdown breakdown={booking.fareBreakdown} compact />}

        {expiredByJob && (
          <div className="notice notice-muted" style={{ marginTop: 14 }}>
            <div>
              <strong>Hold expired</strong>
              Payment was not completed inside the 10-minute window, so these seats went back to the pool. You can search again and rebook.
            </div>
          </div>
        )}

        {holdActive && !expiredByJob && (
          <div className={`hold-timer ${expired ? 'hold-timer-urgent' : remainingMs < 120000 ? 'hold-timer-urgent' : ''}`} style={{ marginTop: 14 }}>
            {expired
              ? 'The hold window has closed. Refresh to see the released status.'
              : `Seats held for ${formatRemaining(remainingMs)} — pay before the timer ends.`}
          </div>
        )}

        {booking.status === 'WAITLISTED' && !expiredByJob && (
          <div className="notice notice-warn" style={{ marginTop: 14 }}>
            <div>
              <strong>On the waitlist</strong>
              You keep your place in the queue whether or not you pay. You are confirmed automatically as soon as a seat is released.
            </div>
          </div>
        )}

        {booking.status === 'RAC' && (
          <div className="notice notice-info" style={{ marginTop: 14 }}>
            <div><strong>RAC confirmed</strong>You have a reserved seat and will travel sharing the compartment.</div>
          </div>
        )}

        <h3 className="section-title" style={{ marginTop: 'var(--space-5)' }}>Passengers</h3>
        <div className="table-wrap">
          <table><caption className="sr-only">Passengers on this booking</caption>
            <thead><tr><th scope="col">Name</th><th scope="col">Age</th><th scope="col">Gender</th><th scope="col">Quota</th><th scope="col">Allocation</th><th scope="col">Status</th></tr></thead>
            <tbody>
              {booking.passengers.map((p, i) => (
                <tr key={i}>
                  <td>{p.fullName}</td><td className="num">{p.age}</td><td>{p.gender}</td>
                  <td>
                    {p.quotaLabel || p.quota}
                    {p.quotaDiscountPercent > 0 && (
                      <div className="field-hint" style={{ marginTop: 2 }}>{p.quotaDiscountPercent}% off</div>
                    )}
                    {p.quotaNote && <div className="field-hint" style={{ marginTop: 2 }}>{p.quotaNote}</div>}
                  </td>
                  <td>
                    {p.coachCode
                      ? <span className="seat-alloc">{p.coachCode}-{p.seatNumber}{p.berthType ? ` · ${p.berthType.replace(/_/g, ' ')}` : ''}</span>
                      : p.passengerStatus === 'WAITLISTED'
                        ? <span className="text-muted">Queue #{p.waitlistPosition ?? '—'}</span>
                        : <span className="text-muted">—</span>}
                  </td>
                  <td><StatusBadge status={p.passengerStatus} passenger /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {canShowTicket && <AssistanceRequests booking={booking} onRaise={load} />}

        {booking.payment && (
          <div className="surface-soft flex-between" style={{ padding: 14, marginTop: 16 }}>
            <span>Payment ({booking.payment.method}) · {money(booking.payment.amount)}</span>
            <StatusBadge status={booking.payment.status} />
          </div>
        )}

        <div className="flex gap-sm" style={{ marginTop: 20, flexWrap: 'wrap' }}>
          {canPay && <button type="button" className="btn btn-success" disabled={paying} onClick={retryPayment}>{paying ? 'Processing...' : 'Pay / Retry Payment'}</button>}
          {canCancel && <button type="button" className="btn btn-danger" onClick={() => setShowCancelModal(true)}>Cancel Passengers</button>}
          {booking.status === 'EXPIRED'
            ? <button type="button" className="btn btn-primary" onClick={() => navigate('/search')}>Search Trains</button>
            : canShowTicket
              ? <button type="button" className="btn btn-primary" onClick={() => navigate(`/tickets/${booking.id}`)}>View E-Ticket &amp; QR</button>
              : null}
        </div>
      </div>

      {showCancelModal && (
        <CancelPassengersModal
          booking={booking}
          onClose={() => setShowCancelModal(false)}
          onCancelled={onCancelled}
        />
      )}
    </div>
  );
}
