import { useEffect, useState } from 'react';
import { AdminApi } from '../../api/endpoints';
import { extractErrorMessage } from '../../api/client';
import { useToast } from '../../context/ToastContext';
import { money } from '../../utils/format';
import Loader from '../../components/Loader';
import ErrorState from '../../components/ErrorState';
import PageHeader from '../../components/PageHeader';

const SEAT_CLASSES = ['FIRST_AC', 'SECOND_AC', 'THIRD_AC', 'SLEEPER', 'CHAIR_CAR', 'SECOND_SEATING'];

/** Quotas that may carry a concession. General is open to everyone and is never discounted. */
const CONCESSION_QUOTAS = ['TATKAL', 'SENIOR', 'LADIES', 'DIVYANG', 'STAFF'];

const today = () => new Date().toISOString().slice(0, 10);
const plusDays = (days) => new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);

export default function AdminFares() {
  const { showToast } = useToast();
  const [rules, setRules] = useState(null);
  const [concessions, setConcessions] = useState(null);
  const [trains, setTrains] = useState([]);
  const [stations, setStations] = useState([]);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const [savingConcession, setSavingConcession] = useState(false);
  const [form, setForm] = useState({ seatClass: 'THIRD_AC', farePerKm: '', minimumFare: '', platformFee: '', gstPercent: '' });
  const [concession, setConcession] = useState({ seatClass: 'THIRD_AC', quota: 'SENIOR', discountPercent: '', note: '' });
  const [preview, setPreview] = useState(null);
  const [previewError, setPreviewError] = useState(null);
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewForm, setPreviewForm] = useState({
    trainId: '',
    coachId: '',
    sourceStationId: '',
    destinationStationId: '',
    journeyDate: plusDays(3),
  });

  const load = () => {
    setError(null);
    Promise.all([AdminApi.fareRules(), AdminApi.quotaFares(), AdminApi.trains(), AdminApi.stations()])
      .then(([r, q, t, s]) => {
        setRules(r.data.data);
        setConcessions(q.data.data);
        setTrains(t.data.data);
        setStations(s.data.data);
      })
      .catch((err) => setError(extractErrorMessage(err)));
  };

  useEffect(load, []);

  const save = async (e) => {
    e.preventDefault();
    setSaving(true);
    try {
      await AdminApi.upsertFareRule({
        ...form,
        farePerKm: Number(form.farePerKm),
        minimumFare: Number(form.minimumFare),
        platformFee: Number(form.platformFee || 0),
        gstPercent: Number(form.gstPercent || 0),
      });
      showToast('Fare rule saved', 'success');
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  const saveConcession = async (e) => {
    e.preventDefault();
    setSavingConcession(true);
    try {
      await AdminApi.upsertQuotaFare({
        ...concession,
        discountPercent: Number(concession.discountPercent || 0),
      });
      showToast('Quota concession saved', 'success');
      load();
    } catch (err) {
      showToast(extractErrorMessage(err), 'error');
    } finally {
      setSavingConcession(false);
    }
  };

  // The preview needs a real coach, so the class list follows the selected train.
  const [coachOptions, setCoachOptions] = useState([]);
  useEffect(() => {
    if (!previewForm.trainId) {
      setCoachOptions([]);
      return;
    }
    AdminApi.coaches(previewForm.trainId)
      .then((res) => setCoachOptions(res.data.data || []))
      .catch(() => setCoachOptions([]));
  }, [previewForm.trainId]);

  const runPreview = async (e) => {
    e.preventDefault();
    setPreviewBusy(true);
    setPreviewError(null);
    setPreview(null);
    try {
      const res = await AdminApi.previewFare({
        trainId: Number(previewForm.trainId),
        coachId: Number(previewForm.coachId),
        sourceStationId: Number(previewForm.sourceStationId),
        destinationStationId: Number(previewForm.destinationStationId),
        journeyDate: previewForm.journeyDate,
        passengers: [
          { fullName: 'Preview Senior', age: 65, gender: 'FEMALE', berthPreference: 'NO_PREFERENCE', quota: 'SENIOR' },
          { fullName: 'Preview General', age: 30, gender: 'MALE', berthPreference: 'NO_PREFERENCE', quota: 'GENERAL' },
        ],
      });
      setPreview(res.data.data);
    } catch (err) {
      setPreviewError(extractErrorMessage(err));
    } finally {
      setPreviewBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;
  if (!rules || !concessions) return <Loader label="Loading fare rules..." />;

  return (
    <div>
      <PageHeader
        title="Fare Management"
        icon="rupee"
        subtitle={(
          <>Simplified, documented demo fare model. A passenger&apos;s gross fare is the higher of the coach
          base fare, distance &times; fare-per-km, or the class minimum fare. Quota concessions are then
          subtracted, and a platform fee plus GST are added on top. Bookings store this breakdown, so changing
          a rule here never alters a ticket that has already been issued. This does not represent an official
          Indian Railways tariff.</>
        )}
      />

      <h2 className="section-title" style={{ marginTop: 'var(--space-7)' }}>Tariff by class</h2>
      <div className="table-wrap" style={{ marginBottom: 24 }}>
        <table><caption className="sr-only">Fare rules by class</caption>
          <thead>
            <tr>
              <th scope="col">Class</th>
              <th scope="col" className="num">Fare per km (₹)</th>
              <th scope="col" className="num">Minimum fare (₹)</th>
              <th scope="col" className="num">Platform fee (₹)</th>
              <th scope="col" className="num">GST (%)</th>
            </tr>
          </thead>
          <tbody>
            {rules.map((r) => (
              <tr key={r.id}>
                <td>{r.seatClassLabel || r.seatClass}</td>
                <td className="num">{r.farePerKm}</td>
                <td className="num">{r.minimumFare}</td>
                <td className="num">{r.platformFee}</td>
                <td className="num">{r.gstPercent}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ maxWidth: 460 }}>
        <h3>Add / update fare rule</h3>
        <form onSubmit={save}>
          <div className="field">
            <label htmlFor="seat-class">Seat class</label>
            <select id="seat-class" value={form.seatClass} onChange={(e) => setForm((f) => ({ ...f, seatClass: e.target.value }))}>
              {SEAT_CLASSES.map((c) => <option key={c}>{c}</option>)}
            </select>
          </div>
          <div className="field"><label htmlFor="fare-per-km">Fare per km (₹)</label><input id="fare-per-km" type="number" step="0.01" value={form.farePerKm} onChange={(e) => setForm((f) => ({ ...f, farePerKm: e.target.value }))} required /></div>
          <div className="field"><label htmlFor="minimum-fare">Minimum fare (₹)</label><input id="minimum-fare" type="number" step="0.01" value={form.minimumFare} onChange={(e) => setForm((f) => ({ ...f, minimumFare: e.target.value }))} required /></div>
          <div className="field">
            <label htmlFor="platform-fee">Platform fee (₹)</label>
            <input id="platform-fee" type="number" step="0.01" value={form.platformFee} onChange={(e) => setForm((f) => ({ ...f, platformFee: e.target.value }))} required />
            <div className="field-hint">A flat convenience charge per booking, added after concessions.</div>
          </div>
          <div className="field">
            <label htmlFor="gst">GST (%)</label>
            <input id="gst" type="number" step="0.01" min="0" max="100" value={form.gstPercent} onChange={(e) => setForm((f) => ({ ...f, gstPercent: e.target.value }))} required />
            <div className="field-hint">Applied to the discounted passenger subtotal only, never to the platform fee.</div>
          </div>
          <button type="submit" className="btn btn-primary" disabled={saving}>{saving ? 'Saving...' : 'Save Fare Rule'}</button>
        </form>
      </div>

      <h2 className="section-title" style={{ marginTop: 'var(--space-7)' }}>Quota concessions</h2>
      <p className="text-muted">
        The percentage off the gross fare for a passenger who actually travels under a given quota. The general
        quota is deliberately absent: it is open to everyone and is never discounted.
      </p>
      <div className="table-wrap" style={{ marginBottom: 24 }}>
        <table><caption className="sr-only">Quota discounts</caption>
          <thead>
            <tr><th scope="col">Class</th><th scope="col">Quota</th><th scope="col" className="num">Discount (%)</th><th scope="col">Note</th></tr>
          </thead>
          <tbody>
            {concessions.length === 0 && (
              <tr><td colSpan="4" className="text-muted table-empty">No concessions configured yet.</td></tr>
            )}
            {concessions.map((c) => (
              <tr key={`${c.seatClass}-${c.quota}`}>
                <td>{c.seatClassLabel || c.seatClass}</td>
                <td>{c.quotaLabel || c.quota}</td>
                <td className="num">{c.discountPercent}</td>
                <td className="text-muted">{c.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="card" style={{ maxWidth: 460 }}>
        <h3>Add / update quota concession</h3>
        <form onSubmit={saveConcession}>
          <div className="field">
            <label htmlFor="seat-class-2">Seat class</label>
            <select id="seat-class-2" value={concession.seatClass} onChange={(e) => setConcession((c) => ({ ...c, seatClass: e.target.value }))}>
              {SEAT_CLASSES.map((c) => <option key={c}>{c}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="quota">Quota</label>
            <select id="quota" value={concession.quota} onChange={(e) => setConcession((c) => ({ ...c, quota: e.target.value }))}>
              {CONCESSION_QUOTAS.map((q) => <option key={q}>{q}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="discount">Discount (%)</label>
            <input id="discount" type="number" min="0" max="90" value={concession.discountPercent} onChange={(e) => setConcession((c) => ({ ...c, discountPercent: e.target.value }))} required />
            <div className="field-hint">0 to 90. The backend refuses anything higher.</div>
          </div>
          <div className="field">
            <label htmlFor="note-optional">Note (optional)</label>
            <input id="note-optional" type="text" maxLength="200" value={concession.note} onChange={(e) => setConcession((c) => ({ ...c, note: e.target.value }))} />
          </div>
          <button type="submit" className="btn btn-primary" disabled={savingConcession}>{savingConcession ? 'Saving...' : 'Save Concession'}</button>
        </form>
      </div>

      <h2 className="section-title" style={{ marginTop: 'var(--space-7)' }}>Fare preview</h2>
      <p className="text-muted">
        Runs the real production pricing code against a hypothetical journey, so you can see exactly what a
        change above does to an actual fare. No booking is created and no seat is held.
      </p>

      <div className="card" style={{ maxWidth: 560 }}>
        <form onSubmit={runPreview}>
          <div className="field">
            <label htmlFor="train">Train</label>
            <select id="train"
              value={previewForm.trainId}
              onChange={(e) => setPreviewForm((f) => ({ ...f, trainId: e.target.value, coachId: '' }))}
              required
            >
              <option value="">Select a train</option>
              {trains.map((t) => (
                <option key={t.id} value={t.id}>{t.trainNumber} — {t.name}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="class">Class</label>
            <select id="class"
              value={previewForm.coachId}
              onChange={(e) => setPreviewForm((f) => ({ ...f, coachId: e.target.value }))}
              required
              disabled={!previewForm.trainId}
            >
              <option value="">Select a class</option>
              {coachOptions.map((c) => (
                <option key={c.id} value={c.id}>{c.coachCode} — {c.seatClass}</option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor="from">From</label>
            <select id="from" value={previewForm.sourceStationId} onChange={(e) => setPreviewForm((f) => ({ ...f, sourceStationId: e.target.value }))} required>
              <option value="">Select a station</option>
              {stations.map((s) => <option key={s.id} value={s.id}>{s.code} — {s.name}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="to">To</label>
            <select id="to" value={previewForm.destinationStationId} onChange={(e) => setPreviewForm((f) => ({ ...f, destinationStationId: e.target.value }))} required>
              <option value="">Select a station</option>
              {stations.map((s) => <option key={s.id} value={s.id}>{s.code} — {s.name}</option>)}
            </select>
          </div>
          <div className="field">
            <label htmlFor="journey-date">Journey date</label>
            <input id="journey-date"
              type="date"
              min={today()}
              value={previewForm.journeyDate}
              onChange={(e) => setPreviewForm((f) => ({ ...f, journeyDate: e.target.value }))}
              required
            />
          </div>
          <button type="submit" className="btn btn-primary" disabled={previewBusy || !previewForm.coachId}>
            {previewBusy ? 'Calculating...' : 'Preview Fare'}
          </button>
        </form>
      </div>

      {previewError && (
        <div className="state-block" style={{ marginTop: 16 }}>
          <p className="field-error">{previewError}</p>
        </div>
      )}

      {preview && (
        <div className="card" style={{ marginTop: 20, maxWidth: 720 }}>
          <h3>Preview breakdown</h3>
          <div className="table-wrap">
            <table><caption className="sr-only">Fare preview for the selected booking</caption>
              <thead>
                <tr><th scope="col">Passenger</th><th scope="col">Quota</th><th scope="col" className="num">Gross</th><th scope="col" className="num">Discount</th><th scope="col" className="num">Payable</th></tr>
              </thead>
              <tbody>
                {preview.passengers.map((p, i) => (
                  <tr key={i}>
                    <td>{p.fullName}</td>
                    <td>{p.quotaLabel || p.quota}{p.discountPercent > 0 ? ` (${p.discountPercent}% off)` : ''}</td>
                    <td className="num">{money(p.grossFare)}</td>
                    <td className="num">{p.discountAmount > 0 ? `- ${money(p.discountAmount)}` : '—'}</td>
                    <td className="num">{money(p.payableFare)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr><td colSpan="4" className="num">Passenger subtotal</td><td className="num">{money(preview.passengerFareTotal)}</td></tr>
                <tr><td colSpan="4" className="num">Platform fee</td><td className="num">{money(preview.platformFee)}</td></tr>
                <tr><td colSpan="4" className="num">GST ({preview.gstPercent}%)</td><td className="num">{money(preview.gstAmount)}</td></tr>
                <tr><td colSpan="4" className="num"><strong>Total payable</strong></td><td className="num"><strong>{money(preview.totalPayable)}</strong></td></tr>
              </tfoot>
            </table>
          </div>
          <p className="field-hint" style={{ marginTop: 12 }}>
            Gross fare per passenger = max(coach base {money(preview.coachBaseFare)}, distance{' '}
            {preview.distanceKm} km &times; {money(preview.farePerKm)} = {money(preview.distanceFare)}, minimum{' '}
            {money(preview.minimumFare)}). The binding candidate was <strong>{preview.fareBasis}</strong>.
          </p>
          {preview.notes?.map((n, i) => <p key={i} className="field-hint">{n}</p>)}
        </div>
      )}
    </div>
  );
}
