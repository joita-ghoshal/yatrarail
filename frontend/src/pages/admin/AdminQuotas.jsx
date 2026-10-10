import { useEffect, useMemo, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { useToast } from '../../context/ToastContext';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import PageHeader from '../../components/PageHeader';

const SEAT_CLASSES = ['FIRST_AC', 'SECOND_AC', 'THIRD_AC', 'SLEEPER', 'CHAIR_CAR', 'SECOND_SEATING'];

/**
 * The slices an administrator reserves out of a class, in the order they are presented to the
 * passenger. `general` is not editable: it is whatever is left over, which is what makes an
 * over-committed split impossible to save.
 */
const RESERVED_SLICES = [
  { key: 'tatkalSeats', quota: 'TATKAL', label: 'Tatkal (simulated)' },
  { key: 'seniorSeats', quota: 'SENIOR', label: 'Senior Citizen' },
  { key: 'ladiesSeats', quota: 'LADIES', label: 'Ladies' },
  { key: 'divyangSeats', quota: 'DIVYANG', label: 'Divyang' },
  { key: 'staffSeats', quota: 'STAFF', label: 'Staff' },
];

const emptyForm = (trainId, seatClass) => ({
  trainId,
  seatClass,
  tatkalSeats: 0,
  seniorSeats: 0,
  ladiesSeats: 0,
  divyangSeats: 0,
  staffSeats: 0,
  tatkalWindowDays: 1,
  active: true,
});

export default function AdminQuotas() {
  const { showToast } = useToast();
  const [configs, setConfigs] = useState(null);
  const [trains, setTrains] = useState([]);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState(null);
  const [fieldError, setFieldError] = useState(null);

  const load = () => {
    setError(null);
    Promise.all([AdminApi.quotaConfigs(), AdminApi.trains()])
      .then(([q, t]) => {
        setConfigs(q.data.data);
        setTrains(t.data.data);
      })
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  // Editing an existing row starts from what is saved; a new one starts empty.
  const startEdit = (config) => {
    setFieldError(null);
    if (config) {
      setForm({
        trainId: String(config.trainId),
        seatClass: config.seatClass,
        tatkalSeats: config.tatkalSeats,
        seniorSeats: config.seniorSeats,
        ladiesSeats: config.ladiesSeats,
        divyangSeats: config.divyangSeats,
        staffSeats: config.staffSeats,
        tatkalWindowDays: config.tatkalWindowDays,
        active: config.active,
      });
    } else {
      setForm(emptyForm('', 'THIRD_AC'));
    }
  };

  const reservedTotal = useMemo(() => {
    if (!form) return 0;
    return RESERVED_SLICES.reduce((sum, s) => sum + (Number(form[s.key]) || 0), 0);
  }, [form]);

  // Mirrors the backend's ceiling so the admin sees the problem before pressing Save.
  const capacityOf = useMemo(() => {
    if (!configs || !form) return null;
    const match = configs.find((c) => String(c.trainId) === String(form.trainId) && c.seatClass === form.seatClass);
    return match ? match.totalCapacity : null;
  }, [configs, form]);

  const overCapacity = capacityOf !== null && reservedTotal > capacityOf;
  const tatkalWithoutWindow = Number(form?.tatkalSeats) > 0 && Number(form?.tatkalWindowDays) < 1;

  const save = async (e) => {
    e.preventDefault();
    setFieldError(null);
    if (overCapacity) {
      setFieldError(`The reserved slices total ${reservedTotal} seats but this class only has ${capacityOf}. Reduce a slice, or close a coach to free capacity.`);
      return;
    }
    if (tatkalWithoutWindow) {
      setFieldError('Tatkal seats need a departure window of at least 1 day, otherwise the quota could never open.');
      return;
    }
    setSaving(true);
    try {
      await AdminApi.upsertQuotaConfig({
        trainId: Number(form.trainId),
        seatClass: form.seatClass,
        tatkalSeats: Number(form.tatkalSeats) || 0,
        seniorSeats: Number(form.seniorSeats) || 0,
        ladiesSeats: Number(form.ladiesSeats) || 0,
        divyangSeats: Number(form.divyangSeats) || 0,
        staffSeats: Number(form.staffSeats) || 0,
        tatkalWindowDays: Number(form.tatkalWindowDays) || 0,
        active: Boolean(form.active),
      });
      showToast('Quota split saved', 'success');
      load();
    } catch (err) {
      setFieldError(extractErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!configs) return <Loader label="Loading quota configuration..." />;

  const trainName = (id) => {
    const t = trains.find((x) => x.id === id);
    return t ? `${t.trainNumber} — ${t.name}` : `Train ${id}`;
  };

  return (
    <div>
      <PageHeader
        title="Booking Quotas"
        icon="scale"
        subtitle={(
          <>A quota is a slice of one class&apos;s seats on one journey date. The reserved slices below are
          held back for the passengers who are entitled to them; the general quota is automatically whatever
          is left over, so it is never configured and a split can never add up to more seats than the train
          owns. Quota concessions (how much off each quota earns) are managed under Fare Rules.</>
        )}
      />

      <h2 className="section-title" style={{ marginTop: 'var(--space-7)' }}>Configured splits</h2>
      <div className="table-wrap" style={{ marginBottom: 24 }}>
        <table><caption className="sr-only">Seat quota split by train and class</caption>
          <thead>
            <tr>
              <th scope="col">Train</th>
              <th scope="col">Class</th>
              <th scope="col" className="num">Capacity</th>
              <th scope="col" className="num">Tatkal</th>
              <th scope="col" className="num">Senior</th>
              <th scope="col" className="num">Ladies</th>
              <th scope="col" className="num">Divyang</th>
              <th scope="col" className="num">Staff</th>
              <th scope="col" className="num">General</th>
              <th scope="col" className="num">Window</th>
              <th scope="col">Status</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {configs.length === 0 && (
              <tr><td colSpan="12" className="text-muted table-empty">No quota configuration yet.</td></tr>
            )}
            {configs.map((c) => (
              <tr key={c.id}>
                <td>{trainName(c.trainId)}</td>
                <td>{c.seatClassLabel || c.seatClass}</td>
                <td className="num">{c.totalCapacity}</td>
                <td className="num">{c.tatkalSeats}</td>
                <td className="num">{c.seniorSeats}</td>
                <td className="num">{c.ladiesSeats}</td>
                <td className="num">{c.divyangSeats}</td>
                <td className="num">{c.staffSeats}</td>
                <td className="num"><strong>{c.generalSeats}</strong></td>
                <td className="num">{c.tatkalSeats > 0 ? `${c.tatkalWindowDays}d` : '—'}</td>
                <td>
                  <span className={`badge ${c.active ? 'badge-success' : 'badge-neutral'}`}>
                    {c.active ? 'Active' : 'Disabled'}
                  </span>
                </td>
                <td><button type="button" className="btn btn-ghost btn-sm" onClick={() => startEdit(c)}>Edit</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ maxWidth: 520 }}>
        <h3>{configs.some((c) => String(c.trainId) === String(form?.trainId) && c.seatClass === form?.seatClass) ? 'Edit quota split' : 'Add quota split'}</h3>
        {form === null && (
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => startEdit(null)}>
            Configure a new train / class
          </button>
        )}
        {form !== null && (
          <form onSubmit={save}>
            <div className="field">
              <label htmlFor="train">Train</label>
              <select id="train" value={form.trainId} onChange={(e) => setForm((f) => ({ ...f, trainId: e.target.value, seatClass: 'THIRD_AC' }))} required>
                <option value="">Select a train</option>
                {trains.map((t) => <option key={t.id} value={t.id}>{t.trainNumber} — {t.name}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="seat-class">Seat class</label>
              <select id="seat-class" value={form.seatClass} onChange={(e) => setForm((f) => ({ ...f, seatClass: e.target.value }))} required>
                {SEAT_CLASSES.map((c) => <option key={c}>{c}</option>)}
              </select>
              <div className="field-hint">
                {capacityOf === null
                  ? 'This class has no active coach on that train yet, so there is no capacity to divide.'
                  : `This class owns ${capacityOf} seats across its active coaches.`}
              </div>
            </div>

            {RESERVED_SLICES.map((slice) => (
              <div className="field" key={slice.key}>
                <label htmlFor="seats">{slice.label} seats</label>
                <input id="seats"
                  type="number"
                  min="0"
                  value={form[slice.key]}
                  onChange={(e) => setForm((f) => ({ ...f, [slice.key]: e.target.value }))}
                  required
                />
              </div>
            ))}

            <div className="field">
              <label htmlFor="tatkal-departure-window-days">Tatkal departure window (days)</label>
              <input id="tatkal-departure-window-days"
                type="number"
                min="0"
                max="7"
                value={form.tatkalWindowDays}
                onChange={(e) => setForm((f) => ({ ...f, tatkalWindowDays: e.target.value }))}
                required
              />
              <div className="field-hint">
                How many days before departure the Tatkal quota is open. 0 switches Tatkal off; it must be
                at least 1 while Tatkal seats are reserved.
              </div>
            </div>

            <div className="field">
              <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input
                  type="checkbox"
                  style={{ width: 'auto' }}
                  checked={Boolean(form.active)}
                  onChange={(e) => setForm((f) => ({ ...f, active: e.target.checked }))}
                />
                Active
              </label>
              <div className="field-hint">
                A disabled split reserves nothing: every seat in the class falls back to general.
              </div>
            </div>

            <div className={`field-hint${overCapacity ? ' field-error' : ''}`} style={{ marginBottom: 14 }}>
              Reserved {reservedTotal}
              {capacityOf !== null ? ` of ${capacityOf}` : ''} &rarr; general gets{' '}
              {capacityOf === null ? '—' : Math.max(0, capacityOf - reservedTotal)}
            </div>

            {fieldError && <p className="field-error" style={{ marginBottom: 14 }}>{fieldError}</p>}

            <div className="btn-row">
              <button type="submit" className="btn btn-primary" disabled={saving || !form.trainId}>
                {saving ? 'Saving...' : 'Save Quota Split'}
              </button>
              <button type="button" className="btn btn-ghost" onClick={() => startEdit(null)}>New</button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
