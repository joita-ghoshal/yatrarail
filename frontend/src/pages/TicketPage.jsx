import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { TicketApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { money } from '../utils/format';
import { verdictFor } from '../utils/ticket';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import StatusBadge from '../components/StatusBadge';
import TicketQr from '../components/TicketQr';
import FareBreakdown from '../components/FareBreakdown';

/**
 * The printable e-ticket. Everything shown comes from the server, including the refund policy
 * table, so a ticket can never disagree with what the backend would actually refund.
 */
export default function TicketPage() {
  const { bookingId } = useParams();
  const navigate = useNavigate();

  const [ticket, setTicket] = useState(null);
  const [error, setError] = useState(null);

  const load = () => {
    setError(null);
    setTicket(null);
    TicketApi.forBooking(bookingId)
      .then((res) => setTicket(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, [bookingId]);

  if (error) {
    return (
      <div className="container" style={{ padding: 32 }}>
        <ErrorState message={error} onRetry={load} />
        <div style={{ textAlign: 'center', marginTop: 12 }}>
          <button type="button" className="btn btn-secondary" onClick={() => navigate('/bookings')}>Back to My Bookings</button>
        </div>
      </div>
    );
  }
  if (!ticket) return <Loader label="Loading your ticket..." />;

  const travelling = ticket.passengers.filter((p) => p.passengerStatus === 'CONFIRMED' || p.passengerStatus === 'RAC');

  return (
    <div className="container page" style={{ maxWidth: 820 }}>
      <div className="no-print btn-row">
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate('/bookings')}>← Back to My Bookings</button>
        <div style={{ flex: 1 }} />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => window.print()}>Print / Save as PDF</button>
      </div>

      <div className={`notice ${ticket.validForTravel ? 'notice-muted' : 'notice-warn'} no-print`} style={{ margin: '16px 0 0' }}>
        <div>
          <strong>{ticket.validForTravel ? 'This ticket is valid for travel' : 'This ticket cannot be used'}</strong>
          {ticket.validityMessage}
        </div>
      </div>

      <div className="card ticket-sheet">
        <header className="ticket-head">
          <div>
            <div className="ticket-brand">YatraRail</div>
            <div className="text-muted" style={{ fontSize: '0.8rem' }}>E-ticket · not an official Indian Railways PNR</div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div className="ticket-ref">{ticket.bookingReference}</div>
            <StatusBadge status={ticket.bookingStatus} />
          </div>
        </header>

        <div className="ticket-body">
          <div className="ticket-journey">
            <div>
              <div className="ticket-leg">{ticket.sourceStationName}</div>
              <div className="text-muted" style={{ fontSize: '0.78rem' }}>{ticket.departureTime}</div>
            </div>
            <div className="ticket-arrow">→</div>
            <div style={{ textAlign: 'right' }}>
              <div className="ticket-leg">{ticket.destinationStationName}</div>
              <div className="text-muted" style={{ fontSize: '0.78rem' }}>{ticket.arrivalTime}</div>
            </div>
          </div>

          <div className="ticket-facts">
            <div><span>Train</span><strong>{ticket.trainName} (#{ticket.trainNumber})</strong></div>
            <div><span>Date</span><strong>{ticket.journeyDate}</strong></div>
            <div><span>Class</span><strong>{ticket.seatClass}</strong></div>
            <div><span>Distance</span><strong>{ticket.distanceKm} km</strong></div>
            <div><span>Booked by</span><strong>{ticket.bookedByName}</strong></div>
            <div><span>Code valid until</span><strong>{ticket.qrValidUntil ? new Date(ticket.qrValidUntil).toLocaleString() : '—'}</strong></div>
          </div>

          <div className="table-wrap">
            <table><caption className="sr-only">Passengers on this ticket</caption>
              <thead>
                <tr><th scope="col">Passenger</th><th scope="col">Quota</th><th scope="col">Seat / Coach</th><th scope="col">Status</th></tr>
              </thead>
              <tbody>
                {ticket.passengers.map((p, i) => (
                  <tr key={`${p.fullName}-${i}`}>
                    <td>{p.fullName}{p.age ? <span className="text-muted"> · {p.age}</span> : null}</td>
                    <td>{p.quotaLabel || p.quota}</td>
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

          <div className="ticket-foot">
            <TicketQr payload={ticket.qrPayload} reference={ticket.bookingReference} />
            <div className="ticket-totals">
              {travelling.length > 0 && (
                <p className="field-hint" style={{ marginTop: 0 }}>
                  Travelling: {travelling.map((p) => p.fullName).join(', ')}
                  {travelling.length < ticket.passengers.length && ' (others on this booking are cancelled)'}
                </p>
              )}
              {ticket.fareBreakdown && (
                <>
                  <div><span>Fare paid</span><strong>{money(ticket.fareBreakdown.totalPayable)}</strong></div>
                  <div><span>Platform fee</span><strong>{money(ticket.fareBreakdown.platformFee)}</strong></div>
                  <div><span>GST ({ticket.fareBreakdown.gstPercent}%)</span><strong>{money(ticket.fareBreakdown.gstAmount)}</strong></div>
                  <div className="ticket-total"><span>Total paid</span><strong>{money(ticket.fareBreakdown.totalPayable)}</strong></div>
                </>
              )}
              {ticket.cancellable === false && ticket.cancellationMessage && (
                <p className="field-hint">{ticket.cancellationMessage}</p>
              )}
            </div>
          </div>
        </div>
      </div>

      <div className="no-print" style={{ marginTop: 16 }}>
        <FareBreakdown breakdown={ticket.fareBreakdown} compact />

        {ticket.refundPolicy?.length > 0 && (
          <div className="card" style={{ marginTop: 16 }}>
            <h3 style={{ marginTop: 0 }}>Cancellation and refund policy</h3>
            <div className="table-wrap">
              <table><caption className="sr-only">Cancellation refund tiers</caption>
                <thead>
                  <tr><th scope="col">When you cancel</th><th scope="col">You get back</th><th scope="col">Platform fee</th></tr>
                </thead>
                <tbody>
                  {ticket.refundPolicy.map((row) => (
                    <tr key={row.label}>
                      <td>{row.label}</td>
                      <td>{row.refundPercent}% of the fare</td>
                      <td>{row.platformFeeRefunded ? 'Refunded in full' : 'Not refunded'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="field-hint">
              Refunds are calculated per passenger, so cancelling one seat on a booking does not
              touch the others. GST is refunded only alongside the fare it was charged on.
            </p>
          </div>
        )}

        <div className="card" style={{ marginTop: 16 }}>
          <h3 style={{ marginTop: 0 }}>Check this code before you travel</h3>
          <p className="field-hint">
            Paste the code below into a scanner to see the verdict a gate would get. This is the
            same check staff perform, so if it says the code is not valid yet you will avoid a
            problem at the platform.
          </p>
          <SelfCheck defaultPayload={ticket.qrPayload} />
        </div>
      </div>
    </div>
  );
}

/** Owner-side preview of the verification endpoint, using this ticket's own code. */
function SelfCheck({ defaultPayload }) {
  const [payload, setPayload] = useState(defaultPayload || '');
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const run = async () => {
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
    <>
      <div className="field">
        <label htmlFor="ticket-code">Ticket code</label>
        <input id="ticket-code" value={payload} onChange={(e) => setPayload(e.target.value)} placeholder="YATRARAIL-TICKET..." />
      </div>
      <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={run}>
        {busy ? 'Checking...' : 'Check this code'}
      </button>

      {error && <p className="text-orange" style={{ marginBottom: 0 }}>{error}</p>}

      {verdict && (
        <div className={`notice ${verdict.notice}`} style={{ marginTop: 12 }}>
          <div>
            <strong>{verdict.headline}</strong>
            {verdict.action}
            {result.message && <div className="field-hint">{result.message}</div>}
          </div>
        </div>
      )}
    </>
  );
}
