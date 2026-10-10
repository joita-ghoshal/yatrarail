import { useCallback, useEffect, useState } from 'react';
import { StaffApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import EmptyState from '../../components/EmptyState';
import StatusBadge from '../../components/StatusBadge';
import PageHeader from '../../components/PageHeader';

const TYPES = ['DELAY', 'CANCELLATION', 'DIVERSION', 'BREAKDOWN', 'RESCHEDULE', 'OTHER'];
const SEVERITIES = ['INFO', 'MINOR', 'MAJOR', 'CRITICAL'];
const STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'];

/**
 * Delay and incident reporting for staff.
 * <p>
 * The list is the working surface and the form is deliberately collapsed by default: most visits are
 * "acknowledge the delay somebody else reported", not "raise something new". One open incident per
 * train and date is allowed, so a second person about the same delay gets a clear message instead of
 * a duplicate row that two staff then resolve separately.
 */
export default function StaffIncidents() {
  const [trains, setTrains] = useState(null);
  const [incidents, setIncidents] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [form, setForm] = useState({ trainId: '', type: 'DELAY', severity: 'MAJOR', delayMinutes: '', description: '' });
  const [formError, setFormError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    StaffApi.trains()
      .then((res) => setTrains(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, []);

  const [loadingStatus, setLoadingStatus] = useState(null);

  const load = useCallback((status) => {
    setLoadingStatus(status);
    setError(null);
    StaffApi.incidents(status ? { status } : {})
      .then((res) => setIncidents(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)))
      .finally(() => setLoadingStatus(null));
  }, []);

  useEffect(() => { load(statusFilter); }, [statusFilter, load]);

  const submit = async (e) => {
    e.preventDefault();
    setFormError(null);
    setSaving(true);
    try {
      const payload = {
        trainId: Number(form.trainId),
        type: form.type,
        severity: form.severity,
        description: form.description.trim(),
      };
      if (form.delayMinutes !== '') payload.delayMinutes = Number(form.delayMinutes);
      await StaffApi.reportIncident(payload);
      setForm({ trainId: '', type: 'DELAY', severity: 'MAJOR', delayMinutes: '', description: '' });
      setNotice('Incident reported. Every staff member has been notified.');
      load(statusFilter);
    } catch (err) {
      setFormError(extractErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const setStatus = async (id, status) => {
    setError(null);
    try {
      await StaffApi.updateIncident(id, status);
      setNotice(`Incident marked ${status.toLowerCase()}.`);
      load(statusFilter);
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  };

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  return (
    <div>
      <PageHeader
        title="Incidents"
        subtitle="Delays, cancellations, diversions and breakdowns on active services."
        icon="alert"
      />

      {notice && <div className="notice notice-good" style={{ marginBottom: 16 }}>{notice}</div>}

      <details className="card" style={{ marginBottom: 20 }}>
        <summary style={{ cursor: 'pointer', fontWeight: 600 }}>Report an incident</summary>
        <form onSubmit={submit} style={{ marginTop: 16 }}>
          {formError && <ErrorState message={formError} onRetry={() => setFormError(null)} />}
          <div className="grid grid-3">
            <div className="field">
              <label htmlFor="train">Train</label>
              <select id="train" value={form.trainId} onChange={set('trainId')} required>
                <option value="">Select a train</option>
                {(trains || []).map((t) => (
                  <option key={t.id} value={t.id}>{t.name} (#{t.trainNumber})</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="type">Type</label>
              <select id="type" value={form.type} onChange={set('type')}>
                {TYPES.map((t) => <option key={t} value={t}>{t.replace(/_/g, ' ').toLowerCase()}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="severity">Severity</label>
              <select id="severity" value={form.severity} onChange={set('severity')}>
                {SEVERITIES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
              </select>
            </div>
          </div>
          <div className="field" style={{ maxWidth: 200 }}>
            <label htmlFor="delay-in-minutes">Delay in minutes</label>
            <input id="delay-in-minutes" type="number" min="0" max="1440" value={form.delayMinutes} onChange={set('delayMinutes')} placeholder="e.g. 45" />
            <div className="field-hint">Only relevant for delays and reschedules.</div>
          </div>
          <div className="field">
            <label htmlFor="what-happened">What happened</label>
            <textarea id="what-happened"
              rows="3"
              value={form.description}
              onChange={set('description')}
              placeholder="Enough detail that another station's staff can act on it without phoning you."
              required
            />
          </div>
          <button className="btn btn-primary" type="submit" disabled={saving}>
            {saving ? 'Reporting...' : 'Report incident'}
          </button>
        </form>
      </details>

      <div className="toolbar">
        <label className="field-inline" htmlFor="filter-by-status">
          Filter by status
          <select id="filter-by-status" className="control select" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
            <option value="">All incidents</option>
            {STATUSES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
        </label>
      </div>

      {error && <ErrorState message={error} onRetry={() => load(statusFilter)} />}
      {loadingStatus && <Loader label="Loading incidents..." />}

      {incidents && incidents.length === 0 && (
        <EmptyState title="No incidents reported" message="Nothing has been reported for this filter." />
      )}

      {incidents && incidents.length > 0 && (
        <div className="table-wrap">
          <table><caption className="sr-only">Reported incidents</caption>
            <thead>
              <tr>
                <th scope="col">Reported</th>
                <th scope="col">Train</th>
                <th scope="col">Type</th>
                <th scope="col">Severity</th>
                <th scope="col" className="num">Delay</th>
                <th scope="col">Detail</th>
                <th scope="col">Status</th>
                <th scope="col">Update</th>
              </tr>
            </thead>
            <tbody>
              {incidents.map((i) => (
                <tr key={i.id}>
                  <td className="text-muted">{formatWhen(i.reportedAt)}</td>
                  <td>
                    <div>{i.trainName}</div>
                    <div className="text-muted" style={{ fontSize: '0.78rem' }}>{i.journeyDate}</div>
                  </td>
                  <td>{i.type.replace(/_/g, ' ').toLowerCase()}</td>
                  <td><SeverityBadge severity={i.severity} /></td>
                  <td className="num">{i.delayMinutes != null ? `${i.delayMinutes} min` : '—'}</td>
                  <td style={{ maxWidth: 320 }}>
                    <div>{i.description}</div>
                    <div className="text-muted" style={{ fontSize: '0.74rem', marginTop: 2 }}>by {i.reportedByName}</div>
                  </td>
                  <td><StatusBadge status={i.status} /></td>
                  <td>
                    {i.status === 'RESOLVED' ? (
                      <span className="text-muted" style={{ fontSize: '0.8rem' }}>Closed</span>
                    ) : (
                      <div className="flex gap-sm">
                        {i.status !== 'ACKNOWLEDGED' && (
                          <button type="button" className="btn btn-sm btn-ghost" onClick={() => setStatus(i.id, 'ACKNOWLEDGED')}>
                            Acknowledge
                          </button>
                        )}
                        <button type="button" className="btn btn-sm btn-primary" onClick={() => setStatus(i.id, 'RESOLVED')}>
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

/** Severity is the one field staff mis-read under pressure, so it gets an explicit colour. */
function SeverityBadge({ severity }) {
  const cls = { INFO: 'badge-neutral', MINOR: 'badge-rac', MAJOR: 'badge-cancelled', CRITICAL: 'badge-failed' }[severity];
  return <span className={`badge ${cls}`}>{severity?.toLowerCase()}</span>;
}

function formatWhen(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}
