# YatraRail

An Indian Railway–inspired ticket booking and management platform, built as an **academic / portfolio project**.

> **Disclaimer:** YatraRail is not affiliated with, endorsed by, or connected to Indian Railways or IRCTC in
> any way. All stations, trains, schedules, fares and users in this project are fictional demo data.

---

## Live demo

> **Static demo (frontend only, GitHub Pages):** https://joita-ghoshal.github.io/yatrarail/

The Pages build is the React frontend running on a built-in **in-browser mock** of the YatraRail API
(the mock replays recorded snapshots of the real backend). It needs no server and no database, so
bookings, payments, refunds and ticket checks are all simulated and reset when browser storage is
cleared. Any email/password is accepted at login; the role is inferred from the address
(`admin@…` → Admin, `staff@…` → Staff, anything else → Passenger). The demo accounts below also work.

To run the full backend-connected version locally, follow the setup sections below.

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3, Spring Data JPA / Hibernate, Spring Security (JWT), Maven |
| Database | PostgreSQL |
| Frontend | React 18, React Router, Axios, Chart.js, Vite |
| Observability / ops | Spring Boot Actuator, Micrometer + Prometheus, Caffeine cache, bounded task executor |

## Project structure

```
yatrarail/
├── backend/     Spring Boot REST API (Maven project)
└── frontend/    React SPA (Vite project)
```

---

## 1. Backend setup

