import fixtures from './fixtures.json';

let idSeq = 5000;
const nextId = () => (idSeq += 1);
const clone = (v) => JSON.parse(JSON.stringify(v));
const round2 = (n) => Math.round(n * 100) / 100;
const nowIso = () => new Date().toISOString();
const plusMinutes = (m) => new Date(Date.now() + m * 60000).toISOString();

const ok = (data, message = 'OK') => ({ success: true, message, data });

const QUOTA_LABELS = {
  GENERAL: 'General',
  TATKAL: 'Tatkal (simulated)',
  SENIOR: 'Senior Citizen',
  LADIES: 'Ladies',
  DIVYANG: 'Divyang',
  STAFF: 'Staff Quota',
};

const store = {
  get(key, fallback) {
    try {
      const raw = localStorage.getItem(key);
      return raw == null ? clone(fallback) : JSON.parse(raw);
    } catch {
      return clone(fallback);
    }
  },
  set(key, value) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      /* storage full or blocked; demo still works in-memory for this call */
    }
  },
  del(key) {
    try {
      localStorage.removeItem(key);
    } catch {
      /* ignore */
    }
  },
};

const list = (name, seed) => store.get(`yrdemo_${name}`, seed);

function roleFromEmail(email) {
  const e = String(email || '').toLowerCase();
  if (e.includes('admin')) return 'ADMIN';
  if (e.includes('staff')) return 'STAFF';
  return 'PASSENGER';
}

function profileForRole(role) {
  if (role === 'ADMIN') return clone(fixtures.me_admin);
  if (role === 'STAFF') return clone(fixtures.me_staff);
  return clone(fixtures.me_pax);
}

function peopleFrom(req) {
  return req.passengerDetails || req.passengers || [];
}

function buildQuote(req = {}) {
  const base = clone(fixtures.quote);
  const people = peopleFrom(req);
  const n = Math.max(1, people.length || 1);
  const per = base.farePerPassenger;
  const fb = base.fareBreakdown;
  const gst = round2((per * n * fb.gstPercent) / 100);
  base.passengers = n;
  base.totalFare = round2(per * n + fb.platformFee + gst);
  base.wouldConfirm = n;
  base.wouldRac = 0;
  base.wouldWaitlist = 0;
  if (req.journeyDate) base.journeyDate = req.journeyDate;
  if (req.trainId) base.trainId = req.trainId;
  base.fareBreakdown = {
    ...fb,
    passengers: people.map((p) => ({
      fullName: p.fullName,
      age: p.age,
      gender: p.gender,
      quota: p.quota || 'GENERAL',
      quotaLabel: QUOTA_LABELS[p.quota] || 'General',
      discountPercent: 0,
      grossFare: per,
      discountAmount: 0,
      payableFare: per,
      quotaNote: null,
    })),
    grossFareTotal: round2(per * n),
    passengerFareTotal: round2(per * n),
    gstAmount: gst,
    totalPayable: base.totalFare,
  };
  return base;
}

function buildPassengers(people, coachCode, fare) {
  return people.map((p, i) => ({
    id: 1000 + i,
    fullName: p.fullName,
    age: p.age,
    gender: p.gender,
    berthPreference: p.berthPreference || null,
    coachCode: coachCode || 'B1',
    seatNumber: 1 + i,
    berthType: ['LOWER', 'MIDDLE', 'UPPER'][i % 3],
    passengerStatus: 'CONFIRMED',
    waitlistPosition: null,
    rac: false,
    quota: p.quota || 'GENERAL',
    quotaLabel: QUOTA_LABELS[p.quota] || 'General',
    quotaDiscountPercent: 0,
    fareAmount: fare,
    quotaNote: null,
  }));
}

