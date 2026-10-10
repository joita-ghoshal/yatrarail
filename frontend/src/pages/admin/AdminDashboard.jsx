import { useEffect, useState } from 'react';
import { Bar, Doughnut } from 'react-chartjs-2';
import { Chart as ChartJS, ArcElement, BarElement, CategoryScale, LinearScale, Tooltip, Legend } from 'chart.js';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import PageHeader from '../../components/PageHeader';
import StatCard from '../../components/StatCard';

ChartJS.register(ArcElement, BarElement, CategoryScale, LinearScale, Tooltip, Legend);

// Chart.js draws to a canvas, so it cannot read CSS custom properties; these are
// the brand palette as literals, kept here so every chart shares one source.
const C = {
  accent: '#E85D2A',
  green: '#1E7A46',
  danger: '#C0392B',
  info: '#2F6FA3',
  gold: '#B8860B',
  purple: '#8E44AD',
  teal: '#16A085',
};

export default function AdminDashboard() {
  const [stats, setStats] = useState(null);
  const [error, setError] = useState(null);

  const load = () => {
    setError(null);
    AdminApi.dashboard().then((res) => setStats(res.data.data)).catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!stats) return <Loader label="Loading dashboard..." />;

  const metrics = [
    { label: 'Total Users', value: stats.totalUsers, icon: 'users', tone: 'accent' },
    { label: 'Total Trains', value: stats.totalTrains, icon: 'train', tone: 'info' },
    { label: 'Active Trains', value: stats.activeTrains, icon: 'activity', tone: 'success' },
    { label: 'Total Stations', value: stats.totalStations, icon: 'station', tone: 'neutral' },
    { label: 'Total Bookings', value: stats.totalBookings, icon: 'ticket', tone: 'accent' },
    { label: 'Confirmed', value: stats.confirmedBookings, icon: 'check-circle', tone: 'success' },
    { label: 'Cancelled', value: stats.cancelledBookings, icon: 'x-circle', tone: 'danger' },
    { label: 'Demo Revenue', value: `₹${Number(stats.totalDemoRevenue).toLocaleString('en-IN')}`, icon: 'rupee', tone: 'success', valueClass: 'stat-value-money' },
  ];

  const classLabels = Object.keys(stats.bookingsByClass || {});
  const classValues = Object.values(stats.bookingsByClass || {});

  return (
    <div>
      <PageHeader
        title="Admin Dashboard"
        subtitle="A live snapshot of the network, bookings and revenue."
        icon="dashboard"
      />

      <div className="stat-grid" style={{ marginBottom: 'var(--space-7)' }}>
        {metrics.map((m) => (
          <StatCard
            key={m.label}
            label={m.label}
            value={m.value}
            icon={m.icon}
            tone={m.tone}
            valueClass={m.valueClass}
          />
        ))}
      </div>

      <div className="grid grid-2" style={{ marginBottom: 'var(--space-7)' }}>
        <section className="card">
          <div className="card-head">
            <h3>Monthly booking trends</h3>
            <span className="badge badge-neutral">Last {stats.monthlyBookings?.length || 0} months</span>
          </div>
          {stats.monthlyBookings?.length ? (
            <Bar
              data={{
                labels: stats.monthlyBookings.map((m) => m.month),
                datasets: [
                  { label: 'Confirmed', data: stats.monthlyBookings.map((m) => m.confirmed), backgroundColor: C.green, borderRadius: 4 },
                  { label: 'Cancelled', data: stats.monthlyBookings.map((m) => m.cancelled), backgroundColor: C.danger, borderRadius: 4 },
                ],
              }}
              options={{ responsive: true, plugins: { legend: { position: 'bottom' } } }}
            />
          ) : <p className="text-muted">Not enough data yet.</p>}
        </section>

        <section className="card">
          <div className="card-head">
            <h3>Class-wise bookings</h3>
          </div>
          {classLabels.length ? (
            <Doughnut
              data={{
                labels: classLabels,
                datasets: [{ data: classValues, backgroundColor: [C.accent, C.green, C.info, C.gold, C.purple, C.teal] }],
              }}
              options={{ plugins: { legend: { position: 'bottom' } } }}
            />
          ) : <p className="text-muted">Not enough data yet.</p>}
        </section>
      </div>

      <section className="card">
        <div className="card-head">
          <h3>Popular routes</h3>
          {stats.popularRoutes?.length ? <span className="badge badge-rac">{stats.popularRoutes.length} routes</span> : null}
        </div>
        {stats.popularRoutes?.length ? (
          <div className="table-wrap">
            <table>
              <caption className="sr-only">Bookings by route</caption>
              <thead><tr><th scope="col">Route</th><th scope="col" className="num">Bookings</th></tr></thead>
              <tbody>
                {stats.popularRoutes.map((r) => (
                  <tr key={r.route}><td>{r.route}</td><td className="num">{r.bookingCount}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p className="text-muted">No bookings yet.</p>}
      </section>
    </div>
  );
}
