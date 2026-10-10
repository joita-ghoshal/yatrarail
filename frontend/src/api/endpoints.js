import api from './client';

/**
 * Idempotency keys make a retried request safe: the backend returns the original
 * booking/payment instead of creating a second one. The key is generated once per
 * user intent and reused on every retry of that same intent.
 */
export function newIdempotencyKey(prefix) {
  const rand = typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `${prefix}-${rand}`;
}

const withKey = (key) => (key ? { headers: { 'Idempotency-Key': key } } : {});

export const AuthApi = {
  register: (payload) => api.post('/auth/register', payload),
  login: (payload) => api.post('/auth/login', payload),
  changePassword: (payload) => api.post('/auth/change-password', payload),
  updateProfile: (payload) => api.put('/auth/profile', payload),
  me: () => api.get('/auth/me'),
};

/**
 * Guest one-time sessions. `create` is the only public call here; the rest act on the caller's own
 * live session and are refused by the backend once it has been ended or has expired.
 */
export const TempSessionApi = {
  create: (payload) => api.post('/auth/temporary', payload),
  session: () => api.get('/auth/temporary/session'),
  end: () => api.post('/auth/temporary/session/end'),
  convert: (payload) => api.post('/auth/temporary/convert', payload),
};

export const StationApi = {
  list: () => api.get('/stations'),
};

export const TrainApi = {
  search: (params) => api.get('/trains/search', { params }),
  /**
   * `filters` may carry { journeyDate, sourceStationId, destinationStationId, seatClass }.
   * Supplying a source/destination narrows the response to that segment, which is what
   * makes fares and availability leg-accurate instead of whole-train.
   */
  details: (trainId, filters = {}) => {
    const params = {};
    if (filters.journeyDate) params.journeyDate = filters.journeyDate;
    if (filters.sourceStationId) params.sourceStationId = filters.sourceStationId;
    if (filters.destinationStationId) params.destinationStationId = filters.destinationStationId;
    if (filters.seatClass) params.seatClass = filters.seatClass;
    return api.get(`/trains/${trainId}/details`, { params });
  },
};

export const BookingApi = {
  quote: (payload) => api.post('/bookings/quote', payload),
  create: (payload, idempotencyKey) => api.post('/bookings', payload, withKey(idempotencyKey)),
  mine: () => api.get('/bookings'),
  details: (id) => api.get(`/bookings/${id}`),
  pay: (id, payload, idempotencyKey) => api.post(`/bookings/${id}/payment`, payload, withKey(idempotencyKey)),
};

export const NotificationApi = {
  list: () => api.get('/notifications'),
  unreadCount: () => api.get('/notifications/unread-count'),
  markRead: (id) => api.patch(`/notifications/${id}/read`),
  markAllRead: () => api.patch('/notifications/read-all'),
  /**
   * Phase 9 - what the caller has opted out of. `getPreferences` writes nothing: an account that has
   * never changed a setting is served the defaults and no row is created, so this screen can be
   * opened on every visit without putting a row in the database.
   */
  getPreferences: () => api.get('/notifications/preferences'),
  updatePreferences: (payload) => api.put('/notifications/preferences', payload),
};

/**
 * Phase 5 — the passenger's printable ticket. The QR payload is signed by the backend and carries
 * only a booking reference and a signature, so nothing here ever has to sign anything: the browser
 * renders the string it is given, and the server decides whether it is genuine.
 */
export const TicketApi = {
  /** Owner-only. 404s for somebody else's booking, exactly as for one that does not exist. */
  forBooking: (bookingId) => api.get(`/tickets/bookings/${bookingId}`),
  /**
   * Always answers 200 with a verdict. A scanner pointed at a foreign barcode is an expected
   * event, not a client error, so the outcome field - not the status code - is what to read.
   * The same call backs the admin gate scanner and the passenger's own pre-travel check; who may
   * verify which ticket is decided by the role on the token, not by anything in this request.
   */
  verify: (payload) => api.post('/tickets/verify', { payload }),
};

/**
 * Phase 5 — the passenger's own money.
 * `preview` is read-only, so the cancellation modal can quote a real figure before the passenger
 * commits to anything; `cancelPassengers` is what actually moves seats and writes the refund rows.
 */
export const PaymentApi = {
  history: () => api.get('/payments'),
  refunds: () => api.get('/refunds'),
  /**
   * @param passengerIds BookingPassenger ids, not row indexes. Omitting them quotes the whole
   *                     booking, which is what an untouched "cancel this booking" button means.
   */
  refundPreview: (bookingId, passengerIds) =>
    api.get(`/bookings/${bookingId}/refund-preview`, {
      params: passengerIds && passengerIds.length ? { passengerIds } : undefined,
    }),
  cancelPassengers: (bookingId, passengerIds, reason) =>
    api.post(`/bookings/${bookingId}/refunds`, { passengerIds, reason }),
};