function buildBooking(req = {}) {
  const quote = buildQuote(req);
  const people = peopleFrom(req);
  const id = nextId();
  const booking = clone(fixtures.booking_created);
  booking.id = id;
  booking.bookingReference = `YRDEMO${String(id).slice(-4)}`;
  booking.journeyDate = req.journeyDate || quote.journeyDate;
  booking.status = 'PAYMENT_PENDING';
  booking.createdAt = nowIso();
  booking.paymentDeadline = plusMinutes(30);
  booking.payment = null;
  booking.totalFare = quote.totalFare;
  booking.distanceKm = quote.distanceKm;
  booking.farePerPassenger = quote.farePerPassenger;
  booking.fareBreakdown = quote.fareBreakdown;
  booking.passengers = buildPassengers(people.length ? people : [{ fullName: 'Guest Traveller', age: 30, gender: 'Male', quota: 'GENERAL' }], req.coachCode || 'B1', quote.farePerPassenger);
  const existing = list('bookings', fixtures.bookings_pax);
  store.set('yrdemo_bookings', [booking, ...existing]);
  return booking;
}

function findBooking(id) {
  const all = list('bookings', fixtures.bookings_pax);
  const numId = Number(id);
  const found = all.find((b) => Number(b.id) === numId);
  if (found) return { booking: found, index: all.indexOf(found), all };
  const fallback = clone(fixtures.booking_details);
  fallback.id = numId;
  return { booking: fallback, index: -1, all };
}

function ticketFor(bookingId) {
  const { booking } = findBooking(bookingId);
  const t = clone(fixtures.ticket);
  t.bookingId = booking.id;
  t.bookingReference = booking.bookingReference || t.bookingReference;
  t.journeyDate = booking.journeyDate || t.journeyDate;
  t.bookingStatus = booking.status || t.bookingStatus;
  return t;
}

function verifyResult(payload) {
  const t = clone(fixtures.ticket);
  const booking = list('bookings', fixtures.bookings_pax).find((b) => b.bookingReference && String(payload).includes(b.bookingReference));
  return {
    outcome: 'VALID',
    message: 'The code is genuine and the booking is paid and confirmed.',
    verifiedAt: nowIso(),
    bookingReference: booking?.bookingReference || t.bookingReference,
    trainName: t.trainName,
    trainNumber: t.trainNumber,
    sourceStationName: t.sourceStationName,
    destinationStationName: t.destinationStationName,
    journeyDate: booking?.journeyDate || t.journeyDate,
    seatClass: t.seatClass,
    bookingStatus: booking?.status || 'CONFIRMED',
    amountPaid: t.fareBreakdown?.totalPayable ?? t.fareBreakdown?.passengerFareTotal ?? null,
    refundedAmount: 0,
    bookedByName: t.bookedByName,
    passengers: t.passengers,
  };
}

function upsertBy(listName, seed, matchKey, payload) {
  const arr = list(listName, seed);
  const idx = arr.findIndex((r) => matchKey(r, payload));
  if (idx >= 0) {
    arr[idx] = { ...arr[idx], ...clone(payload) };
  } else {
    arr.push({ id: nextId(), ...clone(payload), active: payload.active !== false });
  }
  store.set(`yrdemo_${listName}`, arr);
  return idx >= 0 ? arr[idx] : arr[arr.length - 1];
}

function toggleActive(listName, seed, id, active) {
  const arr = list(listName, seed);
  const idx = arr.findIndex((r) => Number(r.id) === Number(id));
  if (idx >= 0) {
    arr[idx] = { ...arr[idx], active: active === true || active === 'true' };
    store.set(`yrdemo_${listName}`, arr);
    return arr[idx];
  }
  return null;
}

function createIn(listName, seed, payload) {
  const arr = list(listName, seed);
  const row = { id: nextId(), ...clone(payload) };
  arr.push(row);
  store.set(`yrdemo_${listName}`, arr);
  return row;
}

