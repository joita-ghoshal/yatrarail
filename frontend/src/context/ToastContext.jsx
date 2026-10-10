import { createContext, useCallback, useContext, useRef, useState } from 'react';

const ToastContext = createContext(null);

let idCounter = 0;

const DISMISS_AFTER_MS = 4500;
const DISMISS_AFTER_MS_ERROR = 9000;

/**
 * Transient feedback.
 *
 * The stack previously rendered bare divs with no live region, which made every
 * toast in the application invisible to a screen reader — including the only
 * confirmation a user gets after signing in, paying, or failing to pay. Two
 * always-mounted regions fix that: assistive technology has to find the live
 * region in the DOM *before* the text appears in it, otherwise there is
 * nothing to announce.
 *
 *   - polite  (role="status") for confirmations; queued behind whatever else is
 *     being read, which is right for "Profile updated"
 *   - assertive (role="alert") for failures; interrupts, which is right for
 *     "Payment failed"
 *
 * Toasts are also no longer a fixed 4.5s with no control: a failure stays up
 * twice as long, hovering or tabbing onto one stops the timer, and each has a
 * dismiss button (WCAG 2.2.1).
 */
export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const timers = useRef(new Map());

  const removeToast = useCallback((id) => {
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const scheduleDismiss = useCallback(
    (id, type) => {
      const existing = timers.current.get(id);
      if (existing) clearTimeout(existing);
      const delay = type === 'error' ? DISMISS_AFTER_MS_ERROR : DISMISS_AFTER_MS;
      timers.current.set(id, setTimeout(() => removeToast(id), delay));
    },
    [removeToast],
  );

  const showToast = useCallback(
    (message, type = 'info') => {
      const id = ++idCounter;
      setToasts((prev) => [...prev.slice(-3), { id, message, type }]);
      scheduleDismiss(id, type);
      return id;
    },
    [scheduleDismiss],
  );

  const holdToast = useCallback((id) => {
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const render = (list) =>
    list.map((t) => (
      <div
        key={t.id}
        className={`toast toast-${t.type}`}
        onMouseEnter={() => holdToast(t.id)}
        onFocus={() => holdToast(t.id)}
      >
        <div className="flex-between" style={{ gap: 'var(--space-3)' }}>
          <span>{t.message}</span>
          <button type="button" className="modal-close" style={{ margin: 0 }} onClick={() => removeToast(t.id)}>
            <span aria-hidden="true">&times;</span>
            <span className="sr-only">Dismiss notification</span>
          </button>
        </div>
      </div>
    ));

  return (
    <ToastContext.Provider value={{ showToast, removeToast }}>
      {children}
      {/* One fixed stack holding two always-mounted live regions, so the
          assertive and polite queues never overlap on screen. */}
      <div className="toast-stack">
        <div className="toast-region" role="status" aria-live="polite" aria-relevant="additions text">
          {render(toasts.filter((t) => t.type !== 'error'))}
        </div>
        <div className="toast-region" role="alert" aria-live="assertive" aria-relevant="additions text">
          {render(toasts.filter((t) => t.type === 'error'))}
        </div>
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside a <ToastProvider>.');
  return ctx;
}