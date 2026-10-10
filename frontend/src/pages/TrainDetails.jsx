import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { TrainApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import { availabilityOf, money } from '../utils/format';
import Loader from '../components/Loader';
import ErrorState from '../components/ErrorState';
import PageHeader from '../components/PageHeader';

export default function TrainDetails() {
  const { trainId } = useParams();
  const [params] = useSearchParams();
  const journeyDate = params.get('journeyDate');
  const navigate = useNavigate();

  const [details, setDetails] = useState(null);
  const [error, setError] = useState(null);
  // `null` means "the whole train"; picking a pair of stops narrows fares and
  // availability to that leg, which is what the passenger is actually buying.
  const [segment, setSegment] = useState({ from: null, to: null });

  const load = (nextSegment = segment) => {
    setError(null);
    TrainApi.details(trainId, {
      journeyDate,
      sourceStationId: nextSegment.from || undefined,
      destinationStationId: nextSegment.to || undefined,
    })
      .then((res) => setDetails(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(() => { load({ from: null, to: null }); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [trainId, journeyDate]);

  useEffect(() => {
    if (details?.route) {
      const fromCode = params.get('sourceCode') || details.route[0]?.stationCode;
      const toCode = params.get('destinationCode') || details.route[details.route.length - 1]?.stationCode;
      const from = details.route.find((r) => r.stationCode === fromCode);
      const to = details.route.find((r) => r.stationCode === toCode);
      if (from && to && (from.stationId !== segment.from || to.stationId !== segment.to)) {
        setSegment({ from: from.stationId, to: to.stationId });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [details?.route]);

  const options = useMemo(() => details?.route || [], [details]);

  const onSegmentChange = (which, stationId) => {
    const next = { ...segment, [which]: stationId };
    if (which === 'from' && next.to != null) {
      const fromSeq = options.find((r) => r.stationId === next.from)?.sequenceNumber ?? 0;
      const toSeq = options.find((r) => r.stationId === next.to)?.sequenceNumber ?? 0;
      if (fromSeq >= toSeq) next.to = options.find((r) => r.sequenceNumber === fromSeq + 1)?.stationId ?? null;
    }
    if (which === 'to' && next.from != null) {
      const fromSeq = options.find((r) => r.stationId === next.from)?.sequenceNumber ?? 0;
      const toSeq = options.find((r) => r.stationId === next.to)?.sequenceNumber ?? 0;
      if (toSeq <= fromSeq) next.from = options.find((r) => r.sequenceNumber === toSeq - 1)?.stationId ?? null;
    }
    setSegment(next);
    load(next);
  };

  if (error) return <div className="container page"><ErrorState message={error} onRetry={() => load()} /></div>;
  if (!details) return <Loader label="Loading train details..." />;

  const selectedFrom = options.find((r) => r.stationId === segment.from);
  const selectedTo = options.find((r) => r.stationId === segment.to);
  const isSegment = Boolean(selectedFrom && selectedTo);
  const bookState = isSegment
    ? { sourceStationCode: selectedFrom.stationCode, destinationStationCode: selectedTo.stationCode, journeyDate }
    : { journeyDate };

  return (
    <div className="container page">
      <PageHeader
        title={details.trainName}
        subtitle={`Train #${details.trainNumber}`}
        icon="train"
        actions={<span className="badge badge-neutral">{details.trainType}</span>}
      />

      <div className="card" style={{ marginBottom: 24 }}>
        <div className="text-muted text-xs" style={{ marginBottom: 12 }}>
          Runs on: {details.runsOn}
        </div>
        <div className="flex gap-lg">
          <div><strong>{details.departureTime}</strong><div className="text-muted">Departure</div></div>
          <div className="text-muted" style={{ alignSelf: 'center' }}>{Math.floor(details.durationMinutes / 60)}h {details.durationMinutes % 60}m →</div>
          <div><strong>{details.arrivalTime}</strong><div className="text-muted">Arrival</div></div>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 24 }}>
        <h3>Choose your travel segment</h3>
        <p className="field-hint" style={{ marginTop: 0 }}>Fares and seat availability are calculated for the exact part of the route you travel.</p>
        <div className="grid grid-2" style={{ alignItems: 'end' }}>
          <div className="field mb-0">
            <label htmlFor="from">From</label>
            <select id="from" value={segment.from ?? ''} onChange={(e) => onSegmentChange('from', e.target.value ? Number(e.target.value) : null)}>
              {options.map((r) => <option key={r.stationId} value={r.stationId}>{r.stationName} ({r.stationCode})</option>)}
            </select>
          </div>
          <div className="field mb-0">
            <label htmlFor="to">To</label>
            <select id="to" value={segment.to ?? ''} onChange={(e) => onSegmentChange('to', e.target.value ? Number(e.target.value) : null)}>
              {options.map((r) => <option key={r.stationId} value={r.stationId}>{r.stationName} ({r.stationCode})</option>)}
            </select>
          </div>
        </div>
        {isSegment && (
          <div className={`notice ${details.distanceKm > 0 ? 'notice-info' : 'notice-warn'}`} style={{ marginTop: 14 }}>
            {details.distanceKm > 0
              ? <div><strong>{details.distanceKm} km segment</strong>{selectedFrom.stationName} → {selectedTo.stationName}. Prices below are for this leg only.</div>
              : <div><strong>Invalid segment</strong>This train does not travel between the two selected stations in that order.</div>}
          </div>
        )}
      </div>

      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <div className="card">
          <h3>Route</h3>
          <div className="flex-col">
            {details.route.map((rs, idx) => (
              <div key={idx} className="flex gap-md" style={{ paddingBottom: idx === details.route.length - 1 ? 0 : 18, position: 'relative' }}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                  <div style={{ width: 10, height: 10, borderRadius: '50%', background: idx === 0 || idx === details.route.length - 1 ? 'var(--yr-orange)' : 'var(--border)' }} />
                  {idx !== details.route.length - 1 && <div style={{ width: 2, flex: 1, background: 'var(--border)', minHeight: 30 }} />}
                </div>
                <div>
                  <strong>{rs.stationName} ({rs.stationCode})</strong>
                  <div className="text-muted text-xs">
                    {rs.arrivalTime ? `Arr ${rs.arrivalTime}` : 'Source'} {rs.departureTime ? `· Dep ${rs.departureTime}` : ''} · {rs.distanceFromSourceKm} km {rs.haltMinutes > 0 && `· Halt ${rs.haltMinutes}m`}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="card">
          <h3>Classes & Availability</h3>
          <div className="flex-col gap-sm">
            {details.classes.map((c) => {
              const avail = availabilityOf(c);
              return (
                <div key={c.coachId} className="surface-soft flex-between" style={{ padding: 14 }}>
                  <div>
                    <strong>{c.seatClassLabel}</strong>
                    <div className="text-muted text-xs">{money(c.fare)} per passenger</div>
                    {c.status === 'RAC_AVAILABLE' && <div className="field-hint">{c.racSeats} RAC berth(s) open</div>}
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <div className={`${avail.tone === 'green' ? 'text-green' : 'text-orange'} text-xs strong`}>
                      {avail.label}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
          <button type="button"
            className="btn btn-primary btn-block"
            style={{ marginTop: 18 }}
            disabled={!isSegment || details.distanceKm <= 0}
            onClick={() => navigate(`/book/${details.trainId}`, { state: bookState })}
          >
            {isSegment ? 'Book This Segment' : 'Select a valid segment'}
          </button>
        </div>
      </div>
    </div>
  );
}
