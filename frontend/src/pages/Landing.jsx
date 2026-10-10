import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import Icon from '../components/Icon';

const FEATURES = [
  {
    icon: 'map',
    title: 'Wide Network',
    body: 'Search across a curated demo network of major Indian cities and iconic long-distance routes.',
  },
  {
    icon: 'ticket',
    title: 'Real Seat Allocation',
    body: 'Every booking allocates an actual seat and berth — with automatic waitlisting and promotion when seats free up.',
  },
  {
    icon: 'shield',
    title: 'Secure by Design',
    body: 'JWT-based sign-in, role-based access and a full audit trail behind every privileged action.',
  },
  {
    icon: 'moon',
    title: 'Light & Dark Mode',
    body: 'A comfortable, accessible interface in both light and dark themes, on any screen size.',
  },
];

export default function Landing() {
  const { isAuthenticated } = useAuth();

  return (
    <div className="landing">
      <section className="hero">
        <div className="container hero-inner">
          <span className="badge badge-neutral hero-badge">
            Academic / portfolio demo — not affiliated with Indian Railways or IRCTC
          </span>
          <h1 className="hero-title">
            Book your next journey with <span className="text-orange">YatraRail</span>
          </h1>
          <p className="hero-lead">
            A modern, Indian-Railway-inspired ticket booking experience — search trains, pick your class,
            and reserve a seat in minutes. Built as a full-stack demonstration project with real booking
            and seat-allocation logic.
          </p>
          <div className="hero-actions">
            <Link to="/search" className="btn btn-primary btn-lg">
              Search Trains
              <Icon name="arrow-right" size={18} />
            </Link>
            {!isAuthenticated && (
              <Link to="/register" className="btn btn-secondary btn-lg">Create an account</Link>
            )}
          </div>
        </div>
      </section>

      <section className="container landing-features">
        <div className="grid grid-2">
          {FEATURES.map((f) => (
            <article key={f.title} className="card feature-card">
              <span className="feature-icon" aria-hidden="true">
                <Icon name={f.icon} size={22} />
              </span>
              <h3>{f.title}</h3>
              <p>{f.body}</p>
            </article>
          ))}
        </div>
      </section>

      {!isAuthenticated && (
        <section className="container landing-cta">
          <div className="cta-band">
            <div>
              <h3>New to YatraRail?</h3>
              <p>Create a free demo account to start booking tickets.</p>
            </div>
            <Link to="/register" className="btn btn-primary btn-lg">Get Started</Link>
          </div>
        </section>
      )}
    </div>
  );
}
