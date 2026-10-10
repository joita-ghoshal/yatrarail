import { useCallback, useEffect, useState } from 'react';
import { StaffApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import PageHeader from '../../components/PageHeader';
import StatCard from '../../components/StatCard';

/**
 * The staff dashboard: what is running today, who is on it, and what is outstanding.
 * <p>
 * Every figure is scoped to a single journey date rather than a running total, because the question
 * a conductor opens this page to answer is "how is <em>today</em> going", not "how has the system
 * done since launch".
 */
export default function StaffDashboard() {
  const today = new Date().toISOString().slice(0, 10);
  const [journeyDate, setJourneyDate] = useState(today);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  const load = useCallback((date) => {
    setData(null);
    setError(null);
    StaffApi.dashboard(date)
      .then((res) => setData(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, []);

  useEffect(() => { load(journeyDate); }, [journeyDate, load]);

  return (
    <div>
      <PageHeader
        title="Staff Dashboard"
        subtitle="Operations for one journey day across every active train."
        icon="dashboard"
        actions={
          <label className="field-inline" htmlFor="journey-date">
            Journey date
            <input
              id="journey-date"
              className="control"
              type="date"
              value={journeyDate}
              onChange={(e) => setJourneyDate(e.target.value)}
            />
          </label>
        }
      />

      {error && <ErrorState message={error} onRetry={() => load(journeyDate)} />}
      {!error && !data && <Loader label="Loading dashboard..." />}

      {data && (
        <>
          <div className="stat-grid" style={{ marginBottom: 'var(--space-5)' }}>
            <StatCard label="Active trains" value={data.activeTrains} icon="train" tone="accent" />
            <StatCard label="Bookings" value={data.totalBookings} icon="ticket" tone="info" />
            <StatCard label="Passengers" value={data.totalPassengers} icon="passengers" tone="success" />
            <StatCard label="Open incidents" value={data.openIncidents} icon="alert" tone={data.openIncidents > 0 ? 'danger' : 'neutral'} />
          </div>

          <h2 className="section-title">Bookings by state</h2>
          <div className="stat-grid" style={{ marginBottom: 'var(--space-6)' }}>
            <StatCard label="Confirmed" value={data.confirmedBookings} icon="check-circle" tone="success" />
            <StatCard label="RAC" value={data.racBookings} icon="ticket" tone="accent" />
            <StatCard label="Waitlisted" value={data.waitlistedBookings} icon="clock" tone="warning" />
            <StatCard label="Open assistance" value={data.openAssistanceRequests} icon="help" tone={data.openAssistanceRequests > 0 ? 'warning' : 'neutral'} />
          </div>

          <h2 className="section-title">Gate check-in</h2>
          <section className="card">
            {data.totalPassengers === 0 ? (
              <p className="text-muted" style={{ margin: 0 }}>No passengers booked for {data.journeyDate}.</p>
            ) : (
              <>
                <Progress label="Boarded" value={data.boardedPassengers} total={data.totalPassengers} tone="success" />
                <Progress label="Not boarded" value={data.notBoardedPassengers} total={data.totalPassengers} tone="muted" />
                <Progress label="No show" value={data.noShowPassengers} total={data.totalPassengers} tone="warning" />
                <p className="field-hint" style={{ marginTop: 'var(--space-3)', marginBottom: 0 }}>
                  A no-show is recorded for the operational report only. It never refunds or cancels a
                  ticket — refunds stay an explicit action through the cancellation flow.
                </p>
              </>
            )}
          </section>
        </>
      )}
    </div>
  );
}

/** A labelled proportion bar built from the shared progress classes. */
function Progress({ label, value, total, tone }) {
  const pct = total === 0 ? 0 : Math.round((value / total) * 100);
  return (
    <div className="progress-row">
      <div className="progress-head">
        <span>{label}</span>
        <span className="text-muted">{value} ({pct}%)</span>
      </div>
      <div
        className="progress-track"
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={`${label}: ${pct}%`}
      >
        <div className={`progress-bar progress-bar-${tone}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
