import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { NotificationApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import Icon from './Icon';

/**
 * The navbar bell.
 * <p>
 * Failures used to be swallowed entirely, so a bell that had been failing for an hour was
 * indistinguishable from a bell with nothing to say -- and the badge simply stopped appearing. The
 * panel now says it could not refresh, and the button is labelled from the real unread count so a
 * screen reader is not told "Notifications" when the count is the only useful part.
 * <p>
 * Polling stops while the tab is hidden. This endpoint is polled every 30s for every signed-in user,
 * and a browser tab nobody is looking at does not need to know about a new notification; it catches
 * up on the `visibilitychange` instead of polling a background tab for hours.
 */
export default function NotificationBell() {
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const [unread, setUnread] = useState(0);
  const [error, setError] = useState(null);
  const ref = useRef(null);

  const load = useCallback(async () => {
    try {
      const [listRes, countRes] = await Promise.all([
        NotificationApi.list(),
        NotificationApi.unreadCount(),
      ]);
      setNotifications(listRes.data.data.slice(0, 6));
      setUnread(countRes.data.data.count);
      setError(null);
    } catch (err) {
      // Keep the last good list on screen: an error must not replace real information with an
      // empty panel, it just has to be visible that the panel may be stale.
      setError(extractErrorMessage(err));
    }
  }, []);

  useEffect(() => {
    const POLL_MS = 30000;
    let timer = null;

    const start = () => {
      if (timer === null) timer = setInterval(load, POLL_MS);
    };
    const stop = () => {
      if (timer !== null) { clearInterval(timer); timer = null; }
    };

    load();
    start();

    const onVisibility = () => { if (document.hidden) { stop(); } else { load(); start(); } };
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [load]);

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', handler);
    // Escape closes it too: a popover that only closes on an outside click is unreachable by
    // keyboard, which is the whole point of having a close affordance at all.
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', handler); document.removeEventListener('keydown', onKey); };
  }, []);

  const markAll = async () => {
    try {
      await NotificationApi.markAllRead();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
    load();
  };

  return (
    <div ref={ref} className="notif-wrap">
      <button
        type="button"
        className="btn btn-ghost btn-icon"
        onClick={() => setOpen((o) => !o)}
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
        aria-expanded={open}
        aria-haspopup="dialog"
      >
        <Icon name="bell" size={19} />
        {unread > 0 && <span className="notif-count">{unread > 99 ? '99+' : unread}</span>}
      </button>
      {open && (
        <div className="popover" role="dialog" aria-label="Notifications">
          <div className="card-head" style={{ marginBottom: 'var(--space-3)' }}>
            <strong>Notifications</strong>
            <button type="button" className="btn btn-ghost btn-sm" onClick={markAll} disabled={unread === 0}>
              Mark all read
            </button>
          </div>
          {error && (
            <div className="notice notice-bad" role="status" style={{ marginBottom: 'var(--space-3)', fontSize: 'var(--fs-sm)' }}>
              Could not refresh. This list may be out of date.
            </div>
          )}
          {notifications.length === 0 && !error && <p className="text-muted">No notifications yet.</p>}
          <div className="notif-list">
            {notifications.map((n) => (
              <div key={n.id} className={`notif-item${n.isRead ? ' is-read' : ''}`}>
                <div className="notif-item-title">
                  {!n.isRead && <span className="notif-dot" aria-hidden="true" />}
                  {n.typeLabel || n.title}
                  {n.announcement && <span className="badge badge-neutral">Announcement</span>}
                </div>
                <div className="notif-item-body">{n.message}</div>
              </div>
            ))}
          </div>
          <Link to="/notifications" className="btn btn-ghost btn-sm btn-block" style={{ marginTop: 'var(--space-3)' }} onClick={() => setOpen(false)}>
            View all
          </Link>
        </div>
      )}
    </div>
  );
}