const RULES = [
  ['post', /^\/auth\/login$/, (_p, body) => {
    const role = roleFromEmail(body.email);
    const profile = profileForRole(role);
    if (body.email) profile.email = body.email;
    const local = String(body.email || '').split('@')[0];
    if (local && local !== 'admin' && local !== 'staff' && local !== 'passenger') {
      profile.fullName = local.replace(/[._-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
    }
    store.set('yrdemo_user', profile);
    return ok({ token: `demo-token-${role.toLowerCase()}`, userId: profile.id, fullName: profile.fullName, email: profile.email, role: profile.role });
  }],
  ['post', /^\/auth\/register$/, (_p, body) => {
    const profile = { id: nextId(), fullName: body.fullName || 'New Passenger', email: body.email, phone: body.phone || null, gender: body.gender || null, age: body.age || null, role: 'PASSENGER', active: true, temporaryAccount: false, sessionExpiresAt: null, createdAt: nowIso() };
    store.set('yrdemo_user', profile);
    return ok({ token: 'demo-token-passenger', userId: profile.id, fullName: profile.fullName, email: profile.email, role: profile.role });
  }],
  ['post', /^\/auth\/change-password$/, () => ok(null, 'Password changed (demo)')],
  ['put', /^\/auth\/profile$/, (_p, body) => {
    const current = store.get('yrdemo_user', fixtures.me_pax);
    const next = { ...current, ...clone(body) };
    store.set('yrdemo_user', next);
    return ok(next);
  }],
  ['get', /^\/auth\/me$/, () => ok(store.get('yrdemo_user', fixtures.me_pax))],

  ['post', /^\/auth\/temporary$/, (_p, body) => {
    const userId = nextId();
    const session = { userId, fullName: body.fullName || 'Guest Traveller', email: body.email || 'guest@yatrarail.demo', role: 'TEMP_PASSENGER', expiresAt: plusMinutes(30), expiresInSeconds: 1800 };
    store.set('yrdemo_user', { id: userId, fullName: session.fullName, email: session.email, phone: null, gender: null, age: null, role: 'TEMP_PASSENGER', active: true, temporaryAccount: true, sessionExpiresAt: session.expiresAt, createdAt: nowIso() });
    return ok({ token: 'demo-guest-token', session });
  }],
  ['get', /^\/auth\/temporary\/session$/, () => ok({ sessionStatus: 'ACTIVE', expiresInSeconds: 1800 })],
  ['post', /^\/auth\/temporary\/session\/end$/, () => ok(null)],
  ['post', /^\/auth\/temporary\/convert$/, () => {
    const current = store.get('yrdemo_user', fixtures.me_pax);
    const next = { ...current, role: 'PASSENGER', temporaryAccount: false, sessionExpiresAt: null };
    store.set('yrdemo_user', next);
    return ok({ userId: next.id, fullName: next.fullName, email: next.email, role: 'PASSENGER' });
  }],

  ['get', /^\/stations$/, () => ok(fixtures.stations)],
  ['get', /^\/trains\/search$/, () => ok(fixtures.search)],
  ['get', /^\/trains\/([^/]+)\/details$/, (m) => ok(fixtures.details[m[1]] || Object.values(fixtures.details)[0])],
  ['get', /^\/quotas\/availability\/([^/]+)$/, () => ok(fixtures.quota_availability)],

  ['post', /^\/bookings\/quote$/, (_p, body) => ok(buildQuote(body))],
  ['post', /^\/bookings$/, (_p, body) => ok(buildBooking(body), 'Booking created (demo)')],
  ['get', /^\/bookings$/, () => ok(list('bookings', fixtures.bookings_pax))],
  ['get', /^\/bookings\/([^/]+)\/refund-preview$/, (m) => {
    const preview = clone(fixtures.refund_preview);
    preview.bookingId = Number(m[1]);
    return ok(preview);
  }],
  ['post', /^\/bookings\/([^/]+)\/refunds$/, (m) => {
    const { booking, index, all } = findBooking(m[1]);
    booking.status = 'CANCELLED';
    if (index >= 0) {
      all[index] = booking;
      store.set('yrdemo_bookings', all);
    }
    return ok({ id: nextId(), bookingId: booking.id, status: 'PROCESSED', totalRefund: fixtures.refund_preview.totalRefund, createdAt: nowIso() }, 'Refund processed (demo)');
  }],
  ['post', /^\/bookings\/([^/]+)\/payment$/, (m, _b, _params, reqBody) => {
    const { booking, index, all } = findBooking(m[1]);
    const success = !(reqBody && reqBody.simulateSuccess === false);
    booking.status = success ? 'CONFIRMED' : 'PAYMENT_FAILED';
    booking.paymentDeadline = null;
    booking.payment = { id: nextId(), method: reqBody?.method || 'UPI', status: success ? 'SUCCESS' : 'FAILED', amount: booking.totalFare, createdAt: nowIso() };
    if (index >= 0) {
      all[index] = booking;
      store.set('yrdemo_bookings', all);
    }
    return ok(booking, success ? 'Payment successful (demo)' : 'Payment failed (demo)');
  }],
  ['get', /^\/bookings\/([^/]+)$/, (m) => ok(findBooking(m[1]).booking)],
  ['post', /^\/bookings\/([^/]+)\/assistance-requests$/, (m, body) => {
    const row = { id: nextId(), bookingId: Number(m[1]), status: 'OPEN', category: body.category, message: body.message, createdAt: nowIso() };
    const arr = list('assistance', fixtures.staff_assistance);
    store.set('yrdemo_assistance', [row, ...arr]);
    return ok(row, 'Request sent (demo)');
  }],
  ['get', /^\/bookings\/([^/]+)\/assistance-requests$/, (m) => ok(list('assistance', fixtures.staff_assistance).filter((a) => Number(a.bookingId) === Number(m[1])))],

  ['get', /^\/notifications\/unread-count$/, () => {
    const arr = list('notifications', fixtures.notifications);
    return ok({ count: arr.filter((n) => !n.isRead).length });
  }],
  ['get', /^\/notifications$/, () => ok(list('notifications', fixtures.notifications))],
  ['patch', /^\/notifications\/read-all$/, () => {
    const arr = list('notifications', fixtures.notifications).map((n) => ({ ...n, isRead: true }));
    store.set('yrdemo_notifications', arr);
    return ok(null);
  }],
  ['patch', /^\/notifications\/([^/]+)\/read$/, (m) => {
    const arr = list('notifications', fixtures.notifications).map((n) => (Number(n.id) === Number(m[1]) ? { ...n, isRead: true } : n));
    store.set('yrdemo_notifications', arr);
    return ok(null);
  }],
  ['get', /^\/notifications\/preferences$/, () => ok(store.get('yrdemo_notif_prefs', fixtures.notif_prefs))],
  ['put', /^\/notifications\/preferences$/, (_p, body) => {
    const next = { ...store.get('yrdemo_notif_prefs', fixtures.notif_prefs), ...clone(body) };
    store.set('yrdemo_notif_prefs', next);
    return ok(next);
  }],

  ['get', /^\/payments$/, () => ok(fixtures.payments_pax)],
  ['get', /^\/refunds$/, () => ok(list('refunds', fixtures.refunds_pax))],

  ['get', /^\/tickets\/bookings\/([^/]+)$/, (m) => ok(ticketFor(m[1]))],
  ['post', /^\/tickets\/verify$/, (_p, body) => ok(verifyResult(body?.payload))],

  ['get', /^\/admin\/dashboard$/, () => ok(fixtures.admin_dashboard)],
  ['get', /^\/admin\/users$/, () => ok(fixtures.admin_users)],
  ['post', /^\/admin\/users$/, (_p, body) => ok({ id: nextId(), ...clone(body) }, 'User created (demo)')],
  ['put', /^\/admin\/users\/([^/]+)$/, (_p, body) => ok({ ...clone(body) }, 'User updated (demo)')],
  ['patch', /^\/admin\/users\/([^/]+)\/active$/, () => ok(null)],

  ['get', /^\/admin\/stations$/, () => ok(list('admin_stations', fixtures.admin_stations))],
  ['post', /^\/admin\/stations$/, (_p, body) => ok(createIn('admin_stations', fixtures.admin_stations, { ...body, active: body.active !== false }), 'Station created (demo)')],
  ['put', /^\/admin\/stations\/([^/]+)$/, (m, body) => ok(upsertBy('admin_stations', fixtures.admin_stations, (r, p) => Number(r.id) === Number(m[1]), body), 'Station updated (demo)')],
  ['patch', /^\/admin\/stations\/([^/]+)\/active$/, (m, _b, params) => ok(toggleActive('admin_stations', fixtures.admin_stations, m[1], params.active))],

  ['get', /^\/admin\/trains$/, () => ok(list('admin_trains', fixtures.admin_trains))],
  ['post', /^\/admin\/trains$/, (_p, body) => ok(createIn('admin_trains', fixtures.admin_trains, { ...body, active: body.active !== false }), 'Train created (demo)')],
  ['get', /^\/admin\/trains\/([^/]+)\/route$/, () => ok([])],
  ['put', /^\/admin\/trains\/([^/]+)\/route$/, () => ok(null, 'Route updated (demo)')],
  ['get', /^\/admin\/trains\/([^/]+)\/coaches$/, () => ok([])],
  ['post', /^\/admin\/trains\/([^/]+)\/coaches$/, (_p, body) => ok({ id: nextId(), ...clone(body) }, 'Coach added (demo)')],
  ['put', /^\/admin\/trains\/([^/]+)$/, (m, body) => ok(upsertBy('admin_trains', fixtures.admin_trains, (r, p) => Number(r.id) === Number(m[1]), body), 'Train updated (demo)')],
  ['patch', /^\/admin\/trains\/([^/]+)\/active$/, (m, _b, params) => ok(toggleActive('admin_trains', fixtures.admin_trains, m[1], params.active))],
  ['delete', /^\/admin\/trains\/([^/]+)$/, (m) => {
    const arr = list('admin_trains', fixtures.admin_trains).filter((t) => Number(t.id) !== Number(m[1]));
    store.set('yrdemo_admin_trains', arr);
    return ok(null, 'Train removed (demo)');
  }],
  ['put', /^\/admin\/coaches\/([^/]+)$/, (_p, body) => ok({ ...clone(body) }, 'Coach updated (demo)')],
  ['patch', /^\/admin\/coaches\/([^/]+)\/active$/, () => ok(null)],

  ['get', /^\/admin\/fare-rules$/, () => ok(list('admin_fares', fixtures.admin_fares))],
  ['post', /^\/admin\/fare-rules$/, (_p, body) => ok(upsertBy('admin_fares', fixtures.admin_fares, (r, p) => r.seatClass === p.seatClass, body), 'Fare rule saved (demo)')],
  ['post', /^\/admin\/fare-preview$/, (_p, body) => ok(buildQuote(body))],

  ['get', /^\/admin\/quotas$/, () => ok(list('admin_quotas', fixtures.admin_quotas))],
  ['get', /^\/admin\/quotas\/([^/]+)\/([^/]+)$/, (m) => {
    const arr = list('admin_quotas', fixtures.admin_quotas);
    return ok(arr.find((q) => Number(q.trainId) === Number(m[1]) && q.seatClass === m[2]) || arr[0]);
  }],
  ['post', /^\/admin\/quotas$/, (_p, body) => ok(upsertBy('admin_quotas', fixtures.admin_quotas, (r, p) => Number(r.trainId) === Number(p.trainId) && r.seatClass === p.seatClass, body), 'Quota saved (demo)')],
  ['get', /^\/admin\/quota-fares$/, () => ok(list('admin_quota_fares', fixtures.admin_quota_fares))],
  ['post', /^\/admin\/quota-fares$/, (_p, body) => ok(upsertBy('admin_quota_fares', fixtures.admin_quota_fares, (r, p) => r.seatClass === p.seatClass && r.quota === p.quota, body), 'Quota fare saved (demo)')],

  ['get', /^\/admin\/bookings$/, () => ok(fixtures.admin_bookings)],
  ['get', /^\/admin\/payments$/, () => ok(fixtures.admin_payments)],
  ['get', /^\/admin\/payments\/reconciliation$/, () => ok(fixtures.admin_recon)],
  ['get', /^\/admin\/refunds$/, () => ok(fixtures.admin_refunds)],
  ['get', /^\/admin\/announcements$/, () => ok(list('admin_announce', fixtures.admin_announce))],
  ['post', /^\/admin\/announcements$/, (_p, body) => ok(createIn('admin_announce', fixtures.admin_announce, { ...body, createdAt: nowIso(), readCount: 0 }), 'Announcement published (demo)')],
  ['get', /^\/admin\/audit-logs\/actions$/, () => ok(fixtures.admin_audit_actions)],
  ['get', /^\/admin\/audit-logs$/, () => ok(fixtures.admin_audit)],
  ['get', /^\/admin\/reports\/operations$/, () => ok(fixtures.admin_report_ops)],
  ['get', /^\/admin\/reports\/refunds$/, () => ok(fixtures.admin_report_refunds)],

  ['get', /^\/staff\/trains\/([^/]+)\/passengers$/, () => ok([])],
  ['patch', /^\/staff\/passengers\/([^/]+)\/boarding$/, () => ok(null, 'Boarding recorded (demo)')],
  ['get', /^\/staff\/trains$/, () => ok(fixtures.staff_trains)],
  ['get', /^\/staff\/dashboard$/, () => ok(fixtures.staff_dashboard)],
  ['get', /^\/staff\/incidents$/, () => ok(list('incidents', fixtures.staff_incidents))],
  ['post', /^\/staff\/incidents$/, (_p, body) => ok(createIn('incidents', fixtures.staff_incidents, { ...body, status: 'OPEN', createdAt: nowIso() }), 'Incident reported (demo)')],
  ['patch', /^\/staff\/incidents\/([^/]+)$/, (m, body) => {
    const arr = list('incidents', fixtures.staff_incidents).map((r) => (Number(r.id) === Number(m[1]) ? { ...r, status: body?.status } : r));
    store.set('yrdemo_incidents', arr);
    return ok(null, 'Incident updated (demo)');
  }],
  ['get', /^\/staff\/assistance$/, () => ok(list('assistance', fixtures.staff_assistance))],
  ['patch', /^\/staff\/assistance\/([^/]+)$/, (m, body) => {
    const arr = list('assistance', fixtures.staff_assistance).map((r) => (Number(r.id) === Number(m[1]) ? { ...r, status: body?.status } : r));
    store.set('yrdemo_assistance', arr);
    return ok(null, 'Assistance updated (demo)');
  }],
];

function stripQuery(url) {
  return String(url || '').split('?')[0];
}

function match(method, url) {
  const path = stripQuery(url);
  for (const [m, re, handler] of RULES) {
    if (m !== method) continue;
    const hit = path.match(re);
    if (hit) return { handler, match: hit };
  }
  return null;
}

export function createDemoAdapter() {
  return (config) =>
    new Promise((resolve, reject) => {
      const method = (config.method || 'get').toLowerCase();
      const url = config.url || '';
      const params = config.params || {};
      let body = config.data;
      if (typeof body === 'string') {
        try {
          body = JSON.parse(body);
        } catch {
          body = {};
        }
      }

      const found = match(method, url);
      try {
        let payload;
        if (found) {
          payload = found.handler(found.match, body || {}, params, body || {});
        } else {
          payload = method === 'get' ? ok([]) : ok(null);
        }
        resolve({
          data: payload,
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          request: {},
        });
      } catch (err) {
        reject(Object.assign(new Error(err.message || 'Demo adapter error'), { config, isAxiosError: true }));
      }
    });
}