### Prerequisites
- Java 17+
- Maven 3.9+ (or use your IDE's bundled Maven)
- PostgreSQL 14+ running locally or accessible remotely

### Create the database
```sql
CREATE DATABASE yatrarail;
```

### Configure environment variables
The backend reads all sensitive configuration from environment variables (see
`backend/src/main/resources/application.properties`). None of these are hardcoded:

| Variable | Purpose | Example |
|---|---|---|
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/yatrarail` |
| `DB_USERNAME` | Database username | `your_database_username` |
| `DB_PASSWORD` | Local database password | `your_local_database_password` |
| `JWT_SECRET` | HMAC signing key | Use a long random secret |
| `JWT_EXPIRATION_MS` | Token lifetime in milliseconds | `86400000` |
| `SERVER_PORT` | Backend port | `8080` |
| `CORS_ORIGINS` | Allowed frontend origins | `http://localhost:5173` |
| `SEED_DEMO_DATA` | Seed demo data on first run | `true` |

You can export these in your shell, put them in your IDE's run configuration, or create a `.env` file and
load it with a tool like `direnv` (Spring Boot does not read `.env` files natively).

### Run it
```bash
cd backend
mvn spring-boot:run
```
The API starts on `http://localhost:8080`. On first startup (when the `stations` table is empty), it
automatically seeds:
- 12 demo stations across major Indian cities
- 7 demo trains with routes, coaches (1A/2A/3A/SL/CC as applicable) and seat inventory
- Demo fare rules per class
- Demo accounts are automatically seeded for local development.

### Demo accounts

> Demo accounts are seeded automatically for **local development only**, the first time the backend
> runs against an empty database. They are fictional logins for this project, shown on the login
> screen; a production profile does not seed them.

---

## 2. Frontend setup

### Prerequisites
- Node.js 18+

### Install & run
```bash
cd frontend
npm install
cp .env.example .env   # adjust VITE_API_BASE_URL if your backend isn't on localhost:8080
npm run dev
```
The app starts on `http://localhost:5173` and proxies `/api/*` requests to the backend in dev mode.

### Production build
```bash
npm run build
```
Outputs a static site to `frontend/dist/`, which you can serve with any static host (Nginx, Vercel,
Netlify, etc.) — just make sure `VITE_API_BASE_URL` points at your deployed backend.

---

## 3. What's implemented

- **Auth:** registration, login, JWT-based sessions, change password, edit profile. Role-based access
  (Passenger / Admin / Staff) enforced independently on the backend (Spring Security) and the frontend
  (route guards) — a passenger cannot call admin APIs even if they tamper with the frontend.
- **Train search:** by source/destination/date, with per-class availability, fare, filters (class) and
  sorting (departure time, duration, fare), plus loading/empty/error states and a reset-filters option.
- **Train details:** full route timeline with per-station arrival/departure/halt/distance, and live
  class availability for the selected date.
- **Booking:** multi-step flow (class → passengers → review → mock payment → confirmation) with up to 6
  passengers, real seat/berth allocation, and automatic waitlisting when a class is full.
- **Cancellation:** tiered demo refund percentage based on days-before-journey, with automatic promotion
  of the next waitlisted passenger into any seat that frees up.
- **Mock payments:** UPI/Card/Net-banking simulator with a success/failure toggle for demoing both paths;
  failed payments can be retried. No real transactions occur and no card data is ever stored.
- **Notifications:** booking/payment/cancellation notifications per user, plus admin-published system
  announcements; unread count badge and mark-as-read.
- **Admin console:** dashboard (KPIs + booking-trend and class-split charts + popular routes), user
  management (activate/deactivate), station CRUD, train CRUD with a route builder and coach/seat
  management, fare-rule configuration, a bookings report view, and announcements.
- **Staff console:** read-only view of active trains and the passenger manifest for a train + date.
- **Theming:** full light/dark mode with a persistent toggle, applied consistently across forms, tables,
  cards, charts and badges.
- **Responsive design:** works down to mobile widths — cards, tables (horizontal scroll), and forms all
  adapt.

## 4. Documented simplifications (by design, for an academic scope)

- The booking reference (`YRxxxxxxxx`) is YatraRail's own identifier, **not** an official Indian Railways
  PNR, and is labeled as such in the UI.
- Fares use a simplified formula (`max(coach base fare, distance × per-km rate, class minimum)`) rather
  than the real, far more complex railway tariff structure. It's configurable per class under
  Admin → Fare Rules.
- Cancellation refunds use a simple 3-tier percentage (≥7 days: 90%, 2–6 days: 50%, <2 days: 25%) rather
  than the official multi-tier railway refund rules.
- "Waitlist" here is a simplified single-queue model per train/coach/date — not the real system's
  quota-based (GNWL/RLWL/PQWL) logic.
- Forgot Password is a UI-only flow (clearly labeled): this demo does not send real emails. Use the
  Change Password option after logging in, or reset a demo account's password directly in the database.
- Payments are entirely simulated client-and-server-side; there is no real payment gateway integration,
  though `BookingService.processPayment` is structured so a real gateway call could replace the
  simulation with minimal changes.

## 5. API overview

All endpoints are prefixed `/api`. Public: `/auth/**`, `/stations`, `/trains/search`,
`/trains/{id}/details`. Authenticated (any role): `/bookings/**`, `/notifications/**`,
`/auth/me|profile|change-password`. Admin-only: `/admin/**`. Staff + Admin: `/staff/**`. See the
controller classes in `backend/src/main/java/com/yatrarail/controller/` for full request/response
shapes, and the DTOs in `backend/src/main/java/com/yatrarail/dto/` for exact payload fields.

## 6. Security notes

- Passwords are hashed with BCrypt; hashes are never returned by the API (`@JsonIgnore` on `User.password`).
- JWTs are HMAC-signed and validated on every request via a stateless filter; there are no server-side
  sessions.
- CORS is restricted to the origins listed in `CORS_ORIGINS`.
- Role checks are enforced in `SecurityConfig` (`hasRole(...)`) independent of anything the frontend does.
- No sensitive payment or government-ID data is collected or stored anywhere in this system.

## 7. Operations, health & monitoring

The backend exposes a standard Spring Boot Actuator surface. **Health and info are public** (a load
balancer must be able to poll without a token); **every other actuator endpoint is `ADMIN`-only**.

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /actuator/health` | Public | Liveness/readiness probe → `{"status":"UP",...}` |
| `GET /actuator/info` | Public | App name, description, version |
| `GET /actuator/metrics` | Admin | All Micrometer meters |
| `GET /actuator/metrics/{name}` | Admin | A single meter (e.g. `http.server.requests`) |
| `GET /actuator/prometheus` | Admin | Prometheus scrape endpoint |

Health component names: `db`, `diskSpace`, `ping`, plus the application's own **`bookingEngine`** and
**`platformQueue`** indicators. Health details and component names are only shown to an authenticated
caller, so the public check does not disclose diagnostics.

**Reading metrics** (the actuator endpoints need an admin token):

```bash
# 1. Log in as an admin and copy the token from the response (any demo admin
# account works; use the password shown on the login screen)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@yatrarail.demo","password":"<demo-admin-password>"}'

# 2. Query a metric with that token
curl -s http://localhost:8080/actuator/metrics/yatrarail.domain.events \
  -H "Authorization: Bearer <token>"

# 3. See every meter
curl -s http://localhost:8080/actuator/metrics -H "Authorization: Bearer <token>"
```

An application counter worth watching is **`yatrarail.domain.events`**, tagged by `type`
(`BOOKING_CREATED`, `PAYMENT_SETTLED`, `PAYMENT_FAILED`, `BOOKING_CANCELLED`,
`ANNOUNCEMENT_PUBLISHED`). Every request also carries an **`X-Request-Id`** — supplied by the caller or
generated — which appears in the response header and in every log line for that request, so one request
can be followed through the logs.

### Caching

A small, bounded Caffeine cache covers **read-mostly master data only**: `stationList`, `trainRoutes`
and `fareRules`. Live seat inventory is deliberately **never** cached, because it changes with every
booking and must always be answered from the database. Entries are removed when the data they mirror is
edited. Configuration (all optional):

| Variable | Default | Purpose |
|---|---|---|
| `CACHE_ENABLED` | `true` | Turn the cache off entirely |
| `CACHE_TTL_SECONDS` | `300` | How long an entry may live before it is re-read |
| `CACHE_MAX_SIZE` | `10000` | Hard upper bound on entries per cache |

Cache hit/miss counters are real and visible at `/actuator/metrics/cache.gets`.

### Async / queue readiness

Outbound domain events flow through a `DomainEventPublisher` seam backed by a **bounded**
`ThreadPoolTaskExecutor` (core 4 / max 16 / queue 500, `CallerRunsPolicy`). Dispatch is synchronous by
default (`PLATFORM_ASYNC_ENABLED=false`) so a local stack trace points at the real request thread; this
is the seam a real message broker would replace, not a distributed queue in its own right.

### Database migrations

Schema changes ship as re-runnable scripts under `backend/src/main/resources/db/` (`phase2`–`phase10`).
Each is guarded (`IF NOT EXISTS`, constraint drops by lookup) so applying it twice is a no-op. Apply the
one that matches your starting schema, as the database owner:

```bash
psql -U postgres -d yatrarail -f backend/src/main/resources/db/phase10-migration.sql
```

---

### Project Developer

- **Name:** Joita Ghoshal
- **University:** Brainware University
- **Course:** B.Tech CSE
- **Academic Year:** 2nd Year Student
- **Project Created By:** Joita Ghoshal
- **Date:** 18 September 2026
