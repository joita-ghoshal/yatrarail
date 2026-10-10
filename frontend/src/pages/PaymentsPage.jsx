import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { PaymentApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { money } from '../utils/format';
import { ATTEMPT_STATUS_LABEL, REFUND_STATUS_LABEL } from '../utils/ticket';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';

/**
 * Everything the passenger has paid and everything owed back to them.
 *
 * Failed attempts are kept in the same list as successful ones on purpose: a booking that took two
 * tries is a real part of the record, and hiding the failure would make the ledger look tidier than
 * it is. Net paid is shown as the server computed it, never recalculated here.
 */
export default function PaymentsPage() {
  const [summary, setSummary] = useState(null);
  const [refunds, setRefunds] = useState(null);
  const [error, setError] = useState(null);
  const [tab, setTab] = useState('PAYMENTS');

  const load = () => {
    setError(null);
    setSummary(null);
    setRefunds(null);
    Promise.all([PaymentApi.history(), PaymentApi.refunds()])
      .then(([s, r]) => {
        setSummary(s.data.data);
        setRefunds(r.data.data);
      })
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  if (error) return <div className="container" style={{ padding: 32 }}><ErrorState message={error} onRetry={load} /></div>;
  if (!summary || !refunds) return <Loader label="Loading your payments..." />;

  return (
    <div className="container page" style={{ maxWidth: 900 }}>
      <PageHeader
        title="Payments &amp; Refunds"
        subtitle="Your full payment record, including any attempt that did not go through."
        icon="card"
      />

      <div className="stat-grid" style={{ margin: 'var(--space-5) 0' }}>
        <StatCard
          label="Net paid"
          value={money(summary.netPaid)}
          icon="rupee"
          tone="accent"
          note={`${money(summary.totalSettled)} settled across ${countSettled(summary)} settled payments`}
        />
        <StatCard
          label="Refunded"
          value={money(summary.totalRefunded)}
          icon="arrow-right"
          tone="success"
          valueClass="stat-value-money"
          note="back to your account or original method"
        />
        <StatCard
          label="Attempts"
          value={summary.attemptCount}
          icon="card"
          tone="info"
          note={`${summary.failedAttemptCount} of them failed`}
        />
        <StatCard
          label="Last activity"
          value={summary.lastActivityAt ? new Date(summary.lastActivityAt).toLocaleDateString() : '—'}
          icon="clock"
          tone="neutral"
          note={summary.lastActivityAt ? new Date(summary.lastActivityAt).toLocaleTimeString() : 'nothing yet'}
        />
      </div>

      <div className="seg-group" role="group" aria-label="Payments or refunds" style={{ marginBottom: 'var(--space-4)' }}>
        <button type="button" className="seg" aria-pressed={tab === 'PAYMENTS'} onClick={() => setTab('PAYMENTS')}>
          Payments ({summary.attempts.length})
        </button>
        <button type="button" className="seg" aria-pressed={tab === 'REFUNDS'} onClick={() => setTab('REFUNDS')}>
          Refunds ({refunds.length})
        </button>
      </div>

      {tab === 'PAYMENTS' && (
        summary.attempts.length === 0 ? (
          <EmptyState
            title="No payments yet"
            message="Once you pay for a booking it will appear here."
            action={<Link to="/search" className="btn btn-primary">Search Trains</Link>}
          />
        ) : (
          <div className="table-wrap">
            <table><caption className="sr-only">Your payment attempts</caption>
              <thead>
                <tr><th scope="col">When</th><th scope="col">Booking</th><th scope="col">Journey</th><th scope="col">Method</th><th scope="col" className="num">Amount</th><th scope="col">Status</th><th scope="col">Reference</th></tr>
              </thead>
              <tbody>
                {summary.attempts.map((a) => (
                  <tr key={a.attemptId}>
                    <td>{new Date(a.attemptedAt).toLocaleString()}</td>
                    <td>
                      <Link to={`/bookings/${a.bookingId}`}>{a.bookingReference}</Link>
                    </td>
                    <td>
                      {a.trainName} (#{a.trainNumber})
                      <div className="field-hint">{a.sourceStationName} → {a.destinationStationName} · {a.journeyDate}</div>
                    </td>
                    <td>{a.method}</td>
                    <td className="num">{money(a.amount)}</td>
                    <td>
                      <StatusBadge status={a.status} />
                      <div className="field-hint">{ATTEMPT_STATUS_LABEL[a.status] || a.status}</div>
                    </td>
                    <td className="text-muted text-xs">{a.transactionReference || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      )}

      {tab === 'REFUNDS' && (
        refunds.length === 0 ? (
          <EmptyState title="No refunds yet" message="Cancel a passenger and the refund record will be listed here." />
        ) : (
          <div className="flex-col gap-md">
            {refunds.map((r) => (
              <div key={r.id} className="card">
                <div className="flex-between">
                  <div>
                    <div className="flex gap-sm" style={{ alignItems: 'center', marginBottom: 4 }}>
                      <strong>{r.refundReference || r.bookingReference}</strong>
                      <StatusBadge status={r.refundStatus} />
                      <span className="badge badge-neutral">{r.scope === 'FULL' ? 'Full cancellation' : 'Partial'}</span>
                    </div>
                    <p style={{ margin: 0 }}>
                      {r.passengerName === 'All passengers'
                        ? 'Entire booking cancelled'
                        : `Passenger: ${r.passengerName}`}
                    </p>
                    <p className="text-muted text-xs" style={{ margin: 0 }}>
                      <Link to={`/bookings/${r.bookingId}`}>{r.bookingReference}</Link>
                      {r.refundPercent != null && ` · ${r.refundPercent}% tier`}
                      {r.daysBeforeJourney != null && ` · ${r.daysBeforeJourney} days before travel`}
                      {r.cancelledAt && ` · ${new Date(r.cancelledAt).toLocaleString()}`}
                    </p>
                    {r.reason && <p className="field-hint" style={{ margin: '4px 0 0' }}>Reason: {r.reason}</p>}
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className="text-green" style={{ fontSize: '1.3rem', fontWeight: 700 }}>{money(r.refundAmount)}</div>
                    <div className="field-hint" style={{ marginTop: 2 }}>{REFUND_STATUS_LABEL[r.refundStatus] || r.refundStatus}</div>
                    {r.processedAt && <div className="field-hint">processed {new Date(r.processedAt).toLocaleDateString()}</div>}
                  </div>
                </div>

                <div className="table-wrap" style={{ marginTop: 12 }}>
                  <table><caption className="sr-only">Refund breakdown for this refund</caption>
                    <thead>
                      <tr><th scope="col" className="num">Fare back</th><th scope="col" className="num">GST back</th><th scope="col" className="num">Platform fee back</th><th scope="col" className="num">Cancellation charge</th></tr>
                    </thead>
                    <tbody>
                      <tr>
                        <td className="num">{money(r.fareRefunded)}</td>
                        <td className="num">{money(r.gstRefunded)}</td>
                        <td className="num">{money(r.platformFeeRefunded)}</td>
                        <td className="num">{money(r.cancellationCharge)}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        )
      )}
    </div>
  );
}

function countSettled(summary) {
  return summary.attempts.filter((a) => a.settled).length;
}
