import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { money } from '../../utils/format';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import PageHeader from '../../components/PageHeader';
import StatCard from '../../components/StatCard';

export default function AdminReports() {
  const [report, setReport] = useState(null);
  const [refunds, setRefunds] = useState(null);
  const [error, setError] = useState(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const load = () => {
    setError(null);
    const params = {};
    if (from) params.from = from;
    if (to) params.to = to;
    Promise.all([AdminApi.operationsReport(params), AdminApi.refundReport()])
      .then(([r, f]) => { setReport(r.data.data); setRefunds(f.data.data); })
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!report) return <Loader label="Building report..." />;

  const days = report.daily || [];
  const trains = report.byTrain || [];
  const routes = report.byRoute || [];
  const statuses = Object.entries(report.byStatus || {});
  const refundStatuses = Object.entries(refunds?.byStatus || {});

  return (
    <div>
      <PageHeader
        title="Reports"
        subtitle={
          report.from || report.to
            ? `Journey dates ${report.from || 'start'} → ${report.to || 'today'}`
            : 'All journey dates'
        }
        icon="chart"
        actions={
          <div className="toolbar mb-0">
            <label className="field-inline">
              From <input type="date" className="control" value={from} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label className="field-inline">
              To <input type="date" className="control" value={to} onChange={(e) => setTo(e.target.value)} />
            </label>
            <button type="button" className="btn btn-primary btn-sm" onClick={load}>Run</button>
          </div>
        }
      />

      <div className="stat-grid" style={{ marginBottom: 'var(--space-6)' }}>
        <StatCard label="Bookings" value={report.totalBookings} icon="ticket" tone="accent" />
        <StatCard label="Confirmed" value={report.confirmedBookings} icon="check-circle" tone="success" />
        <StatCard label="Cancelled" value={report.cancelledBookings} icon="x-circle" tone="danger" />
        <StatCard label="Waitlisted" value={report.waitlistedBookings} icon="clock" tone="warning" />
        <StatCard label="RAC" value={report.racBookings} icon="ticket" tone="info" />
        <StatCard label="Passengers" value={report.passengerCount} icon="passengers" tone="neutral" />
        <StatCard label="Gross fare" value={money(report.grossFare)} icon="rupee" tone="success" valueClass="stat-value-money" />
        <StatCard label="Platform fees" value={money(report.platformFees)} icon="rupee" tone="neutral" />
        <StatCard label="Registered users" value={report.totalUsers} icon="users" tone="accent" />
      </div>

      <h2 className="section-title">Refund money by state</h2>
      <div className="stat-grid" style={{ marginBottom: 'var(--space-2)' }}>
        <StatCard label="Refunded" value={money(refunds?.refundedTotal)} icon="rupee" tone="success" valueClass="stat-value-money" />
        <StatCard label="Pending" value={money(refunds?.pendingTotal)} icon="clock" tone="warning" />
        <StatCard label="Failed" value={money(refunds?.failedTotal)} icon="x-circle" tone="danger" />
      </div>
      <p className="text-muted" style={{ marginBottom: 'var(--space-6)' }}>
        {refundStatuses.length ? refundStatuses.map(([k, v]) => `${k}: ${v}`).join(' · ') : 'No refunds recorded.'}
        {' '}A pending refund is shown on its own line and never netted off the money collected.
      </p>

      <div className="grid grid-2">
        <div>
          <h2 className="section-title">Bookings by status</h2>
          <div className="table-wrap">
            <table><caption className="sr-only">Bookings by status</caption>
              <thead><tr><th scope="col">Status</th><th scope="col" className="num">Count</th></tr></thead>
              <tbody>
                {statuses.map(([k, v]) => <tr key={k}><td>{k.replace(/_/g, ' ')}</td><td className="num">{v}</td></tr>)}
                {statuses.length === 0 && <tr><td colSpan={2} className="text-muted table-empty">No bookings in range.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h2 className="section-title">Busiest trains</h2>
          <div className="table-wrap">
            <table><caption className="sr-only">Revenue by train</caption>
              <thead><tr><th scope="col">Train</th><th scope="col" className="num">Bookings</th><th scope="col" className="num">Revenue</th></tr></thead>
              <tbody>
                {trains.map((t) => (
                  <tr key={t.trainId}>
                    <td>{t.trainName} (#{t.trainNumber})</td>
                    <td className="num">{t.bookings}</td>
                    <td className="num">{money(t.revenue)}</td>
                  </tr>
                ))}
                {trains.length === 0 && <tr><td colSpan={3} className="text-muted table-empty">No bookings in range.</td></tr>}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <h2 className="section-title" style={{ marginTop: 'var(--space-6)' }}>By journey date</h2>
      <div className="table-wrap">
        <table><caption className="sr-only">Daily bookings and revenue</caption>
          <thead><tr><th scope="col">Journey date</th><th scope="col" className="num">Bookings</th><th scope="col" className="num">Cancelled</th><th scope="col" className="num">Revenue</th></tr></thead>
          <tbody>
            {days.map((d) => (
              <tr key={d.journeyDate}>
                <td>{d.journeyDate}</td>
                <td className="num">{d.bookings}</td>
                <td className="num">{d.cancelled}</td>
                <td className="num">{money(d.revenue)}</td>
              </tr>
            ))}
            {days.length === 0 && <tr><td colSpan={4} className="text-muted table-empty">No bookings in range.</td></tr>}
          </tbody>
        </table>
      </div>

      <h2 className="section-title" style={{ marginTop: 'var(--space-6)' }}>Busiest routes</h2>
      <div className="table-wrap">
        <table><caption className="sr-only">Bookings by route</caption>
          <thead><tr><th scope="col">Route</th><th scope="col" className="num">Bookings</th></tr></thead>
          <tbody>
            {routes.map((r) => <tr key={r.route}><td>{r.route}</td><td className="num">{r.bookingCount}</td></tr>)}
            {routes.length === 0 && <tr><td colSpan={2} className="text-muted table-empty">No bookings in range.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  );
}
