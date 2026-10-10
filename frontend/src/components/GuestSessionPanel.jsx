import { useEffect, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { extractErrorMessage } from '../api/client';

/**
 * "Continue as guest" - the one-time booking entry point.
 *
 * The guest gives a name and, optionally, a phone number. No email and no password are asked for,
 * because a guest session is not a permanent identity: it is closed by the server on a deadline and
 * can be turned into a real account later if the guest wants to keep their history.
 */
export default function GuestSessionPanel({ onStarted, defaultOpen = false }) {
  const { startTemporarySession } = useAuth();
  const { showToast } = useToast();
  const [open, setOpen] = useState(defaultOpen);
  const [form, setForm] = useState({ fullName: '', phone: '' });
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);

  // The header's "Continue as Guest" deep-links to /login?guest=1 after this
  // panel is already mounted, so the change of intent has to reopen it.
  useEffect(() => {
    if (defaultOpen) setOpen(true);
  }, [defaultOpen]);

  const update = (field) => (e) => setForm((f) => ({ ...f, [field]: e.target.value }));

  const validate = () => {
    const next = {};
    const name = form.fullName.trim();
    if (name.length < 2) next.fullName = 'Please enter the name for this booking';
    const phone = form.phone.trim();
    if (phone && !/^[0-9]{10,15}$/.test(phone)) next.phone = 'Phone must be 10-15 digits';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!validate()) return;
    setLoading(true);
    try {
      const payload = { fullName: form.fullName.trim() };
      if (form.phone.trim()) payload.phone = form.phone.trim();
      const user = await startTemporarySession(payload);
      onStarted?.(user);
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setLoading(false);
    }
  };

  if (!open) {
    return (
      <div id="guest-session" className="card surface-soft" style={{ marginTop: 16 }}>
        <h2 className="section-title">Just want to book a ticket?</h2>
        <p className="field-hint" style={{ marginBottom: 12 }}>
          Continue as a guest. No password needed - your session closes on its own, and you can create
          a full account later without losing your bookings.
        </p>
        <button type="button" className="btn btn-secondary btn-block" onClick={() => setOpen(true)}>
          Continue as Guest
        </button>
      </div>
    );
  }

  return (
    <div id="guest-session" className="card surface-soft" style={{ marginTop: 16 }}>
      <h2 className="section-title">Continue as a guest</h2>
      <p className="field-hint" style={{ marginBottom: 12 }}>
        We only need a name for the ticket. Your session is short-lived and is closed automatically.
      </p>
      <form onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="guestName">Passenger name</label>
          <input
            id="guestName"
            value={form.fullName}
            onChange={update('fullName')}
            placeholder="e.g. Priya Sharma"
            autoComplete="name"
          />
          {errors.fullName && <div className="field-error">{errors.fullName}</div>}
        </div>
        <div className="field">
          <label htmlFor="guestPhone">Phone <span className="field-hint">(optional)</span></label>
          <input
            id="guestPhone"
            value={form.phone}
            onChange={update('phone')}
            placeholder="10-15 digits"
            inputMode="numeric"
          />
          {errors.phone && <div className="field-error">{errors.phone}</div>}
        </div>
        <div className="flex gap-sm">
          <button type="submit" className="btn btn-primary btn-block" disabled={loading}>
            {loading ? 'Starting session...' : 'Start guest session'}
          </button>
          <button
            type="button"
            className="btn btn-ghost"
            disabled={loading}
            onClick={() => {
              setOpen(false);
              setErrors({});
            }}
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
