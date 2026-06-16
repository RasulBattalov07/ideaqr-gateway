# IDEAQR Digital Gateway — Stage 3

An access-governance and immutable-audit layer for the **Digital Kazakhstan** vision.
QR codes act **only as identifiers** — the underlying data stays in its registries.
Every scan and every object creation is routed through a decision engine and written
to an **append-only history**, so access is always governed and fully traceable.

This is an investor-stage MVP. The medical, retail, eco and infrastructure registries
are enriched **mock data** that simulate integration with state and commercial systems.

---

## What Stage 3 adds

Stage 2 delivered the governance pipeline:

```
Identity → Request → Decision → QR / Access → Assignment → Interaction → History
```

Stage 3 puts a real identity and access layer on top of it:

- **Spring Security authentication** with BCrypt-hashed passwords.
- **Persistent accounts** — file-based H2 that survives restarts (credentials and
  created objects are *not* volatile). PostgreSQL is available via a profile.
- A **`User`** entity bound to the Stage 2 **`Identity`** (each registered user gets a
  primary identity, a primary QR and a trust level derived from their profession).
- **Three role-based interfaces** served from a single SPA:
  1. **Unauthenticated** — Russian login / registration.
  2. **Administrator** — a governance panel to mint scannable QR codes for objects.
  3. **Citizen / specialist** — a terminal with a live camera scanner and a search hub
     that renders contextual data cards.

> **Localization:** the backend keeps English identifiers for types, enums and data
> keys, but **every user-facing string in the UI is in Russian**.

---

## Tech stack

| Layer        | Choice                                             |
|--------------|----------------------------------------------------|
| Language     | Java 17                                             |
| Framework    | Spring Boot 3.2.5 (Web, Data JPA, Security, Validation) |
| Persistence  | H2 (file, default) · PostgreSQL (profile)          |
| ORM          | Hibernate / JPA                                     |
| QR codes     | ZXing 3.5.3 (server-side PNG, returned as data URI) |
| Frontend     | Vanilla JS SPA + `html5-qrcode` (camera)            |
| Boilerplate  | Lombok                                              |

---

## Running it

### Option A — Maven (local)

Requires JDK 17+ and Maven.

```bash
mvn spring-boot:run
```

Then open **http://localhost:8080**.

The first launch seeds four demo accounts and creates `./data/ideaqr.mv.db`.
Stop and restart — your data is still there.

### Option B — Packaged jar

```bash
mvn clean package
java -jar target/ideaqr-gateway.jar
```

### Option C — Docker

```bash
docker build -t ideaqr-gateway .
docker run -p 8080:8080 ideaqr-gateway
```

---

## Demo accounts

Seeded automatically on first run. On the login screen you can also click a demo
account chip to auto-fill the form.

| Username    | Password      | Person (RU)         | Interface        | Domain role |
|-------------|---------------|---------------------|------------------|-------------|
| `admin`     | `Admin123!`   | Аружан Сапарова     | Admin panel      | Retail admin |
| `doctor`    | `Doctor123!`  | Санжар Ким          | Citizen terminal | Doctor      |
| `inspector` | `Inspect123!` | Гульнара Ахметова   | Citizen terminal | Inspector   |
| `citizen`   | `Citizen123!` | Дамир Оспанов       | Citizen terminal | Citizen     |

Profession → access profile mapping:

| Profession (RU)             | Domain roles                     | Trust | Admin UI |
|-----------------------------|----------------------------------|-------|----------|
| Врач                        | DOCTOR, CITIZEN                   | 85    | no       |
| Администратор торговли      | RETAIL_ADMIN, ADMIN, CITIZEN     | 80    | **yes**  |
| Инспектор инфраструктуры    | INSPECTOR, ENGINEER, CITIZEN     | 80    | no       |
| Гражданин                   | CITIZEN                          | 50    | no       |

---

## Demo scenarios

The citizen terminal has four **quick-scenario chips**. Each one feeds a known
identifier into the gateway so you can see the policy engine accept or reject access.

There is also a **"Контекст времени" (time context)** selector. Time-gated objects
(medical, infrastructure) only open during working hours (08:00–18:00). Because a live
demo might run at any hour, set the selector to **"Рабочее — 10:00"** to satisfy the
time gate, or to **"Нерабочее — 23:00"** to watch the same scan be denied.

### A · Medical — `PATIENT_7291`
- **Log in as `doctor`** and set the time context to a working hour.
- The engine checks role (DOCTOR), trust (≥70) and the working-hours window, then grants
  access and renders the patient card: **allergy warning banner**, blood type and masked
  IIN, chronic conditions, a medication table, an **SVG blood-pressure / pulse chart**,
  visit history, immunizations and an AI assistant note.
- Log in as `citizen` and scan the same code → **access denied** (role gate). Try `doctor`
  at 23:00 → **denied** (time gate).

### B · Retail — `RETAIL_ADIDAS_SHIRT`
- Works for **any** logged-in user (public object).
- Renders the product card: price, rating, **per-size live stock** (in / low / out),
  colours, **cheaper alternatives** (Kaspi, Wildberries, Lamoda) and a loyalty promo code.

### C · Eco — `ECO_SMART_BIN_102`
- Public object. Renders a **fill-level gauge** (colour-coded), operator and address,
  pickup schedule, environmental tier and recycling stats, plus a
  **"Сообщить о переполнении / поломке"** button that files a governed report.

### D · Infrastructure — `INFRA_SUBSTATION_07`
- **Log in as `inspector`** with a working-hour context.
- Role + trust + time gates apply (same as medical). Renders asset type, voltage, status,
  inspection / maintenance dates, technical notes and a **"Сообщить о проблеме"** button.

