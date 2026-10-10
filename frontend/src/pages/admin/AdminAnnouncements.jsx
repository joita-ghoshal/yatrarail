import { useCallback, useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { useToast } from '../../context/ToastContext';
import EmptyState from '../../components/EmptyState';
import Loader from '../../components/Loader';
import PageHeader from '../../components/PageHeader';

/**
 * Publishing an announcement, and — the part that was missing — seeing whether anybody read one.
 * <p>
 * An announcement is one authored row with no recipient, which is the right model for content: the
 * question "how many notices exist" stays a row count. But it means the notice cannot carry its own
 * read flag, because "has this been read" is a different question for every one of the thousands of
 * accounts it was shown to. Read state is therefore a per-reader receipt, and the reach below is
 * counted from those receipts: an administrator who publishes a delay notice and sees it sitting at
 * zero readers is being told something true.
 */
export default function AdminAnnouncements() {
  const { showToast } = useToast();
  const [form, setForm] = useState({ title: '', message: '' });
  const [sending, setSending] = useState(false);
  const [history, setHistory] = useState(null);
  const [error, setError] = useState(null);

  const loadHistory = useCallback(() => {
    setError(null);
    return AdminApi.announcements()
      .then((res) => setHistory(res.data.data))
      .catch((err) => setError(extractErrorMessage(err)));
  }, []);

  useEffect(() => { loadHistory(); }, [loadHistory]);

  const submit = async (e) => {
    e.preventDefault();
    setSending(true);
    try {
      await AdminApi.announce(form);
      showToast('Announcement published to all passengers', 'success');
      setForm({ title: '', message: '' });
      loadHistory();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSending(false);
    }
  };

  return (
    <div>
      <PageHeader
        title="Announcements"
        subtitle="Publish a system-wide notice that appears in every passenger's notification feed."
        icon="megaphone"
      />
      <div className="card" style={{ maxWidth: 520 }}>
        <form onSubmit={submit}>
          <div className="field">
            <label htmlFor="title">Title</label>
            <input id="title" value={form.title} onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))} required />
          </div>
          <div className="field">
            <label htmlFor="message">Message</label>
            <textarea id="message" rows={4} value={form.message} onChange={(e) => setForm((f) => ({ ...f, message: e.target.value }))} required />
          </div>
          <button type="submit" className="btn btn-primary" disabled={sending}>{sending ? 'Publishing...' : 'Publish Announcement'}</button>
        </form>
      </div>

      <h2 className="section-title" style={{ marginTop: 'var(--space-7)' }}>Published notices</h2>
      <p className="text-muted">
        Every account receives each notice. Each passenger's acknowledgement is recorded separately,
        so the reader count is a real figure rather than a shared flag the first reader would set.
      </p>

      {error && (
        <div className="notice notice-bad" role="alert" style={{ marginTop: 'var(--space-3)' }}>
          <span>We could not load the announcement history. {error}</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={loadHistory}>Try again</button>
        </div>
      )}

      {history === null && !error && <Loader label="Loading announcements..." />}

      {history !== null && history.length === 0 && (
        <EmptyState title="Nothing published yet" message="Announcements you publish will be listed here with their reach." />
      )}

      {history !== null && history.length > 0 && (
        <div className="flex-col gap-md">
          {history.map((a) => {
            const pct = a.audience > 0 ? Math.round((a.readCount / a.audience) * 100) : 0;
            return (
              <div key={a.id} className="card">
                <div className="card-head">
                  <strong>{a.title}</strong>
                  <span className="badge badge-neutral">{a.readCount} of {a.audience} read ({pct}%)</span>
                </div>
                <p style={{ margin: 0 }}>{a.message}</p>
                <p className="field-hint" style={{ marginTop: 'var(--space-2)' }}>
                  Published {new Date(a.createdAt).toLocaleString()}
                </p>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}