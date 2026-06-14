# IDEAQR Digital Gateway — Stage 2

Identity-centric digital trust platform. Replaces the Stage-1 prefix-check prototype
with a full pipeline:

```
Identity ──> Request ──> Decision ──> QR Creation ──> Assignment ──> Interaction ──> History
```

- **Spring Boot 3.2 / Java 17 / JPA (Hibernate) / H2 in-memory**
- UUID primary keys on all new entities
- Append-only `History` audit journal (JSON payloads)
- Advanced rules engine (time-window + multi-role evaluation)
- Dynamic GUEST provisioning + guest→primary migration
- Dockerized, tuned for the Render free tier (512 MB)

---

## Build & run locally

```bash
mvn clean package
java -jar target/ideaqr-gateway.jar
# or
mvn spring-boot:run
```

App starts on `http://localhost:8080`. H2 console at `/h2-console`
(JDBC URL `jdbc:h2:mem:ideaqr`, user `sa`, empty password).

Two demo identities are seeded on boot:

| identityUid | roles |
|---|---|
| `11111111-1111-1111-1111-111111111111` | ENGINEER, CITIZEN |
| `22222222-2222-2222-2222-222222222222` | DOCTOR, CITIZEN |

---

## API (base path `/api/v2`)

| Method | Path | Purpose |
|---|---|---|
| POST | `/scan` | End-to-end scan pipeline |
| POST | `/identities` | Register a PRIMARY identity |
| GET | `/identities` | List identities |
| GET | `/identities/{identityUid}` | Fetch one identity |
| POST | `/identities/migrate` | Migrate GUEST → PRIMARY |
| POST | `/qr` | Register a QR (requires APPROVED decision) |
| POST | `/assignments` | Bind identity + QR to an object |
| GET | `/audit` | Read append-only history journal |
| GET | `/health` | Liveness probe |

### Example: registered engineer accessing infrastructure

```bash
curl -X POST http://localhost:8080/api/v2/scan \
  -H "Content-Type: application/json" \
  -d '{
        "identityUid": "11111111-1111-1111-1111-111111111111",
        "objectUid": "INFRA_WAREHOUSE_7",
        "requestType": "INFRASTRUCTURE_ACCESS",
        "interactionType": "PHYSICAL_GATE"
      }'
```

Returns `APPROVED` only between 08:00–18:00 (server local time); otherwise `REJECTED`
with a time-window reason. On approval the response carries mock KZ registry data.

### Example: anonymous (guest) scan

```bash
curl -X POST http://localhost:8080/api/v2/scan \
  -H "Content-Type: application/json" \
  -d '{ "objectUid": "CIV_PORTAL_1", "requestType": "OBJECT_ACCESS" }'
```

A GUEST identity is created on the fly; note the returned `identityUid`.

### Example: migrate that guest into a permanent identity

```bash
curl -X POST http://localhost:8080/api/v2/identities/migrate \
  -H "Content-Type: application/json" \
  -d '{
        "guestIdentityUid": "<uid-from-previous-response>",
        "roles": ["CITIZEN"]
      }'
```

All of the guest's `Request` and `Interaction` rows are re-pointed onto the new
`PRIMARY` identityUid; an `IDENTITY_MERGED` event is appended to history.

### Example: QR governance

QR creation is gated. First obtain an APPROVED decision via a `QR_CREATION` scan,
then register the QR using the returned `requestUid`:

```bash
# 1. approved QR_CREATION request
curl -X POST http://localhost:8080/api/v2/scan \
  -H "Content-Type: application/json" \
  -d '{ "identityUid":"11111111-1111-1111-1111-111111111111",
        "objectUid":"SELF", "requestType":"QR_CREATION" }'

# 2. register the QR (use requestUid from step 1)
curl -X POST http://localhost:8080/api/v2/qr \
  -H "Content-Type: application/json" \
  -d '{ "identityUid":"11111111-1111-1111-1111-111111111111",
        "requestUid":"<requestUid>", "qrType":"MAIN" }'
```

Calling `/qr` with a `requestUid` that has no APPROVED decision returns `409 Conflict`.

---

## Rules engine summary

1. **Time window** — `ENGINEER` access to `INFRA_*` objects allowed only 08:00–18:00; outside → `REJECTED`.
2. **Multi-role** — the identity must hold the role governing the object's prefix
   (`MED_`→DOCTOR, `INFRA_`→ENGINEER, `FIN_`→FINANCIER, `CIV_`→CITIZEN, `ADM_`→ADMIN),
   regardless of default context.
3. **Request-type coherence** — e.g. a medical object rejects a finance request type.
4. **Unknown prefix** → `REVIEW` (manual).
5. **QR creation** — approved for any identity with ≥1 role; roleless → `REVIEW`.

---

## Deploy to Render

This repo is a Docker Web Service.

1. Push to GitHub.
2. In Render: **New → Blueprint**, point at the repo. `render.yaml` is detected.
3. Render builds the `Dockerfile` and deploys, health-checking `/api/v2/health`.

The Dockerfile sets JVM flags for the 512 MB free tier:

```
-XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError
```

`PORT` is supplied by Render and honoured via `server.port=${PORT:8080}`.

> Note: H2 is in-memory, so data resets on each deploy/restart. Swap the datasource
> for Postgres for persistence.