The **administrator** can also create brand-new objects from the panel (the brief's
"Adidas Black T-Shirt" flow). A created object runs the full `QR_CREATION` pipeline and
returns a **real, scannable QR PNG**; scanning that code in the terminal resolves the
object you just created.

---

## How a scan is governed

`GatewayService.scan(...)` performs, in order:

1. **Resolve** the object via `RegistryClient` (DB-created objects first, then the
   enriched demo registry, then prefix inference).
2. Create a **`Request`** (ACCESS, PENDING).
3. Ask **`ValidationService`** for a **`Decision`** — evaluated against role, request
   type, object category, trust level and the time-of-day policy window.
4. Record an **`Interaction`** (always), mark the request PROCESSED / FAILED.
5. Attach the data payload **only when APPROVED**.
6. Append a **`History`** entry (ACCESS_GRANTED / DENIED / REVIEW).
7. Return the verdict, the localized reason, a risk level and the **full chain of UUIDs**,
   which the SPA animates in the **governance pipeline tracker**.

Rejected scans and rejected object creations still return **HTTP 200** with
`success: false` and an `outcome`, so the UI can show the verdict and the pipeline rather
than treating governance decisions as errors.

---

## Design

The interface is an **official state e-service**: a light, high-trust, accessible
register. It opens with a government-service banner, uses a deepened **Kazakh sky-blue**
as the primary colour with a restrained **gold** trust accent, and pairs *Manrope*
(display) with *Golos Text* (Cyrillic UI) and *JetBrains Mono* (codes and UUIDs). The
direction follows government-portal conventions (USWDS / GOV.UK): clear official framing,
data-minimisation messaging, and the privacy-by-design principle that data stays with the
registries that hold it.

The signature element is the **governance pipeline tracker**, which lights
Личность → Запрос → Решение → Действие → История with the real generated UUIDs on every
scan and every object creation — the Decision node turns red when access is denied, and
the History node closes with a gold check when it is granted. A live **health indicator**
sits in the banner, and an **immutable-journal viewer** (admins see the whole system; users
see their own actions) makes the append-only audit trail visible in the product itself.

Motion is restrained and `prefers-reduced-motion` is respected; the layout is responsive
to mobile with visible keyboard focus.

---

## Persistence & inspection

- Default DB file: `./data/ideaqr.mv.db` (created on first run).
- **H2 console:** http://localhost:8080/h2-console
  JDBC URL `jdbc:h2:file:./data/ideaqr`, user `sa`, empty password.
- Schema is managed with `ddl-auto=update` (preserved across restarts).
- To start fresh, stop the app and delete the `./data` directory.

---

## Deploying with PostgreSQL (e.g. Render)

A `postgres` profile is included. Provide the standard datasource environment variables
and activate the profile:

```bash
SPRING_PROFILES_ACTIVE=postgres
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<db>
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<password>
```

The server binds to `$PORT` automatically (defaults to 8080), which suits Render/Heroku.

---

## Security notes & caveats

- **Investor MVP.** All registry data (including the medical record) is **mock data**
  that simulates integration with external registries. No real personal data is stored.
- **CSRF is disabled** to keep the JSON SPA + form-login flow simple for the demo. A
  production build would enable CSRF tokens (or move to a stateless token scheme) and
  serve the app over HTTPS.
- Passwords are hashed with **BCrypt**; the session cookie is `JSESSIONID`.
- The H2 console and verbose error bodies are development conveniences and should be
  disabled in production.

---

## Project layout

```
src/main/java/com/ideaqr/gateway
├── IdeaqrGatewayApplication.java
├── config/        SecurityConfig, DataSeeder
├── domain/        Entities (Identity, User, Request, Decision, Qr, Assignment,
│   │              Interaction, History, RegistryObject)
│   └── enums/     RoleType, RequestType, ObjectCategory, … (11 enums)
├── dto/           Request/response payloads
├── repository/    Spring Data JPA repositories
├── service/       AuditService, IdentityService, QrService, ValidationService,
│                  RegistryClient, GatewayService, UserService, CustomUserDetailsService
├── web/           AuthController, GatewayController, QrAdminController,
│                  HealthController, GlobalExceptionHandler, AuthSupport
└── exception/     UsernameAlreadyExistsException

src/main/resources
├── application.properties            (file-H2 default)
├── application-postgres.properties   (PostgreSQL profile)
└── static/        index.html, styles.css, app.js  (the SPA)
```

---

## API reference

| Method | Path                     | Auth        | Purpose                              |
|--------|--------------------------|-------------|--------------------------------------|
| POST   | `/api/auth/register`     | public      | Register a new account               |
| POST   | `/login`                 | public      | Form login (`username`, `password`)  |
| POST   | `/logout`                | session     | Log out                              |
| GET    | `/api/auth/me`           | session     | Current user profile                 |
| POST   | `/api/v2/scan`           | session     | Scan an object through the pipeline  |
| POST   | `/api/v2/report`         | session     | File a governed issue report         |
| GET    | `/api/v2/audit`          | session     | Read the global immutable journal    |
| GET    | `/api/v2/audit/me`       | session     | Read the caller's own journal        |
| POST   | `/api/admin/qr/create`   | ROLE_ADMIN  | Mint a governed object + QR          |
| GET    | `/api/admin/qr/list`     | ROLE_ADMIN  | List created objects                 |
| GET    | `/api/health`            | public      | Liveness                             |
