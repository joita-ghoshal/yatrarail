import { useCallback, useEffect, useState } from 'react';
import { NotificationApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import Field, { CheckRow, FieldSet } from './Field';
import { useToast } from '../context/ToastContext';

/**
 * Phase 9 — notification settings.
 *
 * <b>Two of the three questions on this screen have a switch. The third does not, and cannot.</b>
 * Booking confirmations, payment failures, cancellations and expiries are the record of something
 * the recipient did; a user who ticks "do not tell me about my bookings" and then misses their own
 * ticket expiring has not configured a preference, they have lost a ticket. So those are listed
 * below as always-on rather than offered as disabled checkboxes, and the backend enforces the same
 * rule independently — this list is an explanation, not the mechanism.
 *
 * <b>Nothing is saved until Save is pressed</b>, so a half-finished edit does not silently take
 * effect, and the button stays disabled until something actually differs from what the server
 * reported.
 */
export default function NotificationPreferences() {
  const { showToast } = useToast();
  const [loaded, setLoaded] = useState(null);
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(() => {
    setError(null);
    return NotificationApi.getPreferences()
      .then((res) => {
        const data = res.data.data;
        setLoaded(data);
        setDraft({
          announcementsEnabled: data.announcementsEnabled,
          waitlistAlerts: data.waitlistAlerts,
          mutedUntil: data.mutedUntil ?? null,
        });
      })
      // A failed load leaves the screen empty rather than showing switches in a state the server
      // has not agreed to — a preference form that looks loaded but is not is worse than none.
      .catch((err) => setError(extractErrorMessage(err)));
  }, []);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    setSaving(true);
    try {
      const res = await NotificationApi.updatePreferences(draft);
      const data = res.data.data;
      setLoaded(data);
      setDraft({
        announcementsEnabled: data.announcementsEnabled,
        waitlistAlerts: data.waitlistAlerts,
        mutedUntil: data.mutedUntil ?? null,
      });
      showToast('Notification settings saved', 'success');
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  /** Mute until a deadline the server will accept: it refuses a mute that has already ended. */
  const muteFor = (hours) => {
    const until = new Date(Date.now() + hours * 3600 * 1000);
    // Built from the local date parts on purpose, not toISOString(). The server compares this
    // against LocalDateTime.now(), which is server wall-clock with no zone attached, and reads the
    // value we send as wall-clock too. toISOString() converts to UTC, so in IST (UTC+5:30) it
    // arrives 5.5 hours in the past and the server rejects every pause as already expired.
    const pad = (n) => String(n).padStart(2, '0');
    setDraft((d) => ({
      ...d,
      mutedUntil:
        `${until.getFullYear()}-${pad(until.getMonth() + 1)}-${pad(until.getDate())}`
        + `T${pad(until.getHours())}:${pad(until.getMinutes())}:${pad(until.getSeconds())}`,
    }));
  };

  const unchanged = loaded
    && loaded.announcementsEnabled === draft.announcementsEnabled
    && loaded.waitlistAlerts === draft.waitlistAlerts
    && (loaded.mutedUntil ?? null) === draft.mutedUntil;

  if (error && !loaded) {
    return (
      <div className="card" style={{ marginBottom: 20 }}>
        <h3>Notifications</h3>
        <div className="notice notice-bad" role="alert">
          <span>We could not load your notification settings. {error}</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
        </div>
      </div>
    );
  }

  if (!loaded || !draft) return null;

  const mutedUntilLabel = draft.mutedUntil
    ? new Date(draft.mutedUntil).toLocaleString()
    : null;

  return (
    <div className="card" style={{ marginBottom: 20 }}>
      <h3>Notifications</h3>
      <p className="text-muted">
        Turn down what is advisory. You will always be told about your own bookings and money.
      </p>

      {error && (
        <div className="notice notice-bad" role="alert" style={{ marginBottom: 12 }}>
          <span>{error} Your changes below have not been saved.</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={load}>Reload</button>
        </div>
      )}

      <FieldSet legend="System announcements" hint="Service notices published by the platform, such as a platform change or a delay. Nobody triggers these individually, so they are yours to refuse.">
        <CheckRow
          type="checkbox"
          checked={draft.announcementsEnabled}
          onChange={(e) => setDraft((d) => ({ ...d, announcementsEnabled: e.target.checked }))}
          label="Announce service notices"
          meta="Off hides notices from your inbox and your unread badge."
        />
      </FieldSet>

      <FieldSet
        legend="Waitlist updates"
        hint="When a coach is full, your booking joins a queue. These control what that queue tells you."
      >
        {(loaded.waitlistOptions || []).map((option) => (
          <CheckRow
            key={option.value}
            type="radio"
            name="waitlist-alerts"
            checked={draft.waitlistAlerts === option.value}
            onChange={() => setDraft((d) => ({ ...d, waitlistAlerts: option.value }))}
            label={option.label}
          />
        ))}
      </FieldSet>

      <Field
        label="Pause announcements and waitlist updates"
        hint="A pause, not a switch: it ends on its own, so there is nothing to remember to undo."
      >
        <select
          value={draft.mutedUntil ? 'custom' : 'none'}
          onChange={(e) => {
            if (e.target.value === 'none') setDraft((d) => ({ ...d, mutedUntil: null }));
            else if (e.target.value !== 'custom') muteFor(Number(e.target.value));
          }}
        >
          <option value="none">Not paused</option>
          <option value={1}>For 1 hour</option>
          <option value={8}>For 8 hours</option>
          <option value={24}>Until tomorrow</option>
          {draft.mutedUntil && <option value="custom">Paused until {mutedUntilLabel}</option>}
        </select>
      </Field>

      <div className="notice notice-muted" style={{ marginTop: 12 }}>
        <div>
          <strong>Always delivered, whatever you choose above</strong>
          <ul style={{ margin: '6px 0 0', paddingLeft: 20 }}>
            {(loaded.alwaysDelivered || []).map((item) => (
              <li key={item.category}>
                <strong>{item.category}</strong> — {item.reason}
              </li>
            ))}
          </ul>
        </div>
      </div>

      <div className="flex gap-sm" style={{ marginTop: 16, alignItems: 'center' }}>
        <button type="button" className="btn btn-primary" disabled={unchanged || saving} onClick={save}>
          {saving ? 'Saving...' : 'Save notification settings'}
        </button>
        {unchanged && <span className="field-hint">No changes to save.</span>}
      </div>
    </div>
  );
}