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
3. `ValidationService` evaluates role, request type, object category, trust level, and time-of-day policy
4. Record an `Interaction`; mark the request PROCESSED / FAILED
5. Attach data payload only when APPROVED
6. Append a `History` entry
7. Return verdict + localized reason + risk level + full chain of UUIDs

Rejected scans return HTTP 200 with `success: false` — governance decisions are not HTTP errors.

### Domain model

Relationships between entities are stored as plain UUID fields (not JPA associations). This allows guest identity histories to be re-pointed to primary identities without cascade effects.

Key entities: `Identity`, `User`, `RequestRecord`, `Decision`, `Qr`, `Assignment`, `Interaction`, `History`, `RegistryObject`.

`User` (Spring Security account) ↔ `Identity` (governance subject) are linked by `identityUid`. Registration provisions both plus a permanent primary QR.

### Role and trust system

Profession → roles + trust level mapping lives in `UserService.profileFor()`. Professions: `DOCTOR`, `RETAIL_ADMIN`, `INSPECTOR`, `CITIZEN`. Trust constants are in `IdentityService`. Admin flag comes from profession (only `RETAIL_ADMIN` gets `ROLE_ADMIN`).

### Security

- Spring Security with BCrypt passwords and `JSESSIONID` session cookies
- `ROLE_ADMIN` required for `/api/admin/**`; authenticated session for all other `/api/**`
- CSRF disabled (demo SPA with fetch-based JSON API)
- Auth responses are JSON (200 / 401), not redirects — configured in `SecurityConfig`
- `AuthSupport` helper resolves the current session's `Identity` from the `Authentication` object

### Persistence

- **Default (local):** file-based H2 at `./data/ideaqr.mv.db`, `ddl-auto=update`
- **Production:** PostgreSQL via `postgres` profile — activate with `SPRING_PROFILES_ACTIVE=postgres` plus `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD` env vars

### Frontend

Vanilla JS SPA served as static files (`src/main/resources/static/`). All user-facing strings are in Russian. The SPA has three role-conditional views: unauthenticated login/register, admin governance panel, and citizen terminal with a live camera scanner (`html5-qrcode`).

### Implementation status

The full service layer is implemented; the project compiles, runs and is deployable. Core services: `GatewayService`, `QrService`, `AuditService`, `IdentityService`, `ValidationService`, `RegistryClient`, plus the `IdeaqrGatewayApplication` main class, `application.properties` and `DataSeeder` (seeds four demo accounts, organizations and memberships).

Stage 4 scenario features are implemented: working mode + sessions, organizations + memberships, SOS, a notification center, identity risk score, and guest identities with history merge — via `SessionService`, `OrganizationService`, `NotificationService`, `GuestService` and the `SessionController` / `NotificationController` / `GuestController` endpoints. Multi-step approval exists as scaffolding (`Workflow` entity + repository, linked to SOS requests).
