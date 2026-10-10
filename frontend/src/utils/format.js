const TONE_BY_STATUS = {
  AVAILABLE: 'green',
  RAC_AVAILABLE: 'orange',
  WAITLIST: 'orange',
};

const LABEL_BY_STATUS = {
  AVAILABLE: (c) => `${c.availableSeats} available`,
  RAC_AVAILABLE: (c) => (c.availableSeats > 0 ? `${c.availableSeats} available · RAC open` : 'RAC open'),
  WAITLIST: () => 'Waitlist only',
};

/** Human label + colour tone for one class's live availability. */
export function availabilityOf(classAvailability) {
  const status = classAvailability?.status || 'WAITLIST';
  const label = (LABEL_BY_STATUS[status] || LABEL_BY_STATUS.WAITLIST)(classAvailability || {});
  return { status, label, tone: TONE_BY_STATUS[status] || 'orange' };
}

const BOOKING_STATUS_LABEL = {
  PAYMENT_PENDING: 'Payment pending',
  PAYMENT_FAILED: 'Payment failed',
  CONFIRMED: 'Confirmed',
  RAC: 'RAC',
  WAITLISTED: 'Waitlisted',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

export const PASSENGER_STATUS_LABEL = {
  CONFIRMED: 'Confirmed',
  RAC: 'RAC',
  WAITLISTED: 'Waitlisted',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
};

export function statusLabel(status, passenger = false) {
  if (!status) return '';
  const table = passenger ? PASSENGER_STATUS_LABEL : BOOKING_STATUS_LABEL;
  return table[status] || status.replace(/_/g, ' ');
}

export const money = (value) => `₹${Number(value ?? 0).toFixed(2)}`;

/** "9m 58s" style countdown; clamped at zero so it never shows a negative time. */
export function formatRemaining(millis) {
  const total = Math.max(0, Math.floor(millis / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  if (minutes >= 60) {
    const hours = Math.floor(minutes / 60);
    return `${hours}h ${minutes % 60}m`;
  }
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
}
