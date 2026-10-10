import { useState } from 'react';
import { TicketApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { money, statusLabel } from '../utils/format';
import { verdictFor } from '../utils/ticket';
import StatusBadge from './StatusBadge';
import Icon from './Icon';

const VERDICT_ICON = {
  'notice-good': 'check-circle',
  'notice-warn': 'alert',
  'notice-info': 'info',
};

/**
 * The gate-side scanner, shared by the admin console and the staff portal.
 *
 * There is no camera integration here on purpose: the payload a scanner produces is just the string
 * below, so accepting a pasted code exercises exactly the same server-side check a live scanner
 * would, with no hardware dependency and nothing to keep in sync with the signing format.
 *
 * The outcome is always HTTP 200, so a red result is a normal rendered state here rather than an
 * error, and the page stays on screen showing the verdict instead of clearing the input. That matters
 * most at a platform: a rejected code has to stay readable long enough for the passenger in front of
 * the staff member to see why.
 */
export default function TicketVerifier() {
  const [payload, setPayload] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const verify = async () => {
    if (!payload.trim()) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const res = await TicketApi.verify(payload.trim());
      setResult(res.data.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const verdict = result ? verdictFor(result.outcome) : null;

  return (
    <div>
      <div className="card">
        <div className="field">
          <label htmlFor="ticket-code">Ticket code</label>
          <input id="ticket-code"
            value={payload}
            onChange={(e) => setPayload(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') verify(); }}
            placeholder="YATRARAIL-TICKET.1.YATR...."
          />
        </div>
        <div className="flex gap-sm">
          <button type="button" className="btn btn-primary" disabled={busy || !payload.trim()} onClick={verify}>
            {busy ? 'Checking...' : 'Verify Ticket'}
          </button>
          {result && (
            <button type="button" className="btn btn-secondary" onClick={() => { setResult(null); setPayload(''); }}>
              Scan Next
            </button>
          )}
        </div>
        {error && <p className="text-orange">{error}</p>}
      </div>

      {verdict && (
        <div className={`notice ${verdict.notice}`} style={{ marginTop: 16 }}>
          <Icon name={VERDICT_ICON[verdict.notice] || 'info'} size={22} />
          <div>
            <strong style={{ fontSize: '1.05rem' }}>{verdict.headline}</strong>
            <div>{verdict.action}</div>
            {result.message && <div className="field-hint">{result.message}</div>}
            {result.verifiedAt && (
              <div className="field-hint">Checked {new Date(result.verifiedAt).toLocaleString()}</div>
            )}
          </div>
        </div>
      )}

      {result && (
        <div className="card" style={{ marginTop: 16 }}>
          <div className="flex-between">
            <div>
              <h3>{result.bookingReference || 'Unidentified booking'}</h3>
              {result.trainName && (
                <p className="text-muted" style={{ margin: '4px 0 0' }}>
                  {result.trainName} (#{result.trainNumber}) · {result.sourceStationName} → {result.destinationStationName}
                  {result.journeyDate && ` · ${result.journeyDate}`}
                </p>
              )}
              {result.bookingStatus && <p style={{ margin: '8px 0 0' }}><StatusBadge status={result.bookingStatus} /></p>}
            </div>
            <div style={{ textAlign: 'right' }}>
              {result.amountPaid != null && <div>Paid {money(result.amountPaid)}</div>}
              {result.refundedAmount > 0 && <div className="text-green">Refunded {money(result.refundedAmount)}</div>}
              {result.bookedByName && <div className="field-hint">Booked by {result.bookedByName}</div>}
            </div>
          </div>

          {result.passengers?.length > 0 && (
            <div className="table-wrap" style={{ marginTop: 14 }}>
              <table><caption className="sr-only">Passengers on the verified ticket</caption>
                <thead>
                  <tr><th scope="col">Passenger</th><th scope="col">Coach / Seat</th><th scope="col">Status</th></tr>
                </thead>
                <tbody>
                  {result.passengers.map((p, i) => (
                    <tr key={`${p.fullName}-${i}`}>
                      <td>{p.fullName}{p.age ? <span className="text-muted"> · {p.age}</span> : null}</td>
                      <td>
                        {p.coachCode
                          ? <span className="seat-alloc">{p.coachCode}-{p.seatNumber}{p.berthType ? ` · ${p.berthType.replace(/_/g, ' ')}` : ''}</span>
                          : <span className="text-muted">—</span>}
                      </td>
                      <td><StatusBadge status={p.passengerStatus} passenger /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <p className="field-hint" style={{ marginTop: 12 }}>
            {result.outcome === 'VALID'
              ? `Seat class ${result.seatClass || '—'}. Let the passenger travel.`
              : `Do not admit on this code. Outcome: ${result.outcome} (${statusLabel(result.bookingStatus) || 'no booking status'}).`}
          </p>
        </div>
      )}
    </div>
  );
}
