import { useEffect, useId, useRef, useState } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import ThemeToggle from './ThemeToggle';
import NotificationBell from './NotificationBell';
import SessionCountdown from './SessionCountdown';
import Icon from './Icon';

const PASSENGER_LINKS = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/search', label: 'Search Trains' },
  { to: '/bookings', label: 'My Bookings' },
  { to: '/payments', label: 'Payments' },
];

// A guest gets the same booking links, minus a dashboard they have no history in yet.
const GUEST_LINKS = [
  { to: '/search', label: 'Search Trains' },
  { to: '/bookings', label: 'My Bookings' },
  { to: '/payments', label: 'Payments' },
];

const CONSOLE_LINKS = {
  ADMIN: { to: '/admin', label: 'Admin Console' },
  STAFF: { to: '/staff', label: 'Staff Console' },
};

/**
 * The application banner.
 *
 * On one row this used to be a brand, up to four nav links, a theme toggle, a
 * notification bell, a guest countdown, a name and a logout button, in a flex
 * row with no wrap and no overflow rule. At 375px that cannot fit, so every
 * passenger page scrolled sideways on a phone.
 *
 * It is now a disclosure: below the `md` breakpoint a hamburger owns an
 * aria-expanded panel, and above it the same links sit inline. There is one
 * navigation in the DOM, not two that can disagree. The panel closes on Escape,
 * on a route change and on an outside click, and focus returns to the toggle.
 */
export default function Navbar() {
  const { user, isAuthenticated, logout, isTemporary } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const toggleRef = useRef(null);
  const panelId = useId();

  const isPassengerSide = isAuthenticated && (user.role === 'PASSENGER' || isTemporary);
  const links = isTemporary ? GUEST_LINKS : PASSENGER_LINKS;
  const consoleLink = isAuthenticated ? CONSOLE_LINKS[user.role] : undefined;

  const homeLink = !isAuthenticated
    ? '/'
    : user.role === 'ADMIN'
      ? '/admin'
      : user.role === 'STAFF'
        ? '/staff'
        : isTemporary
          ? '/search'
          : '/dashboard';

  // Navigating anywhere closes the panel, otherwise it stays open over the page
  // the user just asked for.
  useEffect(() => {
    setOpen(false);
  }, [location.pathname]);

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      toggleRef.current?.focus();
    };
    const onPointerDown = (event) => {
      if (!event.target.closest('[data-nav-shell]')) setOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('pointerdown', onPointerDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('pointerdown', onPointerDown);
    };
  }, [open]);

  const handleLogout = () => {
    logout();
    // The public landing page, not /login: logging out is not an auth failure
    // and must not look like one. `replace` keeps the protected page out of the
    // history stack so Back cannot return to it.
    navigate('/', { replace: true });
  };

  // The active page is marked by a tint AND by aria-current, which React Router
  // sets for us. Colour alone is not a state a screen reader can perceive.
  const navLinkClass = ({ isActive }) => `nav-link${isActive ? ' is-active' : ''}`;

  return (
    <header className="app-bar" data-nav-shell>
      <div className="container app-bar-inner">
        <Link to={homeLink} className="brand">
          <span className="brand-mark" aria-hidden="true"><Icon name="train" size={20} /></span>
          <span className="brand-text">YatraRail</span>
          <span className="sr-only">YatraRail, go to your home page</span>
        </Link>

        <button
          type="button"
          ref={toggleRef}
          className="btn btn-ghost btn-icon nav-toggle"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((o) => !o)}
        >
          <Icon name={open ? 'close' : 'menu'} size={22} />
          <span className="sr-only">{open ? 'Close main menu' : 'Open main menu'}</span>
        </button>

        <nav
          className="nav-primary"
          id={panelId}
          data-open={open}
          aria-label="Main navigation"
        >
          <ul className="nav-links">
            {isPassengerSide &&
              links.map((l) => (
                <li key={l.to}>
                  <NavLink to={l.to} className={navLinkClass}>
                    {l.label}
                  </NavLink>
                </li>
              ))}
            {consoleLink && (
              <li>
                <NavLink to={consoleLink.to} className={navLinkClass} end>
                  {consoleLink.label}
                </NavLink>
              </li>
            )}
          </ul>

          <div className="nav-actions">
            <ThemeToggle />
            {isAuthenticated && <NotificationBell />}
            {isTemporary && <SessionCountdown />}

            {isAuthenticated ? (
              <>
                <Link to="/profile" className="btn btn-ghost btn-sm">
                  <Icon name="user" size={16} />
                  <span aria-hidden="true">{user.fullName?.split(' ')[0]}</span>
                  <span className="sr-only">Profile and settings for {user.fullName}</span>
                </Link>
                <button type="button" className="btn btn-secondary btn-sm" onClick={handleLogout}>
                  {isTemporary ? 'End session' : 'Logout'}
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="btn btn-ghost btn-sm">Login</Link>
                <Link to="/login?guest=1" className="btn btn-secondary btn-sm">Continue as Guest</Link>
                <Link to="/register" className="btn btn-primary btn-sm">Register</Link>
              </>
            )}
          </div>
        </nav>
      </div>
    </header>
  );
}