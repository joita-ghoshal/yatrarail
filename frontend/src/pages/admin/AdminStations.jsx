import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { useToast } from '../../context/ToastContext';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import Modal from '../../components/Modal';
import PageHeader from '../../components/PageHeader';

const EMPTY = { code: '', name: '', city: '', state: '' };

export default function AdminStations() {
  const { showToast } = useToast();
  const [stations, setStations] = useState(null);
  const [error, setError] = useState(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [form, setForm] = useState(EMPTY);
  const [saving, setSaving] = useState(false);

  const load = () => {
    setError(null);
    AdminApi.stations().then((res) => setStations(res.data.data)).catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  const openCreate = () => { setEditing(null); setForm(EMPTY); setModalOpen(true); };
  const openEdit = (s) => { setEditing(s); setForm(s); setModalOpen(true); };

  const save = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      if (editing) await AdminApi.updateStation(editing.id, form);
      else await AdminApi.createStation(form);
      showToast(`Station ${editing ? 'updated' : 'created'}`, 'success');
      setModalOpen(false);
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (s) => {
    try {
      await AdminApi.setStationActive(s.id, !s.active);
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!stations) return <Loader label="Loading stations..." />;

  return (
    <div>
      <PageHeader
        title="Station Management"
        subtitle="The stations a train can call at."
        icon="station"
        actions={<button type="button" className="btn btn-primary" onClick={openCreate}>+ Add Station</button>}
      />
      <div className="table-wrap">
        <table><caption className="sr-only">Stations</caption>
          <thead><tr><th scope="col">Code</th><th scope="col">Name</th><th scope="col">City</th><th scope="col">State</th><th scope="col">Status</th><th scope="col"></th></tr></thead>
          <tbody>
            {stations.map((s) => (
              <tr key={s.id}>
                <td><strong>{s.code}</strong></td>
                <td>{s.name}</td>
                <td>{s.city}</td>
                <td>{s.state}</td>
                <td><span className={`badge ${s.active ? 'badge-confirmed' : 'badge-cancelled'}`}>{s.active ? 'Active' : 'Inactive'}</span></td>
                <td className="flex gap-sm">
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => openEdit(s)}>Edit</button>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggleActive(s)}>{s.active ? 'Deactivate' : 'Activate'}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {modalOpen && (
        <Modal title={editing ? 'Edit Station' : 'Add Station'} onClose={() => setModalOpen(false)}>
          <form onSubmit={save}>
            <div className="field">
              <label htmlFor="station-code">Station code</label>
              <input id="station-code" value={form.code} disabled={!!editing} onChange={(e) => setForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))} required maxLength={10} />
            </div>
            <div className="field">
              <label htmlFor="station-name">Station name</label>
              <input id="station-name" value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} required />
            </div>
            <div className="field">
              <label htmlFor="city">City</label>
              <input id="city" value={form.city} onChange={(e) => setForm((f) => ({ ...f, city: e.target.value }))} required />
            </div>
            <div className="field">
              <label htmlFor="state">State</label>
              <input id="state" value={form.state} onChange={(e) => setForm((f) => ({ ...f, state: e.target.value }))} required />
            </div>
            <div className="flex gap-sm">
              <button type="button" className="btn btn-secondary" onClick={() => setModalOpen(false)}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving...' : 'Save'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
