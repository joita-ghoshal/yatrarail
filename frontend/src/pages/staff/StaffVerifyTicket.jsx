import PageHeader from '../../components/PageHeader';
import TicketVerifier from '../../components/TicketVerifier';

/**
 * The staff portal's gate scanner. Deliberately the same component the admin console uses, so a
 * conductor and an auditor can never be shown different verdicts for the same ticket code.
 */
export default function StaffVerifyTicket() {
  return (
    <div>
      <PageHeader
        title="Verify Ticket"
        subtitle="Scan a passenger's QR code, or paste the code printed under it. A valid code is checked against the live booking, so a cancelled or edited ticket is rejected here."
        icon="scan"
      />
      <TicketVerifier />
    </div>
  );
}
