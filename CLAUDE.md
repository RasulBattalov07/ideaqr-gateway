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
- `CASHIER` on duty → `scanRetailCheckout`: `ValidationService.retailCheckout` gates (role + trust ≥ 50 + working mode + hours), then ONLY the buyer's open purchase lines are returned (`contextView: "RETAIL_CHECKOUT"` — cart + «оплачен, не выдан» with totals), never the profile.
- **Priority branch before all role routing:** if the scanner has an active `SERVICE_ORDER` assigned to the scanned identity → `contextView: "SERVICE_VISIT"` (сверка личности исполнителя, кнопка «Подтвердить приход» / «Подтвердить и оплатить»); this link also legitimises cross-tenant resolution of the executor's identity.
- Everyone else (and any specialist in PERSONAL mode) → public business card immediately in `data` + the pre-existing Owner-Approval request for the full profile (`contextView: "BUSINESS_CARD"`, outcome stays REVIEW).

**Owner override:** in `scan()`, the subject of a MEDICAL/LEGAL record (`patientIdentityUid` / `subjectIdentityUid`) always gets APPROVED (`OWNER_ACCESS`) on their own data, bypassing role gates but not the audit trail.

**Citizen dossier:** `CitizenDossierService` provisions three registry objects per citizen with deterministic uids — `MED-<8hex>`, `LEGAL-<8hex>`, `VCARD-<8hex>` (8hex = first 8 of identityUid). They are always stamped with the **public tenant** so specialists in org tenants can resolve them (`RegistryObjectRepository.findByObjectUidAnyTenant` is a native, filter-bypassing lookup; `RegistryClient.resolve` re-applies the tenant guard by hand). Dossiers are created at registration (classic + eGov + seeder) and **lazily** for pre-Phase-2 accounts; they are excluded from «Мои объекты» (`isDossierObject`).

**Mock-eGov onboarding:** `POST /api/auth/egov/{lookup,register,login}` (`EgovAuthController`, permitAll + hard rate limits). Phone-only sign-up: deterministic persona by the phone's last digit (`MockEgovService`; ending in **7** = flagship «Расул Батталов», IIN starts 070420), confirm creates account (username = normalized phone, random password) + dossier and opens the session **programmatically** (Spring Session cookie — in tests carry the `JSESSIONID` cookie from the response, not a `MockHttpSession`). Re-login is OTP with the fixed demo code `1234` (no SMS gateway — the hint is shown in the UI). The classic `/login` form also accepts a **phone number as the identifier**: `CustomUserDetailsService` retries a failed username lookup with the normalized digits (only for digits-and-separators input, so alphanumeric usernames are never reinterpreted). The SPA's eGov flow is one shared component (`egovAuthHtml`/`mountEgovAuth` in app.js) used by BOTH the auth screen («По телефону · eGov» tab + «Нет пароля — войти по SMS-коду» shortcut on the login tab) and the guest-conversion modal (`openGuestRegisterModal`) — a guest registering after a scan gets the same phone flow (classic username/password form remains as a collapsed fallback), keeps the in-place session switch, the guest-history merge and the scan replay.

**Household services module (THREE-PARTY dispatch model):** an order is a governed `Interaction` of type `SERVICE_ORDER` (JSON payload in `detail`, same trick as prescriptions) under a `RequestRecord(SERVICE_ORDER)`; address comes from the eGov dossier. Three personas, still no schema change: `identity_uid` = customer, `target_identity_uid` = the **executor** (new role `EXECUTOR` — universal сантехник/электрик/уборка, demo login `executor`/`Executor123!`), and the **operator** (`SERVICE_OPERATOR`, demo `operator`/`Operator123!`) acts as dispatcher (actor of the assignment audit rows, not stored in a column). Fine-grained stage lives in the detail JSON (`stage`); the status column never leaves the V1 CHECK set (PENDING/CONFIRMED/REJECTED). UI lifecycle: NEW (ждёт оператора) → ASSIGNED (operator assigned an executor via `POST /{id}/assign` — needs role + **working mode**; customer instantly gets the executor's card: name/org/phone from vcard dossier) → IN_PROGRESS (customer **scanned the executor's personal QR at the door** and confirmed arrival, `POST /{id}/arrival`) → COMPLETED (second scan → «Подтвердить и оплатить», `POST /{id}/complete`, demo payment). Both confirmations re-verify that the scanned uid equals the assigned executor (`executorUid` in the body) — чужой QR отклоняется. Scanning the executor's `IDENTITY:` QR is routed in `scanIdentityProfile` **before** role-routing: an active order of the scanner assigned to the scanned identity returns `contextView: "SERVICE_VISIT"` (executor card + order + action CONFIRM_ARRIVAL/CONFIRM_COMPLETE) and also **legitimises cross-tenant identity resolution** (заказчик public-tenant ↔ исполнитель в тенанте УК связаны управляемой заявкой). Boards: `/queue` (dispatcher, SERVICE_OPERATOR), `/assigned` («Мои наряды», EXECUTOR), `/executors` (ACTIVE org staff with workRole EXECUTOR for the assign dropdown); the queue is deliberately cross-tenant (`Interaction` carries no tenant column; the service gates by role) and resolves names via the native `findDisplayNameByIdentityUid`. The old two-way `accept`/`finish` endpoints are gone (legacy `stage=DONE` rows read as IN_PROGRESS and can still be completed). In the SPA the dispatcher/executor plates render inside «Услуги и быт» **only in working mode**. `GET /api/v2/dossier/me` feeds the dashboard and includes the caller's own `vcard` payload — the «Как видят мою визитку» preview in «Мой QR» renders it with the same `businessCard()` renderer as a real scan.

