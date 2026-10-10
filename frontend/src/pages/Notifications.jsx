import { useCallback, useEffect, useMemo, useState } from 'react';
import { NotificationApi } from '../api/endpoints';
import { extractErrorMessage } from '../api/client';
import Loader from '../components/Loader';
import EmptyState from '../components/EmptyState';
import PageHeader from '../components/PageHeader';

/**
 * The shared notification inbox, used at /notifications and again inside the staff portal.
 * <p>
 * `embedded` drops the standalone page container so the staff layout's sidebar grid supplies the
 * spacing instead of nesting a second centred column inside it.
 * <p>
 * A failed load used to be swallowed into an empty list, which renders as "No notifications yet" --
 * indistinguishable from genuinely having none. A passenger whose payment failed during an outage
 * saw an empty inbox and no reason to suspect it was their own connectivity. A failed load now says
 * so and offers a retry.
 */
export default function Notifications({ embedded = false }) {
  const [notifications, setNotifications] = useState(null);
  const [error, setError] = useState(null);
  const [onlyUnread, setOnlyUnread] = useState(false);

  const load = useCallback(() => {
    setError(null);
    return NotificationApi.list()
      .then((res) => setNotifications(res.data.data))
      .catch((err) => {
        // Keep whatever was already on screen: a failed refresh should not empty a list the user
        // was reading. Only a first load that never succeeded leaves the page without data.
        setError(extractErrorMessage(err));
      });
  }, []);

  useEffect(() => { load(); }, [load]);

  const markRead = async (id) => {
    // Optimistic: the row is visibly read the moment it is clicked, and a failure reloads rather
    // than leaving the list claiming otherwise.
    setNotifications((current) => (current || []).map((n) => (n.id === id ? { ...n, isRead: true } : n)));
    try {
      await NotificationApi.markRead(id);
      load();
    } catch (err) {
      setError(extractErrorMessage(err));
      load();
    }
  };

  const markAllRead = async () => {
    try {
      await NotificationApi.markAllRead();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
    load();
  };

  const unreadCount = useMemo(
    () => (notifications || []).filter((n) => !n.isRead).length,
    [notifications],
  );

  const visible = useMemo(
    () => (notifications || []).filter((n) => !onlyUnread || !n.isRead),
    [notifications, onlyUnread],
  );

  if (notifications === null && !error) return <Loader label="Loading notifications..." />;

  return (
    <div
      className={embedded ? undefined : 'container page'}
      style={embedded ? undefined : { maxWidth: 680 }}
    >
      <PageHeader
        title="Notifications"
        icon="bell"
        actions={
          <>
            <button
              type="button"
              className={onlyUnread ? 'btn btn-primary btn-sm' : 'btn btn-ghost btn-sm'}
              onClick={() => setOnlyUnread((v) => !v)}
              aria-pressed={onlyUnread}
            >
              {unreadCount > 0 ? `Unread (${unreadCount})` : 'Unread'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={markAllRead} disabled={unreadCount === 0}>
              Mark all read
            </button>
          </>
        }
      />

      {error && (
        <div className="notice notice-bad" role="alert" style={{ marginTop: 12, justifyContent: 'space-between' }}>
          <span>We could not load your notifications. {error}</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
        </div>
      )}

      {notifications !== null && visible.length === 0 ? (
        <EmptyState
          title={onlyUnread ? 'Nothing unread' : 'No notifications yet'}
          message={
            onlyUnread
              ? 'You are up to date. Switch off the unread filter to see everything.'
              : 'Booking confirmations, payment updates and announcements will appear here.'
          }
        />
      ) : (
        <div className="flex-col gap-sm">
          {visible.map((n) => (
            <div
              key={n.id}
              className="card"
              style={{
                opacity: n.isRead ? 0.7 : 1,
                borderLeft: n.isRead ? undefined : '3px solid var(--yr-orange)',
              }}
            >
              <div className="flex-between">
                <strong>
                  {n.typeLabel || n.title}
                  {n.announcement && <span className="badge badge-neutral" style={{ marginLeft: 8 }}>Announcement</span>}
                </strong>
                {!n.isRead && (
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => markRead(n.id)}>
                    Mark read
                  </button>
                )}
              </div>
              <p style={{ margin: '4px 0 0' }}>{n.message}</p>
              <p className="field-hint" style={{ marginTop: 6 }}>{new Date(n.createdAt).toLocaleString()}</p>
            </div>
          ))}
        </div>
      )}

      <p className="field-hint" style={{ marginTop: 20 }}>
        Tired of the noise? Notification settings are in your profile.
      </p>
    </div>
  );
}