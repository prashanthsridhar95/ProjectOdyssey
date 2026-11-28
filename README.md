# Odyssey: a marketplace framework, with car wash as its first vertical

Customers find nearby car washes, see live slots and prices, book and pay. Vendors run slots, menu and bookings behind a PIN gate. An admin onboards vendors, sets commission, pays them out and sees the numbers.

The code is built as a **reusable marketplace framework** (providers, locations, catalog, availability, orders, payments, payouts, billing). Car wash is a *vertical pack* on top. A different marketplace is a new pack, not a new backend. The product requirements live in the PRD doc ("PRD: On-Demand Car Wash Marketplace").

## Run it

```bash
docker compose up --build        # Postgres + backend (+ web apps)
./scripts/smoke.sh               # book, pay, complete, payout, over real HTTP
```

| What | Where |
| --- | --- |
| REST API | http://localhost:8080/v1 (spec: `api/openapi.yaml`) |
| Admin panel (web) | http://localhost:5173 |
| Vendor dashboard (web) | http://localhost:5174 |
| Postgres | localhost:55432 (user/db/password `odyssey`) |

Demo logins. The dev identity provider accepts the code **123456** for everyone:

| Who | Phone | Extra |
| --- | --- | --- |
| Admin | +910000000001 | |
| Vendor owner (Sparkle, 3 bays) | +919000000001 (…02, 03, 04 for the others) | Owner PIN `123456` |
| Counter staff | +919000000201 | 4-digit PIN `1234` |
| Customer | any new number | created on first sign-in |

Mobile (Android emulator reaches the host at `10.0.2.2`):

```bash
cd mobile
./gradlew :app:customer:installDebug :app:vendor:installDebug :app:admin:installDebug
# the apps call https://carwashapis.prashanthsridhar.com by default;
# local backend from the emulator:  -Podyssey.apiUrl=http://10.0.2.2:8080
```

Web without Docker: `cd web && npm install && npm run dev:admin` (and `dev:vendor`). Backend without Docker: `cd backend && ./gradlew :server:run` (in-memory H2, same migrations and seed).

### Public API through Cloudflare

`infra/cloudflared/run.sh` publishes the local backend at https://carwashapis.prashanthsridhar.com using the Cloudflare tunnel `carwash` (credentials stay in `~/.cloudflared`). Put `JWT_SECRET`, `DEV_OTP` and `CORS_ORIGINS` in an untracked `.env` before exposing it: the defaults in `docker-compose.yml` are for localhost only. The dev identity provider is still a stand-in, so treat the public API as a demo until the IAM adapter replaces it.

### Customer app in a browser

The customer app also runs as a web app: the same Compose screens compiled to WebAssembly (`mobile/app/customer-web`, shared screens in `mobile/feature/customerapp`). Build it with `scripts/build-customer-web.sh` (first run downloads Node.js), then `docker compose up -d customer-web` serves it on http://localhost:5175. The API address is read at load time from `config.js`, so one build works anywhere. The vendor and admin apps already have React web versions on :5174 and :5173. Needs a modern browser (Chrome, Edge, Firefox, Safari 18.2+); text boxes open only after a real tap or click; browser notifications and QR scanning are not available on web. Publish it by adding an ingress rule and DNS record for `carwash-app.prashanthsridhar.com` (rule already in `infra/cloudflared/config.yml`).

## Layout

```
backend/   Kotlin, Ktor, Exposed, Flyway. Clean Architecture, three Gradle modules
  core/          the framework: domain, ports, services. No web, DB or vertical code (a test enforces it)
  pack-carwash/  the car wash vertical: words, vehicle types, order lifecycle, policies (configuration only)
  server/        adapters: REST routes, Exposed repositories, JWT, fake payment gateway, seed data
web/       React + TypeScript. @odyssey/api (typed client), @odyssey/ui (design system), apps/admin, apps/vendor
mobile/    Kotlin Multiplatform + Compose. core:{domain,data,ui}, feature:*, vertical:carwash, app:{customer,vendor,admin}
api/       OpenAPI contract
```

### How the framework and the vertical split

