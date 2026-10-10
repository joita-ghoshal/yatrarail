import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { BookingApi } from '../api/endpoints';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';

export default function PassengerDashboard() {
  const { user } = useAuth();
  const [bookings, setBookings] = useState(null);
  const [error, setError] = useState(null);

  const load = () => {
    setError(null);
    setBookings(null);
    BookingApi.mine()
      .then((res) => setBookings(res.data.data))
      .catch(() => setError('Could not load your bookings right now.'));
  };

  useEffect(load, []);

  // An expired hold released its seats, so it is no longer a journey to look forward to.
  const upcoming = (bookings || []).filter((b) => new Date(b.journeyDate) >= new Date().setHours(0, 0, 0, 0) && !['CANCELLED', 'EXPIRED'].includes(b.status));
  const recent = (bookings || []).slice(0, 5);
  const confirmed = (bookings || []).filter((b) => b.status === 'CONFIRMED').length;
  const rac = (bookings || []).filter((b) => b.status === 'RAC').length;
  const waitlisted = (bookings || []).filter((b) => b.status === 'WAITLISTED').length;
  const expired = (bookings || []).filter((b) => b.status === 'EXPIRED').length;

  const confirmedNote = [
    rac > 0 ? `${rac} RAC` : null,
    waitlisted > 0 ? `${waitlisted} waitlisted` : null,
  ].filter(Boolean).join(' · ');

  return (
    <div className="container page">
      <PageHeader
        title={`Welcome back, ${(user?.fullName || '').split(' ')[0] || 'traveller'}`}
        subtitle="Here's what's happening with your journeys."
        actions={<Link to="/search" className="btn btn-primary">Search Trains</Link>}
      />

      {bookings === null && !error && <Loader label="Loading your dashboard..." />}
      {error && <ErrorState message={error} onRetry={load} />}

      {bookings && (
        <>
          <div className="stat-grid" style={{ marginBottom: 'var(--space-5)' }}>
            <StatCard label="Upcoming journeys" value={upcoming.length} icon="calendar" tone="accent" />
            <StatCard label="Total bookings" value={bookings.length} icon="ticket" tone="info" />
            <StatCard
              label="Confirmed"
              value={confirmed + rac}
              icon="check-circle"
              tone="success"
              note={confirmedNote}
            />
          </div>

          {expired > 0 && (
            <div className="notice notice-muted" style={{ marginBottom: 'var(--space-5)' }}>
              <div>
                <strong>{expired} booking(s) expired unpaid</strong>
                Seats held by unpaid bookings are released after 10 minutes. You can rebook from the list below.
              </div>
            </div>
          )}

          <h2 className="section-title">Recent bookings</h2>
          {recent.length === 0 ? (
            <EmptyState title="No bookings yet" message="Search for a train to make your first booking." action={<Link to="/search" className="btn btn-primary">Search Trains</Link>} />
          ) : (
            <div className="table-wrap">
              <table>
                <caption className="sr-only">Your recent bookings</caption>
                <thead>
                  <tr><th scope="col">Reference</th><th scope="col">Train</th><th scope="col">Journey Date</th><th scope="col">Class</th><th scope="col">Status</th><th scope="col"></th></tr>
                </thead>
                <tbody>
                  {recent.map((b) => (
                    <tr key={b.id}>
                      <td><strong>{b.bookingReference}</strong></td>
                      <td>{b.trainName} ({b.trainNumber})</td>
                      <td>{b.journeyDate}</td>
                      <td>{b.seatClass}</td>
                      <td><StatusBadge status={b.status} /></td>
                      <td><Link to={`/bookings/${b.id}`} className="btn btn-ghost btn-sm">View</Link></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
