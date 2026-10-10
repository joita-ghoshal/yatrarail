/**
 * How a gate should read each verification outcome.
 *
 * The split that matters is not "good vs bad" but "ask the passenger vs refuse them": a stale or
 * not-yet-issued code is usually a reprint or an early scanner, so the right move is to reissue and
 * look again, while a forged or unknown booking must be refused outright.
 *
 * The keys below are the values the server actually sends in `outcome`, NOT the Java constant
 * names they are declared with. A key that names a constant instead of its value matches nothing,
 * every scan falls through to UNKNOWN_VERDICT, and a genuinely valid ticket is announced as an
 * unrecognised one -- the worst possible failure for a screen a gate acts on.
 */
const VERDICT_BY_OUTCOME = {
  VALID: { notice: 'notice-good', headline: 'Valid ticket', action: 'Let the passenger travel.' },
  CANCELLED: { notice: 'notice-warn', headline: 'Booking cancelled', action: 'Refuse. Ask the passenger to rebook.' },
  UNPAID: { notice: 'notice-warn', headline: 'Never paid', action: 'Refuse. Send to the booking desk.' },
  FORGED: { notice: 'notice-warn', headline: 'Signature does not match', action: 'Refuse and escalate to security.' },
  UNKNOWN_BOOKING: { notice: 'notice-warn', headline: 'No such booking', action: 'Refuse. Treat as a forged code.' },
  MALFORMED: { notice: 'notice-warn', headline: 'Not a YatraRail code', action: 'Refuse. Ask for a printed ticket.' },
  QR_STALE: { notice: 'notice-info', headline: 'Expired code', action: 'Reprint the ticket and scan again.' },
  NOT_YET_ISSUED: { notice: 'notice-info', headline: 'Scan window has not opened', action: 'Too early to scan. Check again nearer departure.' },
  NOT_JOURNEY_DAY: { notice: 'notice-info', headline: 'Wrong travel date', action: 'Ask for the ticket for this journey.' },
  EXPIRED: { notice: 'notice-info', headline: 'Booking expired', action: 'Refuse. Seats were released.' },
};

const UNKNOWN_VERDICT = { notice: 'notice-warn', headline: 'Unrecognised result', action: 'Treat as invalid.' };

export function verdictFor(outcome) {
  return VERDICT_BY_OUTCOME[outcome] || UNKNOWN_VERDICT;
}

/** Short label for one payment attempt, in the same shape the status badges already use. */
export const ATTEMPT_STATUS_LABEL = {
  SUCCESS: 'Paid',
  FAILED: 'Failed',
  INITIATED: 'Pending',
};

export const REFUND_STATUS_LABEL = {
  PROCESSED: 'Refunded',
  PENDING: 'Refund pending',
  FAILED: 'Refund failed',
};
