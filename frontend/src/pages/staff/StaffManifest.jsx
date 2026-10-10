import { useCallback, useEffect, useState } from 'react';
import { StaffApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import EmptyState from '../../components/EmptyState';
import StatusBadge from '../../components/StatusBadge';
import PageHeader from '../../components/PageHeader';

const BOARDING_STATES = ['NOT_BOARDED', 'BOARDED', 'NO_SHOW'];

/**
 * The passenger manifest for one train on one day, with gate check-in.
 * <p>
 * This is the page a conductor actually works from, so the design priority is scanning speed: each
 * passenger is one row with three one-tap buttons, and marking somebody updates only that row rather
 * than refetching the whole manifest — reloading a full train list after every scan is what makes a
 * gate screen unusable when the queue is moving.
 */
export default function StaffManifest() {
  const [trains, setTrains] = useState(null);
  const [selectedTrainId, setSelectedTrainId] = useState('');
  const [journeyDate, setJourneyDate] = useState(new Date().toISOString().slice(0, 10));
  const [passengers, setPassengers] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [onlyUnboarded, setOnlyUnboarded] = useState(false);

  useEffect(() => {
    StaffApi.trains()
      .then((res) => setTrains(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, []);

  const load = useCallback((trainId, date) => {
    if (!trainId) return;
    setLoading(true);
    setError(null);
    StaffApi.passengers(trainId, date)
      .then((res) => setPassengers(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setLoading(false));
  }, []);

  const mark = async (passengerId, status, name) => {
    setNotice(null);
    try {
      const res = await StaffApi.markBoarding(passengerId, status);
      const updated = res.data.data;
      // Patch the single row in place. A refetch here would reset the scroll position a conductor
      // is halfway down, which is the whole point of this page.
      setPassengers((rows) => rows.map((b) => ({
        ...b,
        passengers: b.passengers.map((p) => (p.id === passengerId
          ? { ...p, boarding: { status: updated.status, markedByName: updated.markedByName, markedAt: updated.markedAt } }
          : p)),
      })));
      setNotice(`${name} marked ${status === 'NO_SHOW' ? 'NO SHOW' : status === 'BOARDED' ? 'BOARDED' : 'not boarded'}.`);
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  };

  const rows = [];
  (passengers || []).forEach((b) => {
    b.passengers?.forEach((p) => {
      if (onlyUnboarded && p.boarding?.status === 'BOARDED') return;
      rows.push({ booking: b, passenger: p });
    });
  });

  return (
    <div>
      <PageHeader
        title="Manifest & Boarding"
        subtitle="Who is travelling on this service, and who has actually boarded. Marking a passenger records the gate state only — it never refunds or cancels."
        icon="clipboard"
      />

      <div className="toolbar">
        <label className="field-inline" htmlFor="train">
          Train
          <select id="train" className="control select"
            value={selectedTrainId}
            onChange={(e) => {
              setSelectedTrainId(e.target.value);
              setPassengers(null);
              if (e.target.value) load(e.target.value, journeyDate);
            }}
          >
            <option value="">Select a train</option>
            {(trains || []).map((t) => (
              <option key={t.id} value={t.id}>{t.name} (#{t.trainNumber})</option>
            ))}
          </select>
        </label>
        <label className="field-inline" htmlFor="journey-date">
          Journey date
          <input id="journey-date" className="control"
            type="date"
            value={journeyDate}
            onChange={(e) => {
              setJourneyDate(e.target.value);
              setPassengers(null);
              if (selectedTrainId) load(selectedTrainId, e.target.value);
            }}
          />
        </label>
        <label className="field-inline">
          <input
            type="checkbox"
            checked={onlyUnboarded}
            onChange={(e) => setOnlyUnboarded(e.target.checked)}
          />
          Hide boarded
        </label>
      </div>

      {notice && <div className="notice notice-good" style={{ marginBottom: 16 }}>{notice}</div>}
      {error && <ErrorState message={error} onRetry={() => load(selectedTrainId, journeyDate)} />}
      {loading && <Loader label="Loading manifest..." />}

      {passengers && passengers.length === 0 && (
        <EmptyState title="No passengers on this service" message="No confirmed, RAC or waitlisted bookings for this train and date." />
      )}

      {rows.length > 0 && (
        <div className="table-wrap">
          <table><caption className="sr-only">Passenger manifest for the selected train and date</caption>
            <thead>
              <tr>
                <th scope="col">Passenger</th>
                <th scope="col">Booking</th>
                <th scope="col">Class</th>
                <th scope="col">Seat</th>
                <th scope="col">Boarding</th>
                <th scope="col" style={{ width: 260 }}>Check in</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ booking, passenger }) => (
                <tr key={passenger.id}>
                  <td>
                    <div>{passenger.fullName}</div>
                    <div className="text-muted" style={{ fontSize: '0.78rem' }}>
                      {passenger.age} · {passenger.gender}
                      {passenger.quota && passenger.quota !== 'GENERAL' ? ` · ${passenger.quota}` : ''}
                    </div>
                  </td>
                  <td className="text-muted">{booking.bookingReference}</td>
                  <td className="text-muted">{booking.coach?.seatClass || '—'}</td>
                  <td>
                    {passenger.seat
                      ? <span className="seat-alloc">{passenger.seat.coach?.coachCode}-{passenger.seat.seatNumber}</span>
                      : <span className="text-muted">WL</span>}
                  </td>
                  <td><BoardingCell boarding={passenger.boarding} /></td>
                  <td>
                    <div className="flex gap-sm">
                      {BOARDING_STATES.map((s) => (
                        <button
                          key={s}
                          className={`btn btn-sm ${passenger.boarding?.status === s ? 'btn-secondary' : 'btn-ghost'}`}
                          onClick={() => mark(passenger.id, s, passenger.fullName)}
                        >
                          {s === 'NOT_BOARDED' ? 'Reset' : s === 'NO_SHOW' ? 'No show' : 'Boarded'}
                        </button>
                      ))}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {passengers && rows.length === 0 && onlyUnboarded && (
        <EmptyState title="Everyone has boarded" message="No passengers are still waiting at the gate." />
      )}
    </div>
  );
}

/** Shows the gate state plus who marked it, so a disputed scan is answerable on the spot. */
function BoardingCell({ boarding }) {
  if (!boarding || boarding.status === 'NOT_BOARDED') {
    return <StatusBadge status="NOT_BOARDED" passenger />;
  }
  return (
    <div>
      <StatusBadge status={boarding.status} passenger />
      {boarding.markedByName && (
        <div className="text-muted" style={{ fontSize: '0.74rem', marginTop: 3 }}>
          by {boarding.markedByName}
        </div>
      )}
    </div>
  );
}
