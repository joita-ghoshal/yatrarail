import { useCallback, useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { money } from '../../utils/format';
import { REFUND_STATUS_LABEL } from '../../utils/ticket';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import EmptyState from '../../components/EmptyState';
import StatusBadge from '../../components/StatusBadge';
import PageHeader from '../../components/PageHeader';
import StatCard from '../../components/StatCard';
import Pagination from '../../components/Pagination';

const TABS = [
  { key: 'REPORT', label: 'Reconciliation' },
  { key: 'PAYMENTS', label: 'Payment ledger' },
  { key: 'REFUNDS', label: 'Refunds' },
];

const SIZE = 20;

/**
 * The finance view: what was collected, what was given back, and whether the two agree.
 *
 * Collected and refunded are deliberately never netted into a single headline number. Money
 * already paid back is not the same as money still owed, and a pending refund in particular is a
 * liability rather than a loss, so the three are shown separately and reconciled against bookings.
 *
 * Phase 10: the two ledgers are now paged and searched on the server. The screen used to pull every
 * payment and every refund into the browser and filter them there, which works until the ledger
 * grows and then stops working all at once. Only the current page is fetched now, and the search box
 * asks the database rather than the loaded rows.
 */
export default function AdminReconciliation() {
  const [tab, setTab] = useState('REPORT');
  const [report, setReport] = useState(null);

  const [paymentsPage, setPaymentsPage] = useState(null);
  const [refundsPage, setRefundsPage] = useState(null);
  const [paymentsPageIndex, setPaymentsPageIndex] = useState(0);
  const [refundsPageIndex, setRefundsPageIndex] = useState(0);

  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');

  const [error, setError] = useState(null);

  const loadReport = useCallback(() => {
    setError(null);
    AdminApi.reconciliation()
      .then((res) => setReport(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, []);

  const loadPayments = useCallback(() => {
    const params = { page: paymentsPageIndex, size: SIZE };
    if (search) params.search = search;
    setPaymentsPage(null);
    AdminApi.payments(params)
      .then((res) => setPaymentsPage(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, [paymentsPageIndex, search]);

  const loadRefunds = useCallback(() => {
    const params = { page: refundsPageIndex, size: SIZE };
    if (search) params.search = search;
    setRefundsPage(null);
    AdminApi.refunds(params)
      .then((res) => setRefundsPage(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, [refundsPageIndex, search]);

  useEffect(loadReport, [loadReport]);
  useEffect(() => { if (tab === 'PAYMENTS') loadPayments(); }, [tab, loadPayments]);
  useEffect(() => { if (tab === 'REFUNDS') loadRefunds(); }, [tab, loadRefunds]);

  const submitSearch = (e) => {
    e.preventDefault();
    setPaymentsPageIndex(0);
    setRefundsPageIndex(0);
    setSearch(searchInput.trim());
  };

  const reloadActive = () => {
    loadReport();
    if (tab === 'PAYMENTS') loadPayments();
    if (tab === 'REFUNDS') loadRefunds();
  };

  const switchTab = (key) => {
    // A search term typed for one ledger should not silently filter the other.
    setSearch('');
    setSearchInput('');
    setPaymentsPageIndex(0);
    setRefundsPageIndex(0);
    setTab(key);
  };

  if (error) return <div className="container" style={{ padding: 32 }}><ErrorState message={error} onRetry={reloadActive} /></div>;
  if (!report) return <Loader label="Loading the ledger..." />;

  return (
    <div>
      <PageHeader
        title="Payments &amp; Reconciliation"
        subtitle="Every payment attempt and every refund, checked against the bookings they belong to."
        icon="scale"
      />

      <div className="stat-grid" style={{ margin: 'var(--space-5) 0' }}>
        <StatCard
          label="Collected"
          value={money(report.totalCollected)}
          icon="rupee"
          tone="accent"
          valueClass="stat-value-money"
          note={`${report.settledPaymentCount} settled payment${report.settledPaymentCount === 1 ? '' : 's'}`}
        />
        <StatCard
          label="Refunded"
          value={money(report.totalRefunded)}
          icon="arrow-right"
          tone="danger"
          valueClass="text-danger"
          note={`${report.refundCount} refund${report.refundCount === 1 ? '' : 's'}`}
        />
        <StatCard
          label="Net collected"
          value={money(report.netCollected)}
          icon="scale"
          tone="info"
          note="collected minus refunded"
        />
        <StatCard
          label="Failed attempts"
          value={report.failedPaymentCount}
          icon="x-circle"
          tone={report.failedPaymentCount > 0 ? 'danger' : 'neutral'}
          valueClass={report.failedPaymentCount > 0 ? 'stat-value-warn' : ''}
          note={`${money(report.totalFailedAttempts)} never captured`}
        />
      </div>

      <div className={`notice ${report.reconciled ? 'notice-good' : 'notice-warn'}`} style={{ marginBottom: 18 }}>
        <div>
          <strong>{report.reconciled ? 'Ledger reconciles' : `${report.issues.length} issue${report.issues.length === 1 ? '' : 's'} need attention`}</strong>
          {report.reconciled
            ? 'Every settled payment maps to a paid booking and every refund maps to a cancelled passenger.'
            : 'The ledger and the bookings disagree on at least one row. Each issue below names the booking involved.'}
          {report.generatedAt && <div className="field-hint">Generated {new Date(report.generatedAt).toLocaleString()}</div>}
        </div>
      </div>

      <div className="filter-bar">
        <div className="seg-group" role="group" aria-label="Ledger section">
          {TABS.map((t) => (
            <button key={t.key} type="button" className="seg" aria-pressed={tab === t.key} onClick={() => switchTab(t.key)}>
              {t.label}
            </button>
          ))}
        </div>
        {(tab === 'PAYMENTS' || tab === 'REFUNDS') && (
          <form className="flex gap-sm" onSubmit={submitSearch}>
            <label className="sr-only" htmlFor="ledger-search">Search the ledger</label>
            <input
              id="ledger-search"
              className="control"
              type="search"
              placeholder="Reference, train or passenger"
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
            />
            <button className="btn btn-secondary" type="submit">Search</button>
          </form>
        )}
      </div>

      {tab === 'REPORT' && (
        <div className="card">
          <h3 style={{ marginTop: 0 }}>What the ledger says</h3>
          <dl className="totals">
            <div><dt>Payment attempts recorded</dt><dd>{report.paymentAttemptCount}</dd></div>
            <div><dt>Of which settled</dt><dd>{report.settledPaymentCount}</dd></div>
            <div><dt>Of which failed</dt><dd className={report.failedPaymentCount > 0 ? 'stat-value-bad' : ''}>{report.failedPaymentCount}</dd></div>
            <div><dt>Bookings</dt><dd>{report.bookingCount}</dd></div>
            <div><dt>Paid bookings</dt><dd>{report.paidBookingCount}</dd></div>
            <div><dt>Refunds issued</dt><dd>{report.refundCount}</dd></div>
            <div><dt>Refunds still pending</dt><dd className={report.pendingRefundCount > 0 ? 'stat-value-warn' : ''}>{report.pendingRefundCount}</dd></div>
            <div><dt>Refunds that failed</dt><dd className={report.failedRefundCount > 0 ? 'stat-value-bad' : ''}>{report.failedRefundCount}</dd></div>
          </dl>

          <h3>Issues</h3>
          {!report.issues || report.issues.length === 0 ? (
            <EmptyState title="Nothing to reconcile" message="No mismatches were found between the payment ledger and the booking records." />
          ) : (
            <div className="table-wrap">
              <table><caption className="sr-only">Reconciliation issues</caption>
                <thead>
                  <tr><th scope="col">Booking</th><th scope="col">Code</th><th scope="col">Severity</th><th scope="col" className="num">Amount</th><th scope="col">What happened</th></tr>
                </thead>
                <tbody>
                  {report.issues.map((issue, i) => (
                    <tr key={`${issue.bookingId}-${issue.code}-${i}`}>
                      <td>{issue.bookingReference || issue.bookingId}</td>
                      <td className="text-muted text-xs">{issue.code}</td>
                      <td>
                        <span className={`badge ${issue.severity === 'ERROR' ? 'badge-failed' : 'badge-pending'}`}>
                          {issue.severity}
                        </span>
                      </td>
                      <td className="num">{issue.amount != null ? money(issue.amount) : '—'}</td>
                      <td>{issue.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {tab === 'PAYMENTS' && (
        !paymentsPage ? <Loader label="Loading payments..." /> : (
          paymentsPage.content.length === 0 ? (
            <EmptyState
              title="No payments found"
              message={search ? 'No payments match this search.' : 'Payments will appear here as passengers pay for bookings.'}
            />
          ) : (
            <>
              <div className="table-wrap card" style={{ padding: 0 }}>
                <table><caption className="sr-only">Payment attempts and the refunds against them</caption>
                  <thead>
                    <tr><th scope="col">Paid at</th><th scope="col">Booking</th><th scope="col">Passenger</th><th scope="col">Journey</th><th scope="col">Method</th><th scope="col" className="num">Amount</th><th scope="col" className="num">Refunded</th><th scope="col" className="num">Net</th><th scope="col">Status</th><th scope="col" className="num">Attempts</th></tr>
                  </thead>
                  <tbody>
                    {paymentsPage.content.map((p) => (
                      <tr key={p.paymentId}>
                        <td>{p.paidAt ? new Date(p.paidAt).toLocaleString() : '—'}</td>
                        <td>{p.bookingReference}</td>
                        <td>{p.passengerName || '—'}</td>
                        <td>
                          {p.trainName} (#{p.trainNumber})
                          <div className="field-hint">{p.sourceStationName} → {p.destinationStationName} · {p.journeyDate}</div>
                        </td>
                        <td>{p.method}</td>
                        <td className="num">{money(p.amount)}</td>
                        <td className="text-green num">{p.refundedAmount > 0 ? money(p.refundedAmount) : '—'}</td>
                        <td className="num"><strong>{money(p.netCollected)}</strong></td>
                        <td><StatusBadge status={p.status} /></td>
                        <td className="num">
                          {p.attemptCount}
                          {p.failedAttemptCount > 0 && <div className="field-hint text-orange">{p.failedAttemptCount} failed</div>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination page={paymentsPage} onChange={setPaymentsPageIndex} noun="row" />
            </>
          )
        )
      )}

      {tab === 'REFUNDS' && (
        !refundsPage ? <Loader label="Loading refunds..." /> : (
          refundsPage.content.length === 0 ? (
            <EmptyState
              title="No refunds found"
              message={search ? 'No refunds match this search.' : 'Refunds appear here as passengers cancel.'}
            />
          ) : (
            <>
              <div className="table-wrap card" style={{ padding: 0 }}>
                <table><caption className="sr-only">Refund records</caption>
                  <thead>
                    <tr><th scope="col">Cancelled</th><th scope="col">Refund ref</th><th scope="col">Booking</th><th scope="col">Passenger</th><th scope="col">Scope</th><th scope="col" className="num">Tier</th><th scope="col" className="num">Charge</th><th scope="col" className="num">Amount</th><th scope="col">Status</th></tr>
                  </thead>
                  <tbody>
                    {refundsPage.content.map((r) => (
                      <tr key={r.id}>
                        <td>{r.cancelledAt ? new Date(r.cancelledAt).toLocaleString() : '—'}</td>
                        <td>{r.refundReference || '—'}</td>
                        <td>{r.bookingReference}</td>
                        <td>{r.passengerName}</td>
                        <td>{r.scope === 'FULL' ? 'Full' : 'Partial'}</td>
                        <td className="num">{r.refundPercent}%</td>
                        <td className="num">{money(r.cancellationCharge)}</td>
                        <td className="num"><strong className="text-green">{money(r.refundAmount)}</strong></td>
                        <td>
                          <StatusBadge status={r.refundStatus} />
                          <div className="field-hint">{REFUND_STATUS_LABEL[r.refundStatus] || r.refundStatus}</div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination page={refundsPage} onChange={setRefundsPageIndex} noun="refund" />
            </>
          )
        )
      )}
    </div>
  );
}