export const AdminApi = {
  dashboard: () => api.get('/admin/dashboard'),

  /**
   * Phase 7 — accounts are now paginated and filterable server-side. The old call returned every
   * account as a bare array; `content` inside the page envelope is the row list now.
   *
   * @param {{search?: string, role?: string, active?: boolean, page?: number, size?: number}} params
   */
  users: (params) => api.get('/admin/users', { params }),
  createUser: (payload) => api.post('/admin/users', payload),
  updateUser: (id, payload) => api.put(`/admin/users/${id}`, payload),
  setUserActive: (id, active) => api.patch(`/admin/users/${id}/active`, null, { params: { active } }),

  stations: () => api.get('/admin/stations'),
  createStation: (payload) => api.post('/admin/stations', payload),
  updateStation: (id, payload) => api.put(`/admin/stations/${id}`, payload),
  setStationActive: (id, active) => api.patch(`/admin/stations/${id}/active`, null, { params: { active } }),

  trains: () => api.get('/admin/trains'),
  createTrain: (payload) => api.post('/admin/trains', payload),
  updateTrain: (id, payload) => api.put(`/admin/trains/${id}`, payload),
  setTrainActive: (id, active) => api.patch(`/admin/trains/${id}/active`, null, { params: { active } }),
  deleteTrain: (id) => api.delete(`/admin/trains/${id}`),

  /**
   * Phase 7 — the stop list a train was created with, readable and editable at last. `updateTrainRoute`
   * replaces the whole list in one call, which keeps sequence numbers consistent.
   */
  trainRoute: (id) => api.get(`/admin/trains/${id}/route`),
  updateTrainRoute: (id, route) => api.put(`/admin/trains/${id}/route`, route),

  coaches: (trainId) => api.get(`/admin/trains/${trainId}/coaches`),
  addCoach: (trainId, payload) => api.post(`/admin/trains/${trainId}/coaches`, payload),
  updateCoach: (id, payload) => api.put(`/admin/coaches/${id}`, payload),
  setCoachActive: (id, active) => api.patch(`/admin/coaches/${id}/active`, null, { params: { active } }),

  fareRules: () => api.get('/admin/fare-rules'),
  upsertFareRule: (payload) => api.post('/admin/fare-rules', payload),
  /**
   * Prices a hypothetical journey with the real production pricing code, so an admin can see what
   * a tariff or quota change does to an actual fare. Creates no booking and holds no seat.
   */
  previewFare: (payload) => api.post('/admin/fare-preview', payload),

  quotaConfigs: () => api.get('/admin/quotas'),
  quotaConfig: (trainId, seatClass) => api.get(`/admin/quotas/${trainId}/${seatClass}`),
  upsertQuotaConfig: (payload) => api.post('/admin/quotas', payload),

  quotaFares: () => api.get('/admin/quota-fares'),
  upsertQuotaFare: (payload) => api.post('/admin/quota-fares', payload),

  bookings: (params) => api.get('/admin/bookings', { params }),

  /**
   * Phase 5 — the raw payment ledger, reconciled against bookings, plus the
   * money the platform still owes back. Money out is shown as a separate list
   * because a pending refund is not yet a loss and must not be silently
   * netted off against money in.
   */
  payments: (params) => api.get('/admin/payments', { params }),
  reconciliation: (params) => api.get('/admin/payments/reconciliation', { params }),
  refunds: (params) => api.get('/admin/refunds', { params }),

  /**
   * Phase 9 — publishing a notice, and reading back every notice with how many accounts it reached
   * and how many have actually read it. The second call exists because an announcement is one
   * authored row: without a per-reader count, "did anybody see this?" was unanswerable.
   */
  announce: (payload) => api.post('/admin/announcements', payload),
  announcements: () => api.get('/admin/announcements'),

  /**
   * Phase 7 — the audit trail. Read-only by design: the console can show it and filter it, but there
   * is no create/update/delete method here because the API has none.
   */
  auditLogs: (params) => api.get('/admin/audit-logs', { params }),
  auditActions: () => api.get('/admin/audit-logs/actions'),

  /** Phase 7 — the operations report for a journey-date window, and the refund-state summary. */
  operationsReport: (params) => api.get('/admin/reports/operations', { params }),
  refundReport: () => api.get('/admin/reports/refunds'),
};

/**
 * Per-quota availability for one (train, class) on one journey date. The backend already refuses a
 * passenger who is not entitled to a quota, so the UI uses this to explain *why* a quota is
 * unavailable rather than to decide eligibility.
 */
export const QuotaApi = {
  availability: (coachId, journeyDate) =>
    api.get(`/quotas/availability/${coachId}`, { params: { journeyDate } }),
};

export const StaffApi = {
  trains: () => api.get('/staff/trains'),
  passengers: (trainId, journeyDate) => api.get(`/staff/trains/${trainId}/passengers`, { params: { journeyDate } }),
  dashboard: (journeyDate) => api.get('/staff/dashboard', { params: { journeyDate } }),

  /**
   * Gate check-in. This records who is on the train and nothing more: it deliberately does not
   * refund or cancel, so a mis-scan at a busy platform can never move money.
   */
  markBoarding: (passengerId, status) => api.patch(`/staff/passengers/${passengerId}/boarding`, { status }),

  incidents: (params) => api.get('/staff/incidents', { params }),
  reportIncident: (payload) => api.post('/staff/incidents', payload),
  updateIncident: (id, status) => api.patch(`/staff/incidents/${id}`, { status }),

  assistance: (params) => api.get('/staff/assistance', { params }),
  updateAssistance: (id, status) => api.patch(`/staff/assistance/${id}`, { status }),
};

/** A passenger asking railway staff for help on one of their own passengers. */
export const AssistanceApi = {
  raise: (bookingId, payload) => api.post(`/bookings/${bookingId}/assistance-requests`, payload),
  forBooking: (bookingId) => api.get(`/bookings/${bookingId}/assistance-requests`),
};
