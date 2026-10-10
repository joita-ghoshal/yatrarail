import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { useToast } from '../../context/ToastContext';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import Modal from '../../components/Modal';
import PageHeader from '../../components/PageHeader';

const TRAIN_TYPES = ['RAJDHANI', 'SHATABDI', 'EXPRESS', 'SUPERFAST', 'PASSENGER', 'DURONTO'];
const SEAT_CLASSES = ['FIRST_AC', 'SECOND_AC', 'THIRD_AC', 'SLEEPER', 'CHAIR_CAR', 'SECOND_SEATING'];
const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

function emptyTrainForm() {
  return { trainNumber: '', name: '', type: 'EXPRESS', sourceStationId: '', destinationStationId: '', departureTime: '09:00', arrivalTime: '18:00', durationMinutes: 540, runsOn: DAYS.join(','), route: [] };
}

function emptyCoachForm() {
  return { coachCode: '', seatClass: 'THIRD_AC', totalSeats: 64, baseFare: 500, racCapacity: 0 };
}

/** Route stops are sent with their position renumbered from 1, so gaps from removed rows never persist. */
function orderedStops(stops) {
  return stops
    .filter((s) => s.stationId)
    .map((s, i) => ({
      stationId: Number(s.stationId),
      sequenceNumber: i + 1,
      distanceFromSourceKm: Number(s.distanceFromSourceKm) || 0,
      haltMinutes: Number(s.haltMinutes) || 0,
    }));
}

