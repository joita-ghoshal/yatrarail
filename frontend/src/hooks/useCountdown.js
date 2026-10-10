import { useEffect, useState } from 'react';

/**
 * Ticks once a second while a hold deadline is in the future.
 * Returns { remainingMs, expired } — `expired` flips to true the moment the
 * backend hold window closes, so the UI can stop offering payment.
 */
export default function useCountdown(deadline) {
  const compute = () => {
    if (!deadline) return 0;
    const target = new Date(deadline).getTime();
    return Number.isNaN(target) ? 0 : Math.max(0, target - Date.now());
  };

  const [remainingMs, setRemainingMs] = useState(compute);

  useEffect(() => {
    setRemainingMs(compute());
    if (!deadline) return undefined;

    const tick = setInterval(() => {
      const left = compute();
      setRemainingMs(left);
      if (left === 0) clearInterval(tick);
    }, 1000);

    return () => clearInterval(tick);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deadline]);

  return { remainingMs, expired: Boolean(deadline) && remainingMs === 0 };
}
