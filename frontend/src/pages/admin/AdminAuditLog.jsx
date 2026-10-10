import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import PageHeader from '../../components/PageHeader';
import Pagination from '../../components/Pagination';

const SIZE = 25;

/**
 * The administrative audit trail: who did what, when.
 *
 * Read-only. There is no edit or delete button anywhere on this screen, because the API has no
 * endpoint that would answer one - the trail is append-only by construction, not by convention.
 */
export default function AdminAuditLog() {
  const [page, setPage] = useState(null);
  const [error, setError] = useState(null);
  const [actions, setActions] = useState([]);
  const [action, setAction] = useState('');
  const [entityType, setEntityType] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  useEffect(() => {
    AdminApi.auditActions().then((res) => setActions(res.data.data)).catch(() => setActions([]));
  }, []);

  const load = () => {
    setError(null);
    const params = { page: pageIndex, size: SIZE };
    if (action) params.action = action;
    if (entityType) params.entityType = entityType;
    if (search) params.search = search;
    if (from) params.from = from;
    if (to) params.to = to;
    AdminApi.auditLogs(params)
      .then((res) => setPage(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, [action, entityType, search, from, to, pageIndex]);

  const submitSearch = (e) => {
    e.preventDefault();
    setPageIndex(0);
    setSearch(searchInput.trim());
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!page) return <Loader label="Loading audit trail..." />;

  const rows = page.content || [];

  return (
    <div>
      <PageHeader title="Audit Trail" icon="audit" />

      <div className="toolbar">
        <select className="control select" value={action} onChange={(e) => { setAction(e.target.value); setPageIndex(0); }}>
          <option value="">All actions</option>
          {actions.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <input className="control" placeholder="Entity type" value={entityType} onChange={(e) => { setEntityType(e.target.value); setPageIndex(0); }} />
        <form className="grow" onSubmit={submitSearch}>
          <input className="control" placeholder="Search summary or actor" value={searchInput} onChange={(e) => setSearchInput(e.target.value)} />
          <button className="btn btn-secondary" type="submit">Search</button>
        </form>
        <label className="field-inline">
          From <input className="control" type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPageIndex(0); }} />
        </label>
        <label className="field-inline">
          To <input className="control" type="date" value={to} onChange={(e) => { setTo(e.target.value); setPageIndex(0); }} />
        </label>
      </div>

      <div className="table-wrap">
        <table><caption className="sr-only">Recent administrative actions</caption>
          <thead><tr><th scope="col">When</th><th scope="col">Action</th><th scope="col">Entity</th><th scope="col">Summary</th><th scope="col">Actor</th></tr></thead>
          <tbody>
            {rows.map((log) => (
              <tr key={log.id}>
                <td>{new Date(log.createdAt).toLocaleString()}</td>
                <td><span className="badge badge-neutral">{log.action}</span></td>
                <td>{log.entityType}{log.entityId != null ? ` #${log.entityId}` : ''}</td>
                <td style={{ maxWidth: 420 }}>{log.summary}</td>
                <td>{log.actorName}{log.actorRole ? ` · ${log.actorRole}` : ''}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="text-muted table-empty">No audit entries match these filters.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <Pagination page={page} onChange={setPageIndex} noun="entry" nounPlural="entries" />
    </div>
  );
}