**Retail checkout module («Бизнес и магазины», покупатель × кассир):** a purchase line is a governed `Interaction(RETAIL_PURCHASE)` under `RequestRecord(PURCHASE)` (request_type widened by V10): `identity_uid` = buyer, `object_uid` = the item, `target_identity_uid` = the cashier who issued it; stage in the detail JSON: CART → PAID («оплачен, но не выдан») → ISSUED (status CONFIRMED) / REMOVED (status REJECTED). Shop items are ordinary public-tenant `RegistryObject`s owned by the cashier (склад магазина) whose payload carries `forSale:true` + numeric `price` — that predicate (`RetailCheckoutService.commerceFor`) adds a `commerce` block to OBJECT_EXTENDED scans («Оплатить на месте» = instant PAID demo-payment / «В корзину»; personal items and pre-ownership catalog cards never get it). The **cashier** (new role `CASHIER`, demo `cashier`/`Cashier123!`, org IDEAQR Retail) scans the BUYER's personal QR: `scanIdentityProfile` routes CASHIER-on-duty through `ValidationService.retailCheckout` (role + trust ≥ 50 + working mode + hours) to `contextView: "RETAIL_CHECKOUT"` — ONLY the open lines (cart + paid-not-issued with totals), no profile, no history; the scan targets the buyer so it shows in «кто сканировал меня». Cashier ops (`/collect` = whole cart → PAID; `/{line}/issue`) re-check the gates server-side; **issue transfers ownership through the standard `ObjectLifecycleService.transfer`** (OBJECT_TRANSFERRED, the QR never changes, buyer gets a subject-addressed journal row via the tenantOverride discipline + a notification, and the item appears in «Мои объекты»). The «не хватает средств» demo: the buyer removes a CART line from his phone (`/{line}/remove`, only unpaid lines) and the cashier's checkout panel repolls `GET /checkout/{buyerUid}` live. Endpoints under `/api/v2/retail/{mine,cart,buy,{line}/remove,{line}/issue,checkout/{buyer},checkout/{buyer}/collect}`.

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

Profession → roles + trust level mapping lives in `UserService.profileFor()`. Professions (10): `DOCTOR`, `PHARMACIST`, `INSPECTOR`, `POLICE`, `SELLER`, `SERVICE_OPERATOR`, `EXECUTOR`, `CASHIER`, `RETAIL_ADMIN`, `CITIZEN`. Trust constants are in `IdentityService` (POLICE seeds at `TRUST_GOV` = 90; EXECUTOR/CASHIER at `TRUST_CITIZEN` = 50 — the retail-checkout gate needs exactly ≥ 50). Admin flag comes from profession (only `RETAIL_ADMIN` gets `ROLE_ADMIN`). Public self-registration (classic and eGov) is always `CITIZEN`; specialist/admin roles are granted only via `provisionTrusted` (seeder) or an admin endpoint.

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

- **Schema is owned by Flyway** (SQL in `src/main/resources/db/migration` V1–V8 **plus Java migrations in `src/main/java/db/migration`** — V9 widens the CHECK constraints on `identity_roles.role` (+POLICE), `registry_objects.category` (+LEGAL) and `requests.request_type` (+SERVICE_ORDER), and V10 widens them again for the three-party flows: `identity_roles.role` +EXECUTOR +CASHIER, `requests.request_type` +PURCHASE — both introspect `information_schema`, because the V1 checks are unnamed and H2/PostgreSQL auto-name them differently). Hibernate runs `ddl-auto=validate` on **every** profile, including the local default.
- **Production** runs the **`prod`** profile (file-based H2 on a mounted disk, `ddl-auto=validate`, H2 console hard-disabled, `H2_PASSWORD` required). A managed **PostgreSQL** alternative is the optional `postgres` profile.
- Because every profile runs `validate`, a schema change needs a Flyway migration. Note: `History`/`Event` `event_type` columns carry SQL CHECK constraints, so persisting a brand-new enum value requires a migration — prefer reusing an existing event type (e.g. medical consent reuses `PROFILE_ACCESS_*`; household service orders reuse `ISSUE_REPORTED` + `SERVICE_STARTED`).

### Frontend

