import { useState } from 'react';
import { DEMO_MODE } from '../demo/demoMode';

export default function DemoBanner() {
  const [closed, setClosed] = useState(false);
  if (!DEMO_MODE || closed) return null;

  return (
    <div
      role="status"
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 12,
        flexWrap: 'wrap',
        padding: '8px 16px',
        fontSize: '0.86rem',
        background: 'var(--color-orange, #c1440e)',
        color: '#fff',
        textAlign: 'center',
      }}
    >
      <span>
        Demo build &mdash; running entirely in your browser on sample data. No server, no database.
        Searches, bookings, payments, refunds and ticket checks are simulated and reset when you
        clear browser storage.
      </span>
      <button
        type="button"
        onClick={() => setClosed(true)}
        aria-label="Dismiss demo notice"
        style={{ background: 'transparent', border: '1px solid rgba(255,255,255,0.6)', color: '#fff', borderRadius: 4, padding: '2px 10px', cursor: 'pointer' }}
      >
        Dismiss
      </button>
    </div>
  );
}
