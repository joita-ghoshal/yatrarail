import { useCallback, useEffect, useState } from 'react';
import { StaffApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import EmptyState from '../../components/EmptyState';
import StatusBadge from '../../components/StatusBadge';
import PageHeader from '../../components/PageHeader';

const STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'];

/**
 * The assistance queue: requests passengers raised from their own bookings, for wheelchair and
 * luggage help, medical attention, unaccompanied minors and language support.
 * <p>
 * Open requests are the working list; acknowledging one claims it so the rest of the shift knows
 * somebody is already on it. Resolving closes it, and a passenger can then raise a fresh request for
 * the same passenger if the need comes back.
 */
export default function StaffAssistance() {
  const [requests, setRequests] = useState(null);
  const [statusFilter, setStatusFilter] = useState('OPEN');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  const load = useCallback((status) => {
    setLoading(true);
    setError(null);
    StaffApi.assistance(status ? { status } : {})
      .then((res) => setRequests(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { load(statusFilter); }, [statusFilter, load]);

  const setStatus = async (id, status) => {
    setError(null);
    try {
      await StaffApi.updateAssistance(id, status);
      setNotice(`Assistance request marked ${status.toLowerCase()}.`);
      load(statusFilter);
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  };

  return (
    <div>
      <PageHeader
        title="Assistance Requests"
        subtitle="Requests raised by passengers for help on their journey."
        icon="help"
      />

      {notice && <div className="notice notice-good" style={{ marginBottom: 16 }}>{notice}</div>}

      <div className="toolbar">
        <label className="field-inline" htmlFor="filter-by-status">
          Filter by status
          <select id="filter-by-status" className="control select" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="OPEN">Open only</option>
            <option value="ACKNOWLEDGED">Acknowledged</option>
            <option value="RESOLVED">Resolved</option>
            <option value="">All requests</option>
          </select>
        </label>
      </div>

      {error && <ErrorState message={error} onRetry={() => load(statusFilter)} />}
      {loading && <Loader label="Loading requests..." />}

      {requests && requests.length === 0 && (
        <EmptyState title="Nothing waiting" message="No assistance requests match this filter." />
      )}

      {requests && requests.length > 0 && (
        <div className="table-wrap">
          <table><caption className="sr-only">Assistance requests</caption>
            <thead>
              <tr>
                <th scope="col">Passenger</th>
                <th scope="col">Booking</th>
                <th scope="col">Service</th>
                <th scope="col">Category</th>
                <th scope="col">Details</th>
                <th scope="col">Status</th>
                <th scope="col">Update</th>
              </tr>
            </thead>
            <tbody>
              {requests.map((r) => (
                <tr key={r.id}>
                  <td>
                    <div>{r.passengerName}</div>
                    {r.coachCode && (
                      <div className="text-muted" style={{ fontSize: '0.78rem' }}>
                        {r.coachCode}{r.seatNumber ? `-${r.seatNumber}` : ''}
                      </div>
                    )}
                  </td>
                  <td className="text-muted">{r.bookingReference}</td>
                  <td>
                    <div>{r.trainName}</div>
                    <div className="text-muted" style={{ fontSize: '0.78rem' }}>{r.journeyDate}</div>
                  </td>
                  <td>{r.category.replace(/_/g, ' ').toLowerCase()}</td>
                  <td style={{ maxWidth: 300 }}>{r.details}</td>
                  <td>
                    <StatusBadge status={r.status} />
                    {r.assignedToName && (
                      <div className="text-muted" style={{ fontSize: '0.74rem', marginTop: 3 }}>with {r.assignedToName}</div>
                    )}
                  </td>
                  <td>
                    {r.status === 'RESOLVED' ? (
                      <span className="text-muted" style={{ fontSize: '0.8rem' }}>Closed</span>
                    ) : (
                      <div className="flex gap-sm">
                        {r.status !== 'ACKNOWLEDGED' && (
                          <button type="button" className="btn btn-sm btn-ghost" onClick={() => setStatus(r.id, 'ACKNOWLEDGED')}>
                            Claim
                          </button>
                        )}
                        <button type="button" className="btn btn-sm btn-primary" onClick={() => setStatus(r.id, 'RESOLVED')}>
                          Resolve
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
