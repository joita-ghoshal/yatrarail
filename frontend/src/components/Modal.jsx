import { useCallback, useEffect, useId, useRef } from 'react';
import Icon from './Icon';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * A modal dialog.
 *
 * The previous version rendered two divs with onClick handlers and no dialog
 * semantics at all: no role, no aria-modal, no Escape key, no focus management.
 * A keyboard user who opened the cancel-passengers dialog was stranded behind
 * it — tabbing walked the page underneath and Escape did nothing.
 *
 * What this component guarantees:
 *   - role="dialog" + aria-modal, with the visible title as its accessible name
 *   - focus moves into the dialog when it opens and returns to the opener when
 *     it closes, so the user is not left at the top of the document
 *   - Tab and Shift+Tab cycle inside the dialog only
 *   - Escape closes it
 *   - the page behind cannot scroll while it is open
 *   - the backdrop click is a convenience, but the real affordance is the
 *     labelled close button in the header, which is what a keyboard reaches
 */
export default function Modal({ title, children, onClose, footer, wide = false }) {
  const dialogRef = useRef(null);
  const restoreFocusTo = useRef(null);
  const titleId = useId();

  // Move focus in on open, and hand it back to whatever opened the dialog on
  // close. Captured in a ref rather than state because it only matters at the
  // two boundaries of the dialog's life.
  useEffect(() => {
    restoreFocusTo.current = document.activeElement;
    const node = dialogRef.current;
    if (!node) return undefined;

    const first = node.querySelector(FOCUSABLE);
    (first || node).focus({ preventScroll: true });

    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';

    return () => {
      document.body.style.overflow = overflow;
      const target = restoreFocusTo.current;
      if (target && typeof target.focus === 'function' && document.contains(target)) {
        target.focus({ preventScroll: true });
      }
    };
  }, []);

  const onKeyDown = useCallback(
    (event) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;

      const node = dialogRef.current;
      if (!node) return;
      const items = Array.from(node.querySelectorAll(FOCUSABLE)).filter(
        (el) => el.offsetParent !== null || el === document.activeElement,
      );
      if (items.length === 0) {
        event.preventDefault();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;

      if (event.shiftKey && (active === first || !node.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    },
    [onClose],
  );

  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        // mousedown, not click: a drag that starts inside the dialog and ends on
        // the backdrop must not dismiss the form the user was filling in.
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        className={`modal ${wide ? 'modal-wide' : ''}`.trim()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        tabIndex={-1}
        ref={dialogRef}
        onKeyDown={onKeyDown}
      >
        {(title || onClose) && (
          <div className="modal-head">
            {title && <h3 id={titleId}>{title}</h3>}
            {onClose && (
              <button type="button" className="modal-close" onClick={onClose} aria-label={`Close ${title || 'dialog'}`}>
                <Icon name="close" size={20} />
              </button>
            )}
          </div>
        )}
        {children}
        {footer}
      </div>
    </div>
  );
}