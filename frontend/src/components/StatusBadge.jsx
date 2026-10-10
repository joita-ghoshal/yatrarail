import { PASSENGER_STATUS_LABEL } from '../utils/format';

const MAP = {
  CONFIRMED: 'badge-confirmed',
  SUCCESS: 'badge-success',
  RAC: 'badge-rac',
  WAITLISTED: 'badge-waitlisted',
  EXPIRED: 'badge-expired',
  PAYMENT_PENDING: 'badge-pending',
  PENDING: 'badge-pending',
  CANCELLED: 'badge-cancelled',
  PAYMENT_FAILED: 'badge-failed',
  FAILED: 'badge-failed',
  INITIATED: 'badge-pending',
  // Phase 6 gate check-in states.
  NOT_BOARDED: 'badge-neutral',
  BOARDED: 'badge-confirmed',
  NO_SHOW: 'badge-cancelled',
  // Phase 6 incident / assistance states.
  OPEN: 'badge-pending',
  ACKNOWLEDGED: 'badge-rac',
  RESOLVED: 'badge-confirmed',
};

export default function StatusBadge({ status, passenger = false }) {
  if (!status) return null;
  const cls = MAP[status] || 'badge-neutral';
  const label = passenger
    ? (PASSENGER_STATUS_LABEL[status] || status.replace(/_/g, ' '))
    : (status === 'PAYMENT_PENDING' ? 'Payment pending' : status.replace(/_/g, ' '));
  return <span className={`badge ${cls}`}>{label}</span>;
}
