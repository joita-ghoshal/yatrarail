import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import StatusBadge from '../../components/StatusBadge';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';

const STATUSES = ['ALL', 'CONFIRMED', 'WAITLISTED', 'RAC', 'PAYMENT_PENDING', 'CANCELLED', 'PAYMENT_FAILED'];
const SIZE = 20;

export default function AdminBookings() {
  const [page, setPage] = useState(null);
  const [error, setError] = useState(null);
  const [status, setStatus] = useState('ALL');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const load = () => {
    setError(null);
    const params = { page: pageIndex, size: SIZE };
    if (search) params.search = search;
    if (status !== 'ALL') params.status = status;
    AdminApi.bookings(params)
      .then((res) => setPage(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, [search, status, pageIndex]);

  const submitSearch = (e) => {
    e.preventDefault();
    setPageIndex(0);
    setSearch(searchInput.trim());
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!page) return <Loader label="Loading bookings..." />;

  const rows = page.content || [];
  const revenue = rows
    .filter((b) => b.status === 'CONFIRMED' || b.status === 'WAITLISTED' || b.status === 'RAC')
    .reduce((sum, b) => sum + Number(b.totalFare), 0);

  return (
    <div>
      <PageHeader title="Booking Management" icon="ticket" />
      <div className="toolbar">
        <form className="grow" onSubmit={submitSearch}>
          <input
            className="control"
            placeholder="Search reference or train"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
          <button className="btn btn-secondary" type="submit">Search</button>
        </form>
        <div className="seg-group" role="group" aria-label="Filter by status">
          {STATUSES.map((s) => (
            <button
              key={s}
              type="button"
              className="seg"
              aria-pressed={status === s}
              onClick={() => { setStatus(s); setPageIndex(0); }}
            >
              {s.replace(/_/g, ' ')}
            </button>
          ))}
        </div>
      </div>
      <p className="text-muted">
        {page.totalElements} booking{page.totalElements === 1 ? '' : 's'} match · fare on this page ₹{revenue.toLocaleString('en-IN')}
      </p>
      <div className="table-wrap">
        <table><caption className="sr-only">All bookings</caption>
          <thead><tr><th scope="col">Reference</th><th scope="col">Train</th><th scope="col">Journey Date</th><th scope="col">Class</th><th scope="col" className="num">Fare</th><th scope="col">Status</th></tr></thead>
          <tbody>
            {rows.map((b) => (
              <tr key={b.id}>
                <td>{b.bookingReference}</td>
                <td>{b.trainName} (#{b.trainNumber})</td>
                <td>{b.journeyDate}</td>
                <td>{b.seatClass}</td>
                <td className="num">₹{b.totalFare}</td>
                <td><StatusBadge status={b.status} /></td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={6} className="text-muted table-empty">No bookings match these filters.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <Pagination page={page} onChange={setPageIndex} noun="booking" />
    </div>
  );
}