Vanilla JS SPA served as static files (`src/main/resources/static/`). All user-facing strings are in Russian. The SPA has three role-conditional views: unauthenticated login (classic + eGov phone tab), admin governance panel, and the citizen app. The citizen app opens on the «Главная» dashboard with three module cards — 🫀 Медицина и здоровье, 🏠 Услуги и быт, 🛍 Бизнес и магазины — plus terminal/QR/objects/history/complaints tabs. Scan results are rendered by `contextView` (`renderContextCard`): business card, prescriptions-only slice, legal dossier, or the category cards; the camera scanner is `html5-qrcode`.

### Implementation status

The full service layer is implemented; the project compiles, runs and is deployable. Core services: `GatewayService`, `QrService`, `AuditService`, `IdentityService`, `ValidationService`, `RegistryClient`, `CitizenDossierService`, `MockEgovService`, `ServiceOrderService`, `RetailCheckoutService`, `ObjectLifecycleService`, `AiCardService`, plus the `IdeaqrGatewayApplication` main class, `application.properties` and `DataSeeder` (seeds eleven demo accounts — admin, seller, doctor, pharmacist, inspector, **police**, **operator** (SERVICE_OPERATOR-диспетчер, УК «Comfort Service»), **executor** (EXECUTOR, УК «Comfort Service»), **cashier** (CASHIER, IDEAQR Retail), citizen, plus the fixed-UUID "Айдос" identity used as the digital business card **and** the medical-consent patient — organizations, memberships, citizen dossiers, a starter prescription on the citizen's card so the pharmacist demo works out of the box, a starter PLUMBER `SERVICE_ORDER` from the citizen (via the standard `ServiceOrderService.order` pipeline, public tenant, skipped if the citizen already has any orders) so the operator's dispatch queue is populated on first login, the citizen's personal items `ITEM_JACKET_UNIQLO` / `ITEM_FRIDGE_SAMSUNG` for the universal-object demo, and the shop showcase `SHOP_TSHIRT_UNIQLO` / `SHOP_JEANS_LEVIS` / `SHOP_SNEAKERS_NB` — forSale + price, owned by the cashier — for the retail-checkout demo). The seeder is idempotent AND self-healing on an existing database: specialist memberships are forced to ACTIVE via `ensureActiveMembership` (a drifted PENDING membership would grey out working mode), and missing dossiers are provisioned lazily. Test suite: 105 green (`ContextAwareScanTest`, `EgovOnboardingTest` cover Phase 2; `ServiceOrderFlowTest` covers the three-party dispatch flow — assign gates, QR identity match on arrival, pay-after-arrival ordering, visit context; `RetailCheckoutFlowTest` covers the checkout — buy/cart lines, duplicate and forSale guards, cashier gates, collect, issue-transfers-ownership, commerce states; `DemoSpecialistOnboardingTest` covers admin profession+organization assignment unlocking working mode for eGov accounts; `ObjectGovernanceTest` covers universal object governance — guest sees zero owner info, P2P owner-profile consent, police disclosure leaves a tamper-evident audit trail visible to the owner via `/api/v2/audit/me`, claim/transfer never change the QR, and an org-tenant owner sees a public-tenant item in «Мои объекты»).

**Tenant stamping of subject-addressed journal rows:** a `History` row written on ANOTHER identity's behalf (authority disclosure on the owner's journal, consent outcome on the requester's journal) is stamped with the SUBJECT's tenant via the `AuditService.record(..., tenantOverride)` overload — the default context stamp would carry the ACTOR's tenant and the read-side filter would hide the row from the very person it informs. Same discipline on reads: `/api/v2/my-objects` selects via the native `findOwnedAnyTenant` (personal property lives in the public tenant; an org-tenant owner must still see it). Unknown routes return an honest 404 (`NoResourceFoundException` handler), never a generic 500.

Stage 4 scenario features are implemented: working mode + sessions, organizations + memberships, SOS, a notification center, identity risk score, and guest identities with history merge — via `SessionService`, `OrganizationService`, `NotificationService`, `GuestService` and the `SessionController` / `NotificationController` / `GuestController` endpoints.

**SOS is wired to the admin panel:** each SOS seeds a `Workflow(SOS_ESCALATION)` that surfaces — across all tenants — in the admin "Тревоги (SOS)" tab (`GET /api/admin/sos`, `POST /api/admin/sos/{id}/resolve`), where an admin sees the emergency and marks it resolved. The `Workflow` entity is now consumed, not just written.

The "Modules" admin tab + API were removed (the tab was unreachable and toggling a module gated nothing); the backing dead code (`PlatformModule` entity, `ModuleStatus`, `ModuleService`, `PlatformModuleRepository` and the seeder block) has been deleted too — the orphaned `platform_modules` table stays in the Flyway-owned schema, which `ddl-auto=validate` does not mind. Statistics and Analytics are merged into one admin "Статистика" tab. Complaints may now be **general** (no prior interaction) — `ComplaintService` mints a governing interaction so the immutable trail and the non-null FK both hold without a schema change.

### Build note

`mvn` is not on the PATH in this environment; use the wrapper with JDK 17 (Lombok breaks on newer JDKs): `JAVA_HOME=<jdk17> ./mvnw clean test`.
