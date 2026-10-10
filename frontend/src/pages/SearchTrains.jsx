import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { StationApi, TrainApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { availabilityOf, money } from '../utils/format';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import EmptyState from '../components/EmptyState';
import PageHeader from '../components/PageHeader';
import { useToast } from '../context/ToastContext';

const CLASS_LABELS = { FIRST_AC: '1A', SECOND_AC: '2A', THIRD_AC: '3A', SLEEPER: 'SL', CHAIR_CAR: 'CC', SECOND_SEATING: '2S' };

export default function SearchTrains() {
  const [params, setParams] = useSearchParams();
  const { showToast } = useToast();
  const navigate = useNavigate();

  const [stations, setStations] = useState([]);
  const [form, setForm] = useState({
    sourceCode: params.get('sourceCode') || '',
    destinationCode: params.get('destinationCode') || '',
    journeyDate: params.get('journeyDate') || new Date().toISOString().slice(0, 10),
  });

  const [results, setResults] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [sortBy, setSortBy] = useState('departure');
  const [classFilter, setClassFilter] = useState('ALL');

  useEffect(() => {
    StationApi.list().then((res) => setStations(res.data.data)).catch(() => {});
  }, []);

  const runSearch = async (e) => {
    if (e) e.preventDefault();
    if (!form.sourceCode || !form.destinationCode) {
      showToast('Please choose both source and destination stations', 'error');
      return;
    }
    if (form.sourceCode === form.destinationCode) {
      showToast('Source and destination cannot be the same', 'error');
      return;
    }
    setLoading(true);
    setError(null);
    setResults(null);
    setParams(form);
    try {
      const res = await TrainApi.search(form);
      setResults(res.data.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (params.get('sourceCode') && params.get('destinationCode') && params.get('journeyDate')) {
      runSearch();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const displayedResults = useMemo(() => {
    if (!results) return [];
    let filtered = results;
    if (classFilter !== 'ALL') {
      filtered = filtered.filter((t) => t.classAvailability.some((c) => c.seatClass === classFilter));
    }
    const sorted = [...filtered];
    if (sortBy === 'departure') sorted.sort((a, b) => a.departureTime.localeCompare(b.departureTime));
    if (sortBy === 'duration') sorted.sort((a, b) => a.durationMinutes - b.durationMinutes);
    if (sortBy === 'fare') sorted.sort((a, b) => Math.min(...a.classAvailability.map((c) => c.fare)) - Math.min(...b.classAvailability.map((c) => c.fare)));
    return sorted;
  }, [results, sortBy, classFilter]);

  const formatDuration = (mins) => `${Math.floor(mins / 60)}h ${mins % 60}m`;

  return (
    <div className="container page">
      <PageHeader title="Search Trains" icon="search" />

      <form onSubmit={runSearch} className="card">
        <div className="toolbar" style={{ marginBottom: 0 }}>
          <div className="field-inline">
            <label htmlFor="from">From</label>
            <select id="from" className="control select" value={form.sourceCode} onChange={(e) => setForm((f) => ({ ...f, sourceCode: e.target.value }))}>
              <option value="">Select station</option>
              {stations.map((s) => <option key={s.id} value={s.code}>{s.name} ({s.code})</option>)}
            </select>
          </div>
          <div className="field-inline">
            <label htmlFor="to">To</label>
            <select id="to" className="control select" value={form.destinationCode} onChange={(e) => setForm((f) => ({ ...f, destinationCode: e.target.value }))}>
              <option value="">Select station</option>
              {stations.map((s) => <option key={s.id} value={s.code}>{s.name} ({s.code})</option>)}
            </select>
          </div>
          <div className="field-inline">
            <label htmlFor="journey-date">Journey date</label>
            <input id="journey-date" className="control" type="date" min={new Date().toISOString().slice(0, 10)} value={form.journeyDate} onChange={(e) => setForm((f) => ({ ...f, journeyDate: e.target.value }))} />
          </div>
          <button type="submit" className="btn btn-primary">Search</button>
        </div>
      </form>

      {loading && <Loader label="Searching for trains..." />}
      {error && <ErrorState message={error} onRetry={runSearch} />}

      {results && !loading && !error && (
        results.length === 0 ? (
          <EmptyState title="No trains found" message="Try a different date or route." />
        ) : (
          <>
            <div className="toolbar">
              <div className="flex gap-sm">
                <select className="control select" value={sortBy} onChange={(e) => setSortBy(e.target.value)}>
                  <option value="departure">Sort: Earliest departure</option>
                  <option value="duration">Sort: Shortest duration</option>
                  <option value="fare">Sort: Lowest fare</option>
                </select>
                <select className="control select" value={classFilter} onChange={(e) => setClassFilter(e.target.value)}>
                  <option value="ALL">All classes</option>
                  {Object.entries(CLASS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </div>
              <span className="spacer" />
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setSortBy('departure'); setClassFilter('ALL'); }}>Reset filters</button>
            </div>

            <div className="flex-col gap-md">
              {displayedResults.map((t) => (
                <div key={t.trainId} className="card card-interactive">
                  <div className="flex-between">
                    <div>
                      <h3 style={{ marginBottom: 2 }}>{t.trainName} <span className="text-muted text-xs">#{t.trainNumber}</span></h3>
                      <span className="badge badge-neutral">{t.trainType}</span>
                    </div>
                    <div className="flex gap-lg text-center">
                      <div>
                        <strong>{t.departureTime}</strong>
                        <div className="text-muted text-xs">{t.sourceStationCode}</div>
                      </div>
                      <div className="text-muted text-xs" style={{ alignSelf: 'center' }}>{formatDuration(t.durationMinutes)} →</div>
                      <div>
                        <strong>{t.arrivalTime}</strong>
                        <div className="text-muted text-xs">{t.destinationStationCode}</div>
                      </div>
                    </div>
                  </div>
                  <div className="flex gap-sm" style={{ marginTop: 16, flexWrap: 'wrap' }}>
                    {t.classAvailability
                      .filter((c) => classFilter === 'ALL' || c.seatClass === classFilter)
                      .map((c) => {
                        const avail = availabilityOf(c);
                        return (
                          <div key={c.coachId} className="surface-soft text-center" style={{ padding: '8px 14px', minWidth: 118 }}>
                            <div className="strong">{c.seatClassLabel}</div>
                            <div className="text-muted text-xs">{money(c.fare)}</div>
                            <div className={`${avail.tone === 'green' ? 'text-green' : 'text-orange'} text-xs strong`}>
                              {avail.label}
                            </div>
                          </div>
                        );
                      })}
                  </div>
                  <div className="flex-between" style={{ marginTop: 16 }}>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => navigate(`/trains/${t.trainId}?journeyDate=${form.journeyDate}&sourceCode=${t.sourceStationCode}&destinationCode=${t.destinationStationCode}`)}>View Details</button>
                    <button type="button"
                      className="btn btn-primary"
                      onClick={() => navigate(`/book/${t.trainId}`, {
                        state: {
                          sourceStationCode: t.sourceStationCode,
                          destinationStationCode: t.destinationStationCode,
                          sourceStationId: t.sourceStationId,
                          destinationStationId: t.destinationStationId,
                          journeyDate: form.journeyDate,
                        },
                      })}
                    >
                      Book Now
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </>
        )
      )}
    </div>
  );
}