- The order lifecycle is **data** (`OrderFlow`: states, transitions, who may fire each, side effects such as refund or settle). The core engine runs any flow.
- Words (Vendor / Outlet / Booking), the price dimension (vehicle type), saved items (a vehicle), tax, fees, commission default, cancellation policy and booking rules are all `TenantConfig`, editable in the admin panel.
- Availability is a strategy. The slot-with-capacity strategy is built; others (open hours with stock) plug in behind the same class.
- Mobile mirrors this: generic features ask a `VerticalUi` for the car-specific piece (the garage and vehicle picker, in `vertical:carwash`).
- Each tenant is isolated by `tenant_id`; the apps pick the tenant with `X-Tenant`.

### Money

Commission is a percentage of the **service amount** (not tax or fees), per vendor or the tenant default, fixed on the booking at the time it is placed. Every money movement is a balanced double-entry ledger transaction (payment, settlement, full or partial refund, payout). Completed and no-show bookings become payable; the admin runs payouts. Platform billing (base subscription + per-order percentage above a threshold) is computed per month.

## What is verified

| Layer | Result |
| --- | --- |
| Backend core unit tests (11: slot maths, pricing, flow, boundary rule) | pass |
| Backend API integration tests, 17 scenarios over real HTTP on H2 | pass |
| Same flow on real PostgreSQL in Docker (`scripts/smoke.sh`) | pass |
| Web: typecheck, production build, 8 unit tests | pass |
| Web in a browser (Rinse "Industry" theme): admin dashboard, payments (batch, payouts, collections, refunds, ledger), commission rules with preview, customers, moderation, support, cities and areas, vendor review with per-document states; vendor overview with busiest-hours heatmap, closures, reviews with replies, bulk price edit, earnings | pages load with seeded data; write actions (reply, closure, bulk edit, resolve ticket) covered by backend tests, not clicked through |
| Mobile: shared domain tests (9) and data tests (5, mock HTTP) | pass |
| Android: three apps build; customer app booked and paid on an emulator; vendor app signs in and shows the Today inbox and walk-in form (other vendor screens not clicked through); a stale session returns to sign-in | checked by hand |

Not verified: iOS (see below), the admin Android app on a device (it builds), `api/openapi.yaml` against a validator, the web Docker image under load.

## What is a stand-in (and what to swap)

| Stand-in | Replace with |
| --- | --- |
| `DevIdentityProvider` (fixed OTP) in `server/auth` | Adapter to your IAM product: `IdentityProvider` port for sign-up/login; tokens then come from the IAM and are only validated by the API |
| `FakePaymentGateway` | Razorpay Route or Cashfree Easy Split behind `PaymentGateway`; the signed webhook endpoint already exists |
| Haversine distance search | PostGIS when the vendor count grows |
| Slot holds in the database | Redis if hold traffic needs it |
| Polling every 15 seconds for new bookings | WebSocket / server-sent events + push (FCM, APNs) |
| In-memory dev secrets and `SharedPreferences` session | Secrets manager, encrypted storage |

## Decisions worth knowing

- **iOS is not built yet.** All shared code is in `commonMain` and library modules declare iOS targets behind `-Podyssey.ios=true`, but no Xcode host app exists and nothing was compiled for iOS here. Next step: a small `shared` framework module that exposes the Compose entry point, plus an Xcode project.
- **Compose Multiplatform 1.10.3 and AGP 8.13**, not the newest. Compose 1.12 needs AGP 9 and compileSdk 37; move together when you adopt AGP 9. Ktor client is pinned to 3.2.3 because newer OkHttp needs compileSdk 37.
- **Domain models carry `@Serializable`** so the data layer parses the REST contract directly. It is metadata only, but it is a pragmatic shortcut. Split into DTOs if you want the domain free of it.
- **Plain constructor DI** (`AppContainer`) instead of Koin; swapping is local.
- **Not built yet from the PRD:** promo codes, support tickets, review moderation, multi-outlet switcher in the vendor mobile app (first outlet only), self-serve tenant provisioning, SDK publishing, usage metering. The framework's tenant isolation, config console, API stability and billing statement are in.
- Request OTP has no rate limiting and the Owner PIN lockout is per account, not per device. Add both before real users.
