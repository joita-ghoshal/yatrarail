import { useEffect, useState } from 'react';
import { PaymentApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { money } from '../utils/format';
import Modal from './Modal';
import Loader from './Loader';
import Field from './Field';

/**
 * Cancels a chosen subset of a booking's passengers.
 *
 * The quoted figures always come from the read-only preview endpoint rather than being computed in
 * the browser, so what the passenger agrees to on screen is what the backend will pay out. Selecting
 * everyone is a whole-booking cancellation and additionally returns the platform fee, which the
 * preview states explicitly instead of leaving the passenger to notice the difference.
 */
export default function CancelPassengersModal({ booking, onClose, onCancelled }) {
  const selectable = booking.passengers.filter(
    (p) => p.passengerStatus === 'CONFIRMED' || p.passengerStatus === 'RAC' || p.passengerStatus === 'WAITLISTED'
  );
  const [selected, setSelected] = useState(selectable.map((p) => p.id));
  const [preview, setPreview] = useState(null);
  const [previewError, setPreviewError] = useState(null);
  const [reason, setReason] = useState('');
  const [reasonTouched, setReasonTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (selected.length === 0) {
      setPreview(null);
      return undefined;
    }
    let live = true;
    setPreview(null);
    setPreviewError(null);
    PaymentApi.refundPreview(booking.id, selected)
      .then((res) => { if (live) setPreview(res.data.data); })
      .catch((err) => { if (live) setPreviewError(extractErrorMessage(err)); });
    return () => { live = false; };
  }, [booking.id, selected]);

  const toggle = (id) => {
    setSelected((current) => (current.includes(id) ? current.filter((x) => x !== id) : [...current, id]));
  };

  const everyone = selected.length === selectable.length && selected.length > 0;

  const submit = async () => {
    if (!reason.trim()) return;
    setSubmitting(true);
    try {
      const res = await PaymentApi.cancelPassengers(booking.id, selected, reason.trim());
      onCancelled(res.data);
    } catch (err) {
      setPreviewError(extractErrorMessage(err));
      setSubmitting(false);
    }
  };

  return (
    <Modal title="Cancel passengers" onClose={onClose}>
      {selectable.length === 0 ? (
        <p>There is nobody left to cancel on this booking.</p>
      ) : (
        <>
          <p className="field-hint">
            Untick anyone who is still travelling. Seats are released straight away and the next
            person on the waitlist is promoted.
          </p>

          {/* A fieldset/legend, so a screen reader announces the question ("who is still
              travelling?") before each option instead of four unnamed checkboxes. */}
          <fieldset className="check-list" style={{ margin: 'var(--space-3) 0' }}>
            <legend className="field-label">
              Who is still travelling?
              <span className="text-muted text-xs"> ({selected.length} of {selectable.length} to cancel)</span>
            </legend>
            {selectable.map((p) => (
              <label key={p.id} className="check-row">
                <input type="checkbox" checked={selected.includes(p.id)} onChange={() => toggle(p.id)} />
                <span className="check-body">
                  <span className="check-title">{p.fullName}</span>
                  <span className="check-meta">
                    {p.quotaLabel || p.quota}
                    {p.coachCode ? ` · ${p.coachCode}-${p.seatNumber}` : ''}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>

          <div className="btn-row">
            <button type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => setSelected(everyone ? [] : selectable.map((p) => p.id))}
            >
              {everyone ? 'Clear all' : 'Select all'}
            </button>
          </div>

          {previewError && <p className="text-orange">{previewError}</p>}

          {!preview && !previewError && selected.length > 0 && <Loader label="Calculating your refund..." />}

          {preview && (
            <div className="quote-box quote-box-good" style={{ marginTop: 'var(--space-4)' }}>
              <div className="flex-between">
                <strong>{everyone ? 'Whole-booking cancellation' : 'Partial cancellation'}</strong>
                <span className="badge badge-neutral">{preview.tierLabel} · {preview.refundPercent}%</span>
              </div>
              <p className="field-hint" style={{ margin: '4px 0 10px' }}>
                {preview.daysBeforeJourney} day{preview.daysBeforeJourney === 1 ? '' : 's'} before a
                {preview.journeyDate ? ` ${preview.journeyDate}` : ' departure'} journey.
              </p>

              <div className="table-wrap table-compact" style={{ marginTop: 'var(--space-3)' }}>
                <table>
                  <caption className="sr-only">Refund due for each selected passenger</caption>
                  <thead>
                    <tr>
                      <th scope="col">Passenger</th>
                      <th scope="col" className="num">Paid</th>
                      <th scope="col" className="num">Charge</th>
                      <th scope="col" className="num">Back</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.lines.map((l) => (
                      <tr key={l.bookingPassengerId}>
                        <th scope="row" style={{ fontWeight: 500 }}>{l.fullName}</th>
                        <td className="num">{money(l.farePaid + l.gstPaid)}</td>
                        <td className="num">{money(l.cancellationCharge)}</td>
                        <td className="num text-green"><strong>{money(l.totalRefunded)}</strong></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <dl className="totals">
                <div><dt>Fare refunded</dt><dd>{money(preview.fareRefunded)}</dd></div>
                <div><dt>GST refunded</dt><dd>{money(preview.gstRefunded)}</dd></div>
                <div>
                  <dt>Platform fee</dt>
                  <dd>{preview.platformFeeRefunded > 0 ? `${money(preview.platformFeeRefunded)} back` : `${money(preview.platformFeeForfeited)} forfeited`}</dd>
                </div>
                <div className="totals-grand totals-grand-good"><dt>Total refund</dt><dd>{money(preview.totalRefund)}</dd></div>
              </dl>

              {!everyone && (
                <p className="field-hint">
                  The platform fee is only returned when every passenger on the booking is
                  cancelled, so it stays with YatraRail here.
                </p>
              )}
            </div>
          )}

          {preview?.cancellable === false && (
            <p className="text-orange">{preview.message}</p>
          )}

          <Field label="Reason for cancellation" required error={reasonTouched && !reason.trim() ? 'Enter a reason' : null}>
            <textarea rows={3} value={reason} onBlur={() => setReasonTouched(true)} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Change of plans" />
          </Field>

          <div className="btn-row">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={submitting}>Keep Booking</button>
            <button type="button"
              className="btn btn-danger"
              disabled={submitting || selected.length === 0 || !reason.trim() || preview?.cancellable === false}
              onClick={submit}
            >
              {submitting ? 'Cancelling...' : everyone ? 'Cancel Entire Booking' : `Cancel ${selected.length} Passenger${selected.length === 1 ? '' : 's'}`}
            </button>
          </div>
        </>
      )}
    </Modal>
  );
}
