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

### Domain model

Relationships between entities are stored as plain UUID fields (not JPA associations). This allows guest identity histories to be re-pointed to primary identities without cascade effects.

Key entities: `Identity`, `User`, `RequestRecord`, `Decision`, `Qr`, `Assignment`, `Interaction`, `History`, `RegistryObject`.

`User` (Spring Security account) ↔ `Identity` (governance subject) are linked by `identityUid`. Registration provisions both plus a permanent primary QR.

### Role and trust system

Profession → roles + trust level mapping lives in `UserService.profileFor()`. Professions (7): `DOCTOR`, `PHARMACIST`, `INSPECTOR`, `SELLER`, `SERVICE_OPERATOR`, `RETAIL_ADMIN`, `CITIZEN`. Trust constants are in `IdentityService`. Admin flag comes from profession (only `RETAIL_ADMIN` gets `ROLE_ADMIN`). Public self-registration is always `CITIZEN`; specialist/admin roles are granted only via `provisionTrusted` (seeder) or an admin endpoint.

**Trust is a SINGLE metric:** `Identity.trustLevel` (0–100) is the one number that is both displayed and gated on by `ValidationService` (medical ≥ 70, infrastructure ≥ 60). The earlier gamified "trust score" was removed — it was shown everywhere but never influenced a decision. Do not reintroduce a second number.

### Security

- Spring Security with BCrypt passwords and `JSESSIONID` session cookies
- `ROLE_ADMIN` required for `/api/admin/**`; authenticated session for all other `/api/**`. Admins run **unscoped** across tenants; every other user is hard-isolated by a `tenant_id` Hibernate `@Filter` (`TenantInterceptor` + `TenantFilterAspect`)
- CSRF **enabled** (double-submit cookie): the server issues an `XSRF-TOKEN` cookie and the SPA echoes it in the `X-XSRF-TOKEN` header on every state-changing request (`CsrfCookieFilter` + `SecurityConfig`)
- Per-IP rate limiting (`RateLimitingFilter`), forced password change after an admin reset (`PasswordChangeEnforcementInterceptor`), append-only hash-chained audit with a `/api/admin/audit/verify` integrity check
- Auth responses are JSON (200 / 401), not redirects — configured in `SecurityConfig`
- `AuthSupport` helper resolves the current session's `Identity` from the `Authentication` object

### Persistence

- **Schema is owned by Flyway** (`src/main/resources/db/migration`, V1–V8) — Hibernate only `validate`s it in prod. Local default profile uses file-based H2 with `ddl-auto=update`.
- **Production** runs the **`prod`** profile (file-based H2 on a mounted disk, `ddl-auto=validate`, H2 console hard-disabled, `H2_PASSWORD` required). A managed **PostgreSQL** alternative is the optional `postgres` profile.
- Because prod runs `validate`, a schema change needs a Flyway migration. Note: `History`/`Event` `event_type` columns carry SQL CHECK constraints, so persisting a brand-new enum value requires a migration — prefer reusing an existing event type (e.g. medical consent reuses `PROFILE_ACCESS_*`).

### Frontend

Vanilla JS SPA served as static files (`src/main/resources/static/`). All user-facing strings are in Russian. The SPA has three role-conditional views: unauthenticated login/register, admin governance panel, and citizen terminal with a live camera scanner (`html5-qrcode`).

### Implementation status

The full service layer is implemented; the project compiles, runs and is deployable. Core services: `GatewayService`, `QrService`, `AuditService`, `IdentityService`, `ValidationService`, `RegistryClient`, plus the `IdeaqrGatewayApplication` main class, `application.properties` and `DataSeeder` (seeds seven demo accounts — admin, seller, doctor, pharmacist, inspector, citizen, plus the fixed-UUID "Айдос" identity used as the digital business card **and** the medical-consent patient — organizations and memberships).

Stage 4 scenario features are implemented: working mode + sessions, organizations + memberships, SOS, a notification center, identity risk score, and guest identities with history merge — via `SessionService`, `OrganizationService`, `NotificationService`, `GuestService` and the `SessionController` / `NotificationController` / `GuestController` endpoints.

**SOS is wired to the admin panel:** each SOS seeds a `Workflow(SOS_ESCALATION)` that surfaces — across all tenants — in the admin "Тревоги (SOS)" tab (`GET /api/admin/sos`, `POST /api/admin/sos/{id}/resolve`), where an admin sees the emergency and marks it resolved. The `Workflow` entity is now consumed, not just written.

The "Modules" admin tab + API were removed (the tab was unreachable and toggling a module gated nothing). Statistics and Analytics are merged into one admin "Статистика" tab. Complaints may now be **general** (no prior interaction) — `ComplaintService` mints a governing interaction so the immutable trail and the non-null FK both hold without a schema change.

### Build note

`mvn` is not on the PATH in this environment; use the wrapper with JDK 17 (Lombok breaks on newer JDKs): `JAVA_HOME=<jdk17> ./mvnw clean test`.
