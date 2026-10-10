import PageHeader from '../components/PageHeader';

export default function About() {
  return (
    <div className="container page" style={{ maxWidth: 760 }}>
      <PageHeader title="About YatraRail" icon="info" />
      <p>
        YatraRail is an academic and portfolio project that demonstrates a full-stack, Indian-Railway-inspired
        ticket booking and management system. It is <strong>not affiliated with, endorsed by, or connected to</strong>{' '}
        Indian Railways or IRCTC in any way. All train names, numbers, schedules and fares are fictional demo data
        created for demonstration purposes.
      </p>
      <h3>What this project demonstrates</h3>
      <p>
        A Spring Boot + PostgreSQL backend with JWT authentication and role-based authorization (Passenger, Admin,
        Railway Staff), paired with a React frontend covering train search, a multi-step booking flow with real
        seat/berth allocation and waitlisting, a mock payment simulator, cancellations with a documented demo
        refund model, notifications, and admin/staff management consoles.
      </p>
      <h3>Known simplifications</h3>
      <p>
        The booking reference generated here is a YatraRail-specific code and is not an official Indian Railways
        PNR. Payments are fully simulated — no real transactions occur and no card details are ever stored. Fare
        and refund calculations follow a simplified, documented model rather than official railway tariffs.
      </p>
    </div>
  );
}
