import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import Icon from './Icon';

/**
 * The console shell shared by the admin console and the staff portal.
 *
 * Both layouts used to hard-code `grid-template-columns: 220px 1fr` inline with
 * no media query, so below 860px the rail never went away and squeezed the
 * content into roughly 155px. The rail is now an overlay drawer below `md`:
 * it slides in over the content behind a scrim, closes on Escape, on a route
 * change and on a scrim click, and returns focus to the button that opened it.
 *
 * The shell owns its own width rather than borrowing `.container` (1180px), so
 * the main column expands to fill a widescreen instead of leaving dead space.
 *
 * One component rather than two copies, because the admin and staff portals had
 * become the same file with different link lists, and the next fix would
 * otherwise have to be made twice.
 */
export default function ConsoleLayout({ title, navLabel, links, children }) {
  const [open, setOpen] = useState(false);
  const toggleRef = useRef(null);
  const drawerId = useId();
  const closeDrawer = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      toggleRef.current?.focus();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [open]);

  return (
    <div className="console">
      <button
        type="button"
        ref={toggleRef}
        className="btn btn-secondary btn-sm console-drawer-toggle"
        aria-expanded={open}
        aria-controls={drawerId}
        onClick={() => setOpen(true)}
      >
        <Icon name="menu" size={16} /> {title} menu
      </button>

      <aside
        id={drawerId}
        className={`console-nav${open ? ' is-open' : ''}`}
        aria-label={navLabel}
      >
        <h2 className="console-nav-title">
          <span className="brand-mark" aria-hidden="true"><Icon name="train" size={18} /></span>
          {title}
        </h2>
        <nav aria-label={navLabel}>
          <ul className="console-links">
            {links.map((l) => (
              <li key={l.to}>
                <NavLink
                  to={l.to}
                  end={l.end}
                  className={({ isActive }) => `console-link${isActive ? ' is-active' : ''}`}
                  onClick={closeDrawer}
                >
                  <Icon name={l.icon} size={19} />
                  {l.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="console-nav-foot">
          <Link to="/" onClick={closeDrawer}>
            <Icon name="arrow-right" size={15} />
            Back to YatraRail
          </Link>
        </div>
      </aside>

      {open && (
        <button
          type="button"
          className="console-scrim"
          aria-label="Close menu"
          onClick={() => {
            setOpen(false);
            toggleRef.current?.focus();
          }}
        />
      )}

      {/* A div, not a <main>: the page's single main landmark is owned by App so
          every route has exactly one, which cannot be got wrong per page. */}
      <div className="console-main">{children || <Outlet />}</div>
    </div>
  );
}
