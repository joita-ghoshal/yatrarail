import PageHeader from '../../components/PageHeader';
import TicketVerifier from '../../components/TicketVerifier';

/**
 * The admin console's ticket verification screen. All the behaviour lives in the shared verifier so
 * that staff and admin see the same verdict for the same code — a difference here would be a
 * security bug, not a cosmetic one.
 */
export default function AdminVerifyTicket() {
  return (
    <div>
      <PageHeader
        title="Ticket Verification"
        subtitle="Scan a passenger's QR code, or paste the code printed under it. The signature is checked against the live booking, so a reprinted or edited code is caught here."
        icon="scan"
      />
      <TicketVerifier />
    </div>
  );
}
