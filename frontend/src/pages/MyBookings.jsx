import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { BookingApi } from '../api/endpoints';
import { money } from '../utils/format';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import StatusBadge from '../components/StatusBadge';
import PageHeader from '../components/PageHeader';

const FILTERS = [
  { key: 'ALL', label: 'All' },
  { key: 'CONFIRMED', label: 'Confirmed' },
  { key: 'RAC', label: 'RAC' },
  { key: 'WAITLISTED', label: 'Waitlisted' },
  { key: 'PAYMENT_PENDING', label: 'Payment pending' },
  { key: 'PAYMENT_FAILED', label: 'Payment failed' },
  { key: 'EXPIRED', label: 'Expired' },
  { key: 'CANCELLED', label: 'Cancelled' },
];

export default function MyBookings() {
  const [bookings, setBookings] = useState(null);
  const [error, setError] = useState(null);
  const [filter, setFilter] = useState('ALL');

  const load = () => {
    setError(null);
    setBookings(null);
    BookingApi.mine().then((res) => setBookings(res.data.data)).catch(() => setError('Could not load your bookings.'));
  };

  useEffect(load, []);

  if (bookings === null && !error) return <Loader label="Loading your bookings..." />;
  if (error) return <div className="container" style={{ padding: 32 }}><ErrorState message={error} onRetry={load} /></div>;

  const filtered = filter === 'ALL' ? bookings : bookings.filter((b) => b.status === filter);

  return (
    <div className="container page">
      <PageHeader title="My Bookings" icon="ticket" />
      {/* aria-pressed, not just a colour change: the selected filter was previously
          only distinguishable by hue. */}
      <div className="seg-group" role="group" aria-label="Filter bookings by status" style={{ marginBottom: 'var(--space-5)' }}>
        {FILTERS.map((f) => {
          const count = f.key === 'ALL' ? bookings.length : bookings.filter((b) => b.status === f.key).length;
          return (
            <button
              key={f.key}
              type="button"
              className="seg"
              aria-pressed={filter === f.key}
              onClick={() => setFilter(f.key)}
            >
              {f.label} ({count})
            </button>
          );
        })}
      </div>

      {filtered.length === 0 ? (
        <EmptyState title="No bookings found" message="Try a different filter, or search for a train to book." action={<Link to="/search" className="btn btn-primary">Search Trains</Link>} />
      ) : (
        <div className="flex-col gap-md">
          {filtered.map((b) => (
            <div key={b.id} className="card flex-between">
              <div>
                <div className="flex gap-sm" style={{ alignItems: 'center', marginBottom: 4 }}>
                  <strong>{b.bookingReference}</strong>
                  <StatusBadge status={b.status} />
                </div>
                <p style={{ margin: 0 }}>{b.trainName} (#{b.trainNumber}) · {b.sourceStationName} → {b.destinationStationName}</p>
                <p className="text-muted text-xs" style={{ margin: 0 }}>
                  {b.journeyDate} · {b.seatClass} · {money(b.totalFare)}
                </p>
                {(b.status === 'WAITLISTED' || b.status === 'PAYMENT_PENDING' || b.status === 'PAYMENT_FAILED') && (
                  <p className="field-hint" style={{ margin: '4px 0 0' }}>
                    {b.status === 'WAITLISTED'
                      ? 'On the waitlist — your queue position is kept and you are promoted automatically.'
                      : b.paymentDeadline
                        ? `Payment due by ${new Date(b.paymentDeadline).toLocaleString()}`
                        : 'Awaiting payment.'}
                  </p>
                )}
              </div>
              <div className="flex gap-sm">
                {['CONFIRMED', 'RAC'].includes(b.status) && (
                  <Link to={`/tickets/${b.id}`} className="btn btn-secondary btn-sm">E-Ticket</Link>
                )}
                <Link to={`/bookings/${b.id}`} className="btn btn-secondary btn-sm">View Details</Link>
              </div>

            </div>
          ))}
        </div>
      )}
    </div>
  );
}
