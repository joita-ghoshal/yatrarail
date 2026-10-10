import { money } from '../utils/format';

const BASIS_LABEL = {
  COACH_BASE: 'coach base fare',
  DISTANCE: 'distance × fare per km',
  MINIMUM: 'class minimum fare',
};

/**
 * The itemised fare, rendered from whatever the backend returned.
 *
 * This component is deliberately dumb: it never computes a fare. The numbers, the quota each
 * passenger actually got, and the total all come from the server, because a client-side fare
 * calculation is exactly the kind of thing that drifts from the amount charged. It is also used
 * for both the pre-booking quote and the stored breakdown on a past ticket, which look identical
 * here precisely because a ticket must keep showing what it was priced at.
 */
export default function FareBreakdown({ breakdown, title = 'Fare breakdown', compact = false }) {
  if (!breakdown) return null;

  const b = breakdown;

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <h3>{title}</h3>

      <div className="table-wrap">
        <table><caption className="sr-only">Fare split for each passenger</caption>
          <thead>
            <tr>
              <th scope="col">Passenger</th>
              <th scope="col">Quota</th>
              <th scope="col">Gross</th>
              <th scope="col">Discount</th>
              <th scope="col">Payable</th>
            </tr>
          </thead>
          <tbody>
            {b.passengers?.map((p, i) => (
              <tr key={`${p.fullName}-${i}`}>
                <td>{p.fullName}</td>
                <td>
                  {p.quotaLabel || p.quota}
                  {p.discountPercent > 0 && (
                    <span className="text-muted text-xs"> ({p.discountPercent}% off)</span>
                  )}
                  {p.quotaNote && (
                    <div className="field-hint" style={{ marginTop: 2 }}>{p.quotaNote}</div>
                  )}
                </td>
                <td>{money(p.grossFare)}</td>
                <td>{p.discountAmount > 0 ? `- ${money(p.discountAmount)}` : '—'}</td>
                <td>{money(p.payableFare)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan="4" className="num">Passenger subtotal</td>
              <td>{money(b.passengerFareTotal)}</td>
            </tr>
            <tr>
              <td colSpan="4" className="num">Platform fee</td>
              <td>{money(b.platformFee)}</td>
            </tr>
            <tr>
              <td colSpan="4" className="num">GST ({b.gstPercent}%)</td>
              <td>{money(b.gstAmount)}</td>
            </tr>
            <tr>
              <td colSpan="4" className="num"><strong>Total payable</strong></td>
              <td><strong className="text-orange">{money(b.totalPayable)}</strong></td>
            </tr>
          </tfoot>
        </table>
      </div>

      {!compact && (
        <p className="field-hint" style={{ marginTop: 12 }}>
          Gross fare per passenger is the highest of: coach base {money(b.coachBaseFare)},{' '}
          {b.distanceKm} km &times; {money(b.farePerKm)} = {money(b.distanceFare)}, and the class minimum{' '}
          {money(b.minimumFare)}. For this booking the binding one was{' '}
          <strong>{BASIS_LABEL[b.fareBasis] || b.fareBasis}</strong>. GST is charged on the discounted
          passenger subtotal only, never on the platform fee.
        </p>
      )}

      {b.notes?.map((n, i) => <p key={i} className="field-hint">{n}</p>)}
    </div>
  );
}
