import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { useToast } from '../../context/ToastContext';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import Modal from '../../components/Modal';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';

const ROLES = ['ALL', 'PASSENGER', 'STAFF', 'ADMIN'];
const ASSIGNABLE_ROLES = ['PASSENGER', 'STAFF', 'ADMIN'];
const SIZE = 20;

function emptyCreateForm() {
  return { fullName: '', email: '', phone: '', role: 'PASSENGER', password: '', gender: 'MALE', age: '' };
}

function editFormFrom(user) {
  return {
    fullName: user.fullName || '',
    phone: user.phone || '',
    role: user.role || 'PASSENGER',
    gender: user.gender || 'MALE',
    age: user.age ?? '',
  };
}

export default function AdminUsers() {
  const { showToast } = useToast();
  const [page, setPage] = useState(null);
  const [error, setError] = useState(null);

  const [roleFilter, setRoleFilter] = useState('ALL');
  const [activeFilter, setActiveFilter] = useState('ALL');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [modal, setModal] = useState(null); // { mode: 'create' | 'edit', user? }
  const [form, setForm] = useState(emptyCreateForm());
  const [saving, setSaving] = useState(false);

  const load = () => {
    setError(null);
    const params = { page: pageIndex, size: SIZE };
    if (search) params.search = search;
    if (roleFilter !== 'ALL') params.role = roleFilter;
    if (activeFilter !== 'ALL') params.active = activeFilter === 'ACTIVE';
    AdminApi.users(params)
      .then((res) => setPage(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, [search, roleFilter, activeFilter, pageIndex]);

  const openCreate = () => { setForm(emptyCreateForm()); setModal({ mode: 'create' }); };
  const openEdit = (user) => { setForm(editFormFrom(user)); setModal({ mode: 'edit', user }); };

  const submitSearch = (e) => {
    e.preventDefault();
    setPageIndex(0);
    setSearch(searchInput.trim());
  };

  const save = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      if (modal.mode === 'create') {
        const payload = { ...form, age: form.age === '' ? null : Number(form.age) };
        await AdminApi.createUser(payload);
        showToast('Account created', 'success');
      } else {
        const payload = {
          fullName: form.fullName,
          phone: form.phone,
          role: form.role,
          gender: form.gender,
          age: form.age === '' ? null : Number(form.age),
        };
        await AdminApi.updateUser(modal.user.id, payload);
        showToast('Account updated', 'success');
      }
      setModal(null);
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  const toggleActive = async (user) => {
    try {
      await AdminApi.setUserActive(user.id, !user.active);
      showToast(`${user.fullName} ${user.active ? 'deactivated' : 'activated'}`, 'success');
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!page) return <Loader label="Loading users..." />;

  const rows = page.content || [];

  return (
    <div>
      <PageHeader
        title="User Management"
        subtitle="Create accounts, change roles and manage access."
        icon="users"
        actions={<button type="button" className="btn btn-primary" onClick={openCreate}>+ Add User</button>}
      />

      <div className="toolbar">
        <form className="grow" onSubmit={submitSearch}>
          <input
            className="control"
            placeholder="Search by name or email"
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
          />
          <button className="btn btn-secondary" type="submit">Search</button>
        </form>
        <div className="seg-group" role="group" aria-label="Filter by role">
          {ROLES.map((r) => (
            <button
              key={r}
              type="button"
              className="seg"
              aria-pressed={roleFilter === r}
              onClick={() => { setRoleFilter(r); setPageIndex(0); }}
            >
              {r}
            </button>
          ))}
        </div>
        <select
          className="control select"
          aria-label="Filter by status"
          value={activeFilter}
          onChange={(e) => { setActiveFilter(e.target.value); setPageIndex(0); }}
        >
          <option value="ALL">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
      </div>

      <div className="table-wrap">
        <table><caption className="sr-only">Users</caption>
          <thead><tr><th scope="col">Name</th><th scope="col">Email</th><th scope="col">Phone</th><th scope="col">Role</th><th scope="col">Status</th><th scope="col"></th></tr></thead>
          <tbody>
            {rows.map((u) => (
              <tr key={u.id}>
                <td>{u.fullName}</td>
                <td>{u.email}</td>
                <td>{u.phone}</td>
                <td><span className="badge badge-neutral">{u.role}</span></td>
                <td><span className={`badge ${u.active ? 'badge-confirmed' : 'badge-cancelled'}`}>{u.active ? 'Active' : 'Inactive'}</span></td>
                <td className="flex gap-sm">
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => openEdit(u)}>Edit</button>
                  {!u.temporaryAccount && (
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggleActive(u)}>{u.active ? 'Deactivate' : 'Activate'}</button>
                  )}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={6} className="text-muted table-empty">No accounts match these filters.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <Pagination page={page} onChange={setPageIndex} noun="account" />

      {modal && (
        <Modal title={modal.mode === 'create' ? 'Add User' : `Edit ${modal.user.fullName}`} onClose={() => setModal(null)}>
          <form onSubmit={save}>
            <div className="field">
              <label htmlFor="full-name">Full name</label>
              <input id="full-name" value={form.fullName} onChange={(e) => setForm((f) => ({ ...f, fullName: e.target.value }))} required />
            </div>
            {modal.mode === 'create' && (
              <>
                <div className="field">
                  <label htmlFor="email">Email</label>
                  <input id="email" type="email" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} required />
                </div>
                <div className="field">
                  <label htmlFor="initial-password">Initial password</label>
                  <input id="initial-password" type="password" value={form.password} onChange={(e) => setForm((f) => ({ ...f, password: e.target.value }))} minLength={8} required />
                  <p className="field-hint">The account holder should change this after their first sign-in.</p>
                </div>
              </>
            )}
            <div className="grid grid-2">
              <div className="field">
                <label htmlFor="phone">Phone</label>
                <input id="phone" value={form.phone} onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))} required />
              </div>
              <div className="field">
                <label htmlFor="role">Role</label>
                <select id="role" value={form.role} onChange={(e) => setForm((f) => ({ ...f, role: e.target.value }))}>
                  {ASSIGNABLE_ROLES.map((r) => <option key={r}>{r}</option>)}
                </select>
              </div>
            </div>
            <div className="grid grid-2">
              <div className="field">
                <label htmlFor="gender">Gender</label>
                <select id="gender" value={form.gender} onChange={(e) => setForm((f) => ({ ...f, gender: e.target.value }))}>
                  <option>MALE</option><option>FEMALE</option><option>OTHER</option>
                </select>
              </div>
              <div className="field">
                <label htmlFor="age">Age</label>
                <input id="age" type="number" value={form.age} onChange={(e) => setForm((f) => ({ ...f, age: e.target.value }))} min={0} max={120} />
              </div>
            </div>
            <div className="flex gap-sm">
              <button type="button" className="btn btn-secondary" onClick={() => setModal(null)}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving...' : (modal.mode === 'create' ? 'Create User' : 'Save Changes')}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
