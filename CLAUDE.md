# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run locally (requires JDK 17+ and Maven)
mvn spring-boot:run

# Build fat jar
mvn clean package
java -jar target/ideaqr-gateway.jar

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=MyTestClass

# Docker
docker build -t ideaqr-gateway .
docker run -p 8080:8080 ideaqr-gateway
```

App starts on **http://localhost:8080**.

H2 console (local only): http://localhost:8080/h2-console — JDBC URL `jdbc:h2:file:./data/ideaqr`, user `sa`, blank password.

To reset the database, stop the app and delete the `./data/` directory.

## Architecture overview

This is a **Spring Boot 3.2.5 / Java 17** investor-stage MVP. QR codes are access identifiers only — underlying data lives in registries. Every scan and object creation runs through a governance pipeline and writes to an append-only history.

### Governance pipeline (core flow)

```
Identity → Request → Decision → QR / Access → Assignment → Interaction → History
```

`GatewayService.scan(...)` implements this pipeline:
1. Resolve the object (DB-created objects → enriched demo registry → prefix inference)
2. Create a `RequestRecord` (ACCESS, PENDING)
3. `ValidationService` evaluates role, object category, trust level, **working mode** and time-of-day policy
4. Record an `Interaction`; mark the request PROCESSED / FAILED
5. Attach data payload only when APPROVED
6. Append a `History` entry
7. Return verdict + localized reason + risk level + full chain of UUIDs

Rejected scans return HTTP 200 with `success: false` — governance decisions are not HTTP errors.

**Patient consent for medical records:** passing the professional gates (role + trust + working mode + hours) is necessary but **not sufficient** to open a medical card. The patient of record — carried in the card payload as `patientIdentityUid` — must explicitly approve, reusing the same Owner-Approval flow as a personal-profile QR (`GatewayService.requestMedicalConsent` → patient confirms → scanner polls `profileAccessResult`; interaction type `MEDICAL_SCAN`). The professional gates are re-checked at reveal time, so consent never overrides the policy engine.

### Phase 2 — context-aware identity QR («Единый национальный QR»)

Scanning `IDENTITY:<uuid>` (someone's personal QR) is routed by the **scanner's role × working mode** in `GatewayService.scanIdentityProfile`:

- `DOCTOR` on duty → internally re-scans the owner's `MED-*` dossier object → hits the normal MEDICAL gates **and the patient-consent flow** (routing never bypasses consent); response carries `contextView: "MEDICAL"`.
- `PHARMACIST` on duty → `scanPrescriptionsView`: professional gates checked, then ONLY the prescriptions slice is returned (`contextView: "PRESCRIPTIONS"`), never the full card, and no consent (the patient presenting their QR at the counter is the consent).
- `POLICE` on duty → internally re-scans the owner's `LEGAL-*` object; `ValidationService` LEGAL branch requires role POLICE + trust ≥ 80 + working mode + hours (`contextView: "LEGAL"`, classification SECRET).
- Everyone else (and any specialist in PERSONAL mode) → public business card immediately in `data` + the pre-existing Owner-Approval request for the full profile (`contextView: "BUSINESS_CARD"`, outcome stays REVIEW).

**Owner override:** in `scan()`, the subject of a MEDICAL/LEGAL record (`patientIdentityUid` / `subjectIdentityUid`) always gets APPROVED (`OWNER_ACCESS`) on their own data, bypassing role gates but not the audit trail.

**Citizen dossier:** `CitizenDossierService` provisions three registry objects per citizen with deterministic uids — `MED-<8hex>`, `LEGAL-<8hex>`, `VCARD-<8hex>` (8hex = first 8 of identityUid). They are always stamped with the **public tenant** so specialists in org tenants can resolve them (`RegistryObjectRepository.findByObjectUidAnyTenant` is a native, filter-bypassing lookup; `RegistryClient.resolve` re-applies the tenant guard by hand). Dossiers are created at registration (classic + eGov + seeder) and **lazily** for pre-Phase-2 accounts; they are excluded from «Мои объекты» (`isDossierObject`).

**Mock-eGov onboarding:** `POST /api/auth/egov/{lookup,register,login}` (`EgovAuthController`, permitAll + hard rate limits). Phone-only sign-up: deterministic persona by the phone's last digit (`MockEgovService`; ending in **7** = flagship «Расул Батталов», IIN starts 070420), confirm creates account (username = normalized phone, random password) + dossier and opens the session **programmatically** (Spring Session cookie — in tests carry the `JSESSIONID` cookie from the response, not a `MockHttpSession`). Re-login is OTP with the fixed demo code `1234`.

**Household services module (two-way):** an order is a governed `Interaction` of type `SERVICE_ORDER` (JSON payload in `detail`, same trick as prescriptions) under a `RequestRecord(SERVICE_ORDER)`; address comes from the eGov dossier. The **executor side** (role `SERVICE_OPERATOR`, demo login `operator`/`Operator123!`) is encoded without schema changes: `target_identity_uid` stores the executor who accepted the order; `stage=DONE` inside the detail JSON marks «работа выполнена, ждём заказчика» (the status column never leaves the V1 CHECK set PENDING/CONFIRMED/REJECTED). UI lifecycle: NEW → ACCEPTED → DONE → COMPLETED (customer confirms; REJECTED → DECLINED reserved). Endpoints: `/api/v2/services/{catalog,order,mine,queue,{id}/accept,{id}/finish,{id}/complete}`; the queue is deliberately cross-tenant (`Interaction` carries no tenant column; the service gates by role) and resolves names via the native `findDisplayNameByIdentityUid`. `GET /api/v2/dossier/me` feeds the dashboard and includes the caller's own `vcard` payload — the «Как видят мою визитку» preview in «Мой QR» renders it with the same `businessCard()` renderer as a real scan.

### Universal object governance («один неизменяемый QR — разный контекст» для вещей)

Item categories (**RETAIL / ECO / GENERAL**) route the SAME object QR by who scans (`GatewayService.routeObjectView`; response fields `contextView` + `ownership` + `ownerDisclosure` + `aiCard`):

- **Guest** → `OBJECT_PUBLIC`: PublicCard projection only; `ownership`/`aiCard` are absent — a guest gets ZERO owner information, not even the fact that an owner exists (`ObjectGovernanceTest` asserts over the whole response body).
- **Registered user** → `OBJECT_EXTENDED`: full card + AI card + `ownership.ownerRequestAvailable` (кнопка «Профиль владельца»). The owner is NOT revealed; the button calls `POST /api/v2/objects/{uid}/owner-request` → `GatewayService.requestOwnerProfile` creates a REVIEW request + `OWNER_PROFILE` interaction (same Owner-Approval machinery as `PROFILE_SCAN`; `interaction_type` carries no CHECK constraint, so no migration was needed). The owner decides via the existing `/api/v2/access/{id}/{confirm|reject}`; the requester polls `/api/v2/access/{id}/result` (widened to accept `OWNER_PROFILE`).
- **Owner** → `OBJECT_OWNER`: instant full access, no requests; `ownership.transferAvailable` + transfer form in the SPA.
- **POLICE on duty** → `OBJECT_AUTHORITY`: `ValidationService.authorityDisclosure` (same gates as LEGAL: role + trust ≥ 80 + working mode + hours) unlocks `ownerDisclosure` (ФИО/ИИН/телефон/адрес from the owner's vcard+legal dossiers) **without consent**; the scan additionally writes a hash-chained History row on the OWNER's journal («СЛУЖЕБНЫЙ ДОСТУП…»), stamps `interaction.targetIdentityUid` (owner sees it in «кто сканировал меня») and notifies the owner. Failing the gates silently downgrades to `OBJECT_EXTENDED`.

**Ownership self-service** (`ObjectOwnershipController` → `ObjectLifecycleService`): `POST /api/v2/objects/{uid}/claim` materialises a pre-ownership catalog item as a DB object with the SAME objectUid (QR never changes; public tenant; requires `itemType` in the payload or RETAIL category); `POST /api/v2/objects/{uid}/transfer` (`transferByOwner`) is owner-gated and resolves the recipient by username or identity uid **cross-tenant** via native lookups (`findIdentityUidByUsernameAnyTenant`, `countByIdentityUidAnyTenant` — a hand-over may cross tenants, like a phone-number transfer). Both reuse the `OBJECT_LIFECYCLE` / `OBJECT_TRANSFERRED` pipeline; dossier objects (`MED-/LEGAL-/VCARD-`) are non-claimable and non-transferable.

**AI card** (`AiCardService`): rule-based deterministic mock (no external calls) keyed by `itemType` (explicit in the payload — seeded items carry it — or name heuristics): CLOTHING/FOOTWEAR get styling tips + «гардероб» pairings built from the scanner's own items (`findOwnedAnyTenant`), VEHICLE gets services/parts/insurance, APPLIANCE gets filters/warranty/service centres. Returned only to authenticated non-authority views; swap the internals for a real LLM without touching callers.

Demo items: `ITEM_JACKET_UNIQLO`, `ITEM_FRIDGE_SAMSUNG` (owned by **citizen**, seeded by DataSeeder, public tenant); catalog Nike / Camry / студбилет carry `itemType` for claim + AI.

### Domain model

Relationships between entities are stored as plain UUID fields (not JPA associations). This allows guest identity histories to be re-pointed to primary identities without cascade effects.

Key entities: `Identity`, `User`, `RequestRecord`, `Decision`, `Qr`, `Assignment`, `Interaction`, `History`, `RegistryObject`.

`User` (Spring Security account) ↔ `Identity` (governance subject) are linked by `identityUid`. Registration provisions both plus a permanent primary QR.

### Role and trust system

Profession → roles + trust level mapping lives in `UserService.profileFor()`. Professions (8): `DOCTOR`, `PHARMACIST`, `INSPECTOR`, `POLICE`, `SELLER`, `SERVICE_OPERATOR`, `RETAIL_ADMIN`, `CITIZEN`. Trust constants are in `IdentityService` (POLICE seeds at `TRUST_GOV` = 90). Admin flag comes from profession (only `RETAIL_ADMIN` gets `ROLE_ADMIN`). Public self-registration (classic and eGov) is always `CITIZEN`; specialist/admin roles are granted only via `provisionTrusted` (seeder) or an admin endpoint.

**A specialist role is inert without an organization:** working mode requires an **ACTIVE** `OrganizationMembership`, and every professional gate requires working mode. `POST /api/admin/users/{username}/profession` therefore accepts an optional `organizationUid` — the admin assigns the profession **and** attaches the user to the employer in one action (`OrganizationService.ensureActiveMembership` also promotes a PENDING claim). This is the only way to make a specialist out of an eGov-registered account, which has no employer and never filed an employment claim. Working mode and the org picker consider **ACTIVE memberships only** (`activeMembershipsOf`); a PENDING self-service employment claim governs nothing until approved. `DemoSpecialistOnboardingTest` covers both sides.

**Trust is a SINGLE metric:** `Identity.trustLevel` (0–100) is the one number that is both displayed and gated on by `ValidationService` (medical ≥ 70, infrastructure ≥ 60, legal ≥ 80). The earlier gamified "trust score" was removed — it was shown everywhere but never influenced a decision. Do not reintroduce a second number.

Object categories now include `LEGAL` (criminal record / fines dossier): classification SECRET, policy `LEGAL_ACCESS`, POLICE-only via `ValidationService`.

### Security

- Spring Security with BCrypt passwords and `JSESSIONID` session cookies
- `ROLE_ADMIN` required for `/api/admin/**`; authenticated session for all other `/api/**`. Admins run **unscoped** across tenants; every other user is hard-isolated by a `tenant_id` Hibernate `@Filter` (`TenantInterceptor` + `TenantFilterAspect`)
- CSRF **enabled** (double-submit cookie): the server issues an `XSRF-TOKEN` cookie and the SPA echoes it in the `X-XSRF-TOKEN` header on every state-changing request (`CsrfCookieFilter` + `SecurityConfig`)
- Per-IP rate limiting (`RateLimitingFilter`), forced password change after an admin reset (`PasswordChangeEnforcementInterceptor`), append-only hash-chained audit with a `/api/admin/audit/verify` integrity check
- Auth responses are JSON (200 / 401), not redirects — configured in `SecurityConfig`
- `AuthSupport` helper resolves the current session's `Identity` from the `Authentication` object

### Persistence

- **Schema is owned by Flyway** (SQL in `src/main/resources/db/migration` V1–V8 **plus Java migrations in `src/main/java/db/migration`** — V9 widens the CHECK constraints on `identity_roles.role` (+POLICE), `registry_objects.category` (+LEGAL) and `requests.request_type` (+SERVICE_ORDER) by introspecting `information_schema`, because the V1 checks are unnamed and H2/PostgreSQL auto-name them differently). Hibernate runs `ddl-auto=validate` on **every** profile, including the local default.
- **Production** runs the **`prod`** profile (file-based H2 on a mounted disk, `ddl-auto=validate`, H2 console hard-disabled, `H2_PASSWORD` required). A managed **PostgreSQL** alternative is the optional `postgres` profile.
- Because every profile runs `validate`, a schema change needs a Flyway migration. Note: `History`/`Event` `event_type` columns carry SQL CHECK constraints, so persisting a brand-new enum value requires a migration — prefer reusing an existing event type (e.g. medical consent reuses `PROFILE_ACCESS_*`; household service orders reuse `ISSUE_REPORTED` + `SERVICE_STARTED`).

### Frontend

Vanilla JS SPA served as static files (`src/main/resources/static/`). All user-facing strings are in Russian. The SPA has three role-conditional views: unauthenticated login (classic + eGov phone tab), admin governance panel, and the citizen app. The citizen app opens on the «Главная» dashboard with three module cards — 🫀 Медицина и здоровье, 🏠 Услуги и быт, 🛍 Бизнес и магазины — plus terminal/QR/objects/history/complaints tabs. Scan results are rendered by `contextView` (`renderContextCard`): business card, prescriptions-only slice, legal dossier, or the category cards; the camera scanner is `html5-qrcode`.

### Implementation status

The full service layer is implemented; the project compiles, runs and is deployable. Core services: `GatewayService`, `QrService`, `AuditService`, `IdentityService`, `ValidationService`, `RegistryClient`, `CitizenDossierService`, `MockEgovService`, `ServiceOrderService`, `ObjectLifecycleService`, `AiCardService`, plus the `IdeaqrGatewayApplication` main class, `application.properties` and `DataSeeder` (seeds nine demo accounts — admin, seller, doctor, pharmacist, inspector, **police**, **operator** (SERVICE_OPERATOR, УК «Comfort Service»), citizen, plus the fixed-UUID "Айдос" identity used as the digital business card **and** the medical-consent patient — organizations, memberships, citizen dossiers, a starter prescription on the citizen's card so the pharmacist demo works out of the box, and the citizen's personal items `ITEM_JACKET_UNIQLO` / `ITEM_FRIDGE_SAMSUNG` for the universal-object demo). Test suite: 85 green (`ContextAwareScanTest`, `EgovOnboardingTest` cover Phase 2; `ServiceOrderFlowTest` covers the two-way service flow; `DemoSpecialistOnboardingTest` covers admin profession+organization assignment unlocking working mode for eGov accounts; `ObjectGovernanceTest` covers universal object governance — guest sees zero owner info, P2P owner-profile consent, police disclosure leaves a tamper-evident audit trail, claim/transfer never change the QR).

Stage 4 scenario features are implemented: working mode + sessions, organizations + memberships, SOS, a notification center, identity risk score, and guest identities with history merge — via `SessionService`, `OrganizationService`, `NotificationService`, `GuestService` and the `SessionController` / `NotificationController` / `GuestController` endpoints.

**SOS is wired to the admin panel:** each SOS seeds a `Workflow(SOS_ESCALATION)` that surfaces — across all tenants — in the admin "Тревоги (SOS)" tab (`GET /api/admin/sos`, `POST /api/admin/sos/{id}/resolve`), where an admin sees the emergency and marks it resolved. The `Workflow` entity is now consumed, not just written.

The "Modules" admin tab + API were removed (the tab was unreachable and toggling a module gated nothing). Statistics and Analytics are merged into one admin "Статистика" tab. Complaints may now be **general** (no prior interaction) — `ComplaintService` mints a governing interaction so the immutable trail and the non-null FK both hold without a schema change.

### Build note

`mvn` is not on the PATH in this environment; use the wrapper with JDK 17 (Lombok breaks on newer JDKs): `JAVA_HOME=<jdk17> ./mvnw clean test`.