export default function AdminTrains() {
  const { showToast } = useToast();
  const [trains, setTrains] = useState(null);
  const [stations, setStations] = useState([]);
  const [error, setError] = useState(null);

  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState(emptyTrainForm());
  const [saving, setSaving] = useState(false);

  const [coachModalTrain, setCoachModalTrain] = useState(null);
  const [coaches, setCoaches] = useState([]);
  const [coachForm, setCoachForm] = useState(emptyCoachForm());
  const [editingCoachId, setEditingCoachId] = useState(null);
  const [coachSaving, setCoachSaving] = useState(false);

  const [routeModalTrain, setRouteModalTrain] = useState(null);
  const [routeStops, setRouteStops] = useState([]);
  const [routeSaving, setRouteSaving] = useState(false);

  const load = () => {
    setError(null);
    Promise.all([AdminApi.trains(), AdminApi.stations()])
      .then(([t, s]) => { setTrains(t.data.data); setStations(s.data.data); })
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  const openCreate = () => { setForm(emptyTrainForm()); setModalOpen(true); };

  const toggleDay = (day) => {
    const days = form.runsOn ? form.runsOn.split(',').filter(Boolean) : [];
    const next = days.includes(day) ? days.filter((d) => d !== day) : [...days, day];
    setForm((f) => ({ ...f, runsOn: next.join(',') }));
  };

  const addRouteStop = (setter) => setter((stops) => [...stops, { stationId: '', sequenceNumber: stops.length + 1, distanceFromSourceKm: 0, haltMinutes: 5 }]);
  const updateRouteStop = (setter, idx, field, value) => setter((stops) => stops.map((r, i) => (i === idx ? { ...r, [field]: value } : r)));
  const removeRouteStop = (setter, idx) => setter((stops) => stops.filter((_, i) => i !== idx));

  const addFormRouteStop = () => setForm((f) => ({ ...f, route: [...f.route, { stationId: '', distanceFromSourceKm: 0, haltMinutes: 5 }] }));
  const updateFormRouteStop = (idx, field, value) => setForm((f) => ({ ...f, route: f.route.map((r, i) => (i === idx ? { ...r, [field]: value } : r)) }));
  const removeFormRouteStop = (idx) => setForm((f) => ({ ...f, route: f.route.filter((_, i) => i !== idx) }));

  const saveTrain = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      await AdminApi.createTrain({
        ...form,
        sourceStationId: Number(form.sourceStationId),
        destinationStationId: Number(form.destinationStationId),
        route: orderedStops(form.route),
      });
      showToast('Train created', 'success');
      setModalOpen(false);
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (t) => {
    try { await AdminApi.setTrainActive(t.id, !t.active); load(); } catch (err) { showToast(extractErrorMessage(err), 'error'); }
  };

  const removeTrain = async (t) => {
    if (!window.confirm(`Delete train ${t.name}? This cannot be undone.`)) return;
    try { await AdminApi.deleteTrain(t.id); showToast('Train deleted', 'success'); load(); }
    catch (err) { showToast(extractErrorMessage(err), 'error'); }
  };

  const openCoaches = async (t) => {
    setCoachModalTrain(t);
    setEditingCoachId(null);
    setCoachForm(emptyCoachForm());
    try {
      const res = await AdminApi.coaches(t.id);
      setCoaches(res.data.data);
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    }
  };

  const reloadCoaches = async () => {
    const res = await AdminApi.coaches(coachModalTrain.id);
    setCoaches(res.data.data);
  };

  const submitCoach = async (e) => {
    e.preventDefault();
    setCoachSaving(true);
    try {
      if (editingCoachId) {
        await AdminApi.updateCoach(editingCoachId, { ...coachForm, totalSeats: Number(coachForm.totalSeats), baseFare: Number(coachForm.baseFare), racCapacity: Number(coachForm.racCapacity) || 0 });
        showToast('Coach updated', 'success');
      } else {
        await AdminApi.addCoach(coachModalTrain.id, { ...coachForm, totalSeats: Number(coachForm.totalSeats), baseFare: Number(coachForm.baseFare), racCapacity: Number(coachForm.racCapacity) || 0 });
        showToast('Coach added', 'success');
      }
      setEditingCoachId(null);
      setCoachForm(emptyCoachForm());
      await reloadCoaches();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setCoachSaving(false);
    }
  };

  const editCoach = (c) => {
    setEditingCoachId(c.id);
    setCoachForm({ coachCode: c.coachCode, seatClass: c.seatClass, totalSeats: c.totalSeats, baseFare: c.baseFare, racCapacity: c.racCapacity ?? 0 });
  };

  const toggleCoachActive = async (c) => {
    try {
      await AdminApi.setCoachActive(c.id, !c.active);
      await reloadCoaches();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    }
  };

  const openRoute = async (t) => {
    setRouteModalTrain(t);
    try {
      const res = await AdminApi.trainRoute(t.id);
      setRouteStops(res.data.data.map((r) => ({
        stationId: r.stationId,
        sequenceNumber: r.sequenceNumber,
        distanceFromSourceKm: r.distanceFromSourceKm,
        haltMinutes: r.haltMinutes,
      })));
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    }
  };

  const saveRoute = async (e) => {
    e.preventDefault();
    setRouteSaving(true);
    try {
      await AdminApi.updateTrainRoute(routeModalTrain.id, orderedStops(routeStops));
      showToast('Route saved', 'success');
      setRouteModalTrain(null);
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setRouteSaving(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!trains) return <Loader label="Loading trains..." />;

  return (
    <div>
      <PageHeader
        title="Train Management"
        icon="train"
        actions={<button type="button" className="btn btn-primary" onClick={openCreate}>+ Add Train</button>}
      />

      <div className="table-wrap">
        <table><caption className="sr-only">Trains</caption>
          <thead><tr><th scope="col">Number</th><th scope="col">Name</th><th scope="col">Type</th><th scope="col">Route</th><th scope="col">Status</th><th scope="col"></th></tr></thead>
          <tbody>
            {trains.map((t) => (
              <tr key={t.id}>
                <td>{t.trainNumber}</td>
                <td>{t.name}</td>
                <td><span className="badge badge-neutral">{t.type}</span></td>
                <td>{t.sourceStationCode} → {t.destinationStationCode}</td>
                <td><span className={`badge ${t.active ? 'badge-confirmed' : 'badge-cancelled'}`}>{t.active ? 'Active' : 'Inactive'}</span></td>
                <td className="flex gap-sm">
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => openRoute(t)}>Route</button>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => openCoaches(t)}>Coaches</button>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggleActive(t)}>{t.active ? 'Deactivate' : 'Activate'}</button>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => removeTrain(t)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modalOpen && (
        <Modal title="Add Train" onClose={() => setModalOpen(false)}>
          <form onSubmit={saveTrain} style={{ maxHeight: '70vh', overflowY: 'auto' }}>
            <div className="grid grid-2">
              <div className="field"><label htmlFor="train-number">Train number</label><input id="train-number" value={form.trainNumber} onChange={(e) => setForm((f) => ({ ...f, trainNumber: e.target.value }))} required /></div>
              <div className="field"><label htmlFor="train-type">Train type</label>
                <select id="train-type" value={form.type} onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))}>
                  {TRAIN_TYPES.map((t) => <option key={t}>{t}</option>)}
                </select>
              </div>
            </div>
            <div className="field"><label htmlFor="train-name">Train name</label><input id="train-name" value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} required /></div>
            <div className="grid grid-2">
              <div className="field"><label htmlFor="source-station">Source station</label>
                <select id="source-station" value={form.sourceStationId} onChange={(e) => setForm((f) => ({ ...f, sourceStationId: e.target.value }))} required>
                  <option value="">Select</option>
                  {stations.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.code})</option>)}
                </select>
              </div>
              <div className="field"><label htmlFor="destination-station">Destination station</label>
                <select id="destination-station" value={form.destinationStationId} onChange={(e) => setForm((f) => ({ ...f, destinationStationId: e.target.value }))} required>
                  <option value="">Select</option>
                  {stations.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.code})</option>)}
                </select>
              </div>
            </div>
            <div className="grid grid-2">
              <div className="field"><label htmlFor="departure-time">Departure time</label><input id="departure-time" type="time" value={form.departureTime} onChange={(e) => setForm((f) => ({ ...f, departureTime: e.target.value }))} required /></div>
              <div className="field"><label htmlFor="arrival-time">Arrival time</label><input id="arrival-time" type="time" value={form.arrivalTime} onChange={(e) => setForm((f) => ({ ...f, arrivalTime: e.target.value }))} required /></div>
            </div>
            <div className="field"><label htmlFor="duration-minutes">Duration (minutes)</label><input id="duration-minutes" type="number" value={form.durationMinutes} onChange={(e) => setForm((f) => ({ ...f, durationMinutes: Number(e.target.value) }))} required /></div>
            <fieldset className="field">
              <legend className="field-label">Runs on</legend>
              <div className="seg-group" role="group" aria-label="Runs on">
                {DAYS.map((d) => (
                  <button
                    type="button"
                    key={d}
                    className="seg"
                    aria-pressed={form.runsOn.includes(d)}
                    onClick={() => toggleDay(d)}
                  >{d}</button>
                ))}
              </div>
              <p className="field-hint">Only the selected days will be searchable.</p>
            </fieldset>

            <fieldset className="field">
              <legend className="field-label">Route stations (optional, in order)</legend>
              <div className="field-head">
                <span className="field-hint">Add every stop the train calls at, in order.</span>
                <button type="button" className="btn btn-ghost btn-sm" onClick={addFormRouteStop}>+ Add stop</button>
              </div>
              {form.route.map((r, idx) => (
                <div key={idx} className="grid grid-3" style={{ marginTop: 8, alignItems: 'end' }}>
                  <div className="field">
                    <label className="sr-only" htmlFor={`form-route-${idx}-station`}>Stop {idx + 1} station</label>
                    <select
                      id={`form-route-${idx}-station`}
                      value={r.stationId}
                      onChange={(e) => updateFormRouteStop(idx, 'stationId', Number(e.target.value))}
                    >
                      <option value="">Station</option>
                      {stations.map((s) => <option key={s.id} value={s.id}>{s.code}</option>)}
                    </select>
                  </div>
                  <div className="field">
                    <label className="sr-only" htmlFor={`form-route-${idx}-distance`}>Stop {idx + 1} distance from source (km)</label>
                    <input
                      id={`form-route-${idx}-distance`}
                      type="number"
                      placeholder="Distance (km)"
                      value={r.distanceFromSourceKm}
                      onChange={(e) => updateFormRouteStop(idx, 'distanceFromSourceKm', Number(e.target.value))}
                    />
                  </div>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => removeFormRouteStop(idx)}>Remove</button>
                </div>
              ))}
              <p className="field-hint">Include the source and destination as the first and last stops for distance-based fares to work correctly.</p>
            </fieldset>

            <div className="flex gap-sm">
              <button type="button" className="btn btn-secondary" onClick={() => setModalOpen(false)}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving...' : 'Create Train'}</button>
            </div>
          </form>
        </Modal>
      )}

      {coachModalTrain && (
        <Modal title={`Coaches — ${coachModalTrain.name}`} onClose={() => setCoachModalTrain(null)}>
          <div className="flex-col gap-sm" style={{ marginBottom: 16 }}>
            {coaches.map((c) => (
              <div key={c.id} className="surface-soft flex-between" style={{ padding: 10 }}>
                <span>{c.coachCode} · {c.seatClass} · {c.totalSeats} seats · ₹{c.baseFare}{c.racCapacity ? ` · RAC ${c.racCapacity}` : ''}</span>
                <span className="flex gap-sm">
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => editCoach(c)}>Edit</button>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggleCoachActive(c)}>{c.active ? 'Deactivate' : 'Activate'}</button>
                </span>
              </div>
            ))}
            {coaches.length === 0 && <p className="text-muted">No coaches yet.</p>}
          </div>
          <form onSubmit={submitCoach}>
            <h4>{editingCoachId ? 'Edit coach' : 'Add coach'}</h4>
            <div className="grid grid-2">
              <div className="field"><label htmlFor="coach-code">Coach code</label><input id="coach-code" value={coachForm.coachCode} onChange={(e) => setCoachForm((f) => ({ ...f, coachCode: e.target.value }))} required /></div>
              <div className="field"><label htmlFor="class">Class</label>
                <select id="class" value={coachForm.seatClass} disabled={!!editingCoachId} onChange={(e) => setCoachForm((f) => ({ ...f, seatClass: e.target.value }))}>
                  {SEAT_CLASSES.map((c) => <option key={c}>{c}</option>)}
                </select>
                {editingCoachId && <p className="field-hint">Seat class cannot change once seats exist.</p>}
              </div>
              <div className="field"><label htmlFor="total-seats">Total seats</label><input id="total-seats" type="number" value={coachForm.totalSeats} onChange={(e) => setCoachForm((f) => ({ ...f, totalSeats: Number(e.target.value) }))} required /></div>
              <div className="field"><label htmlFor="base-fare">Base fare (₹)</label><input id="base-fare" type="number" value={coachForm.baseFare} onChange={(e) => setCoachForm((f) => ({ ...f, baseFare: Number(e.target.value) }))} required /></div>
              <div className="field"><label htmlFor="rac-capacity">RAC capacity</label><input id="rac-capacity" type="number" value={coachForm.racCapacity} onChange={(e) => setCoachForm((f) => ({ ...f, racCapacity: Number(e.target.value) }))} min={0} max={50} /></div>
            </div>
            <div className="flex gap-sm">
              {editingCoachId && <button type="button" className="btn btn-secondary" onClick={() => { setEditingCoachId(null); setCoachForm(emptyCoachForm()); }}>Cancel edit</button>}
              <button type="submit" className="btn btn-primary" disabled={coachSaving}>{coachSaving ? 'Saving...' : (editingCoachId ? 'Save Coach' : 'Add Coach')}</button>
            </div>
          </form>
        </Modal>
      )}

      {routeModalTrain && (
        <Modal title={`Route — ${routeModalTrain.name}`} onClose={() => setRouteModalTrain(null)}>
          <form onSubmit={saveRoute} style={{ maxHeight: '70vh', overflowY: 'auto' }}>
            <fieldset className="field">
              <legend className="field-label">Stops in order</legend>
              <div className="field-head">
                <span className="field-hint">The first and last stops should be the source and destination.</span>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => addRouteStop(setRouteStops)}>+ Add stop</button>
              </div>
              {routeStops.map((r, idx) => (
                <div key={idx} className="grid grid-3" style={{ marginTop: 8, alignItems: 'end' }}>
                  <div className="field">
                    <label className="sr-only" htmlFor={`route-stop-${idx}-station`}>Stop {idx + 1} station</label>
                    <select
                      id={`route-stop-${idx}-station`}
                      value={r.stationId || ''}
                      onChange={(e) => updateRouteStop(setRouteStops, idx, 'stationId', Number(e.target.value))}
                      required
                    >
                      <option value="">Station</option>
                      {stations.map((s) => <option key={s.id} value={s.id}>{s.code} — {s.name}</option>)}
                    </select>
                  </div>
                  <div className="field">
                    <label className="sr-only" htmlFor={`route-stop-${idx}-distance`}>Stop {idx + 1} distance from source (km)</label>
                    <input
                      id={`route-stop-${idx}-distance`}
                      type="number"
                      placeholder="Distance (km)"
                      value={r.distanceFromSourceKm}
                      onChange={(e) => updateRouteStop(setRouteStops, idx, 'distanceFromSourceKm', Number(e.target.value))}
                    />
                  </div>
                  <div className="flex gap-sm" style={{ alignItems: 'center' }}>
                    <div className="field">
                      <label className="sr-only" htmlFor={`route-stop-${idx}-halt`}>Stop {idx + 1} halt in minutes</label>
                      <input
                        id={`route-stop-${idx}-halt`}
                        type="number"
                        placeholder="Halt (min)"
                        value={r.haltMinutes}
                        onChange={(e) => updateRouteStop(setRouteStops, idx, 'haltMinutes', Number(e.target.value))}
                        style={{ width: 110 }}
                      />
                    </div>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => removeRouteStop(setRouteStops, idx)}>Remove</button>
                  </div>
                </div>
              ))}
              {routeStops.length === 0 && <p className="text-muted">This train has no stops. Add the source and destination first.</p>}
              <p className="field-hint" style={{ marginTop: 12 }}>The first and last stops should be the source and destination; distances drive distance-based fares.</p>
            </fieldset>
            <div className="flex gap-sm" style={{ marginTop: 12 }}>
              <button type="button" className="btn btn-secondary" onClick={() => setRouteModalTrain(null)}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={routeSaving}>{routeSaving ? 'Saving...' : 'Save Route'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
