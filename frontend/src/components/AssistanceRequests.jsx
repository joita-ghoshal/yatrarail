import { useCallback, useEffect, useState } from 'react';
import { AssistanceApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import StatusBadge from './StatusBadge';
import ErrorState from './ErrorState';
import Field from './Field';

const CATEGORIES = [
  { value: 'WHEELCHAIR', label: 'Wheelchair access' },
  { value: 'LUGGAGE', label: 'Luggage help' },
  { value: 'MEDICAL', label: 'Medical assistance' },
  { value: 'UNACCOMPANIED_MINOR', label: 'Unaccompanied minor' },
  { value: 'LANGUAGE', label: 'Language support' },
  { value: 'OTHER', label: 'Something else' },
];

/**
 * The passenger's side of Phase 6: asking railway staff for help on a specific passenger of this
 * booking.
 */
export default function AssistanceRequests({ booking, onRaise }) {
  const [requests, setRequests] = useState(null);
  const [error, setError] = useState(null);
  const [formError, setFormError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ passengerId: '', category: 'WHEELCHAIR', details: '' });

  const load = useCallback(() => {
    setError(null);
    AssistanceApi.forBooking(booking.id)
      .then((res) => setRequests(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, [booking.id]);

  useEffect(load, [load]);

  const travelling = (booking.passengers || []).filter(
    (p) => p.passengerStatus !== 'CANCELLED' && p.passengerStatus !== 'EXPIRED',
  );
  const openFor = new Set(
    (requests || []).filter((r) => r.status !== 'RESOLVED').map((r) => r.bookingPassengerId),
  );
  const canRaise = travelling.some((p) => !openFor.has(p.id));

  const submit = async (e) => {
    e.preventDefault();
    setFormError(null);
    setSubmitting(true);
    try {
      await AssistanceApi.raise(booking.id, {
        passengerId: Number(form.passengerId),
        category: form.category,
        details: form.details.trim() || undefined,
      });
      setForm({ passengerId: '', category: 'WHEELCHAIR', details: '' });
      setOpen(false);
      load();
      onRaise?.();
    } catch (err) {
      setFormError(extractErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <div className="card" style={{ marginTop: 'var(--space-5)' }}>
      <div className="flex-between">
        <div>
          <h3>Need help on this journey?</h3>
          <p className="field-hint" style={{ marginTop: 2 }}>
            Ask railway staff for assistance — wheelchair access, luggage, medical help and more.
          </p>
        </div>
        {canRaise && !open && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setOpen(true)}>
            Request assistance
          </button>
        )}
      </div>

      {open && (
        <form onSubmit={submit} style={{ marginTop: 'var(--space-4)' }} noValidate>
          {formError && <ErrorState message={formError} />}
          <div className="grid grid-2">
            <Field label="Who needs help?" required>
              <select
                value={form.passengerId}
                onChange={(e) => setForm((f) => ({ ...f, passengerId: e.target.value }))}
                required
              >
                <option value="">Select a passenger</option>
                {travelling
                  .filter((p) => !openFor.has(p.id))
                  .map((p) => (
                    <option key={p.id} value={p.id}>
                      {p.fullName}
                      {p.coachCode ? ` · ${p.coachCode}-${p.seatNumber}` : ''}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label="What kind of help?" required>
              <select value={form.category} onChange={(e) => setForm((f) => ({ ...f, category: e.target.value }))}>
                {CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Field
            label="Anything staff should know?"
            optional
            hint="Optional. For example: travelling with a folded wheelchair."
          >
            <textarea
              rows="2"
              maxLength={400}
              value={form.details}
              onChange={(e) => setForm((f) => ({ ...f, details: e.target.value }))}
              placeholder="Optional details"
            />
          </Field>
          <div className="btn-row">
            <button className="btn btn-primary" type="submit" disabled={submitting}>
              {submitting ? 'Sending...' : 'Send to staff'}
            </button>
            <button className="btn btn-ghost" type="button" onClick={() => setOpen(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}

      {!open && requests && requests.length > 0 && (
        <div className="flex-col gap-sm" style={{ marginTop: 'var(--space-4)' }}>
          {requests.map((r) => (
            <div key={r.id} className="surface-soft" style={{ padding: 'var(--space-3)' }}>
              <div className="flex-between gap-sm">
                <div>
                  <strong>{CATEGORIES.find((c) => c.value === r.category)?.label || r.category}</strong>
                  {r.passengerName ? <span className="text-muted"> · {r.passengerName}</span> : null}
                </div>
                <StatusBadge status={r.status} />
              </div>
              {r.details && <p style={{ margin: 'var(--space-1) 0 0' }}>{r.details}</p>}
              <p className="field-hint" style={{ marginTop: 'var(--space-1)' }}>
                {r.status === 'RESOLVED'
                  ? `Resolved${r.resolvedAt ? ` on ${new Date(r.resolvedAt).toLocaleString()}` : ''}. You can raise a new request if you still need help.`
                  : r.status === 'ACKNOWLEDGED'
                    ? `Staff have been notified${r.assignedToName ? ` and ${r.assignedToName} is looking after it` : ''}.`
                    : 'With railway staff. They have been notified.'}
              </p>
            </div>
          ))}
        </div>
      )}

      {!open && requests && requests.length === 0 && !canRaise && travelling.length === 0 && (
        <p className="field-hint mb-0">
          No passenger on this booking is still travelling, so there is nobody to request help for.
        </p>
      )}
    </div>
  );
}