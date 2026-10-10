import { useAuth } from '../context/AuthContext';

/**
 * The visible clock on a guest session.
 *
 * It is a courtesy, not the enforcement point: the backend refuses an expired session on the very
 * next request regardless of what this shows, so a wrong countdown here can never grant extra time.
 * It turns urgent in the last five minutes so a guest is not surprised mid-checkout.
 */
function format(totalSeconds) {
  const s = Math.max(0, Math.floor(totalSeconds || 0));
  const hours = Math.floor(s / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const seconds = s % 60;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export default function SessionCountdown() {
  const { sessionSecondsLeft, isTemporary } = useAuth();

  if (!isTemporary || sessionSecondsLeft === null) return null;

  const urgent = sessionSecondsLeft <= 300;

  return (
    <span
      className={`badge nowrap ${urgent ? 'badge-pending' : 'badge-rac'}`}
      title="Time left on your guest session. It closes automatically when this runs out."
      style={{ fontVariantNumeric: 'tabular-nums' }}
    >
      Guest · {format(sessionSecondsLeft)}
    </span>
  );
}
