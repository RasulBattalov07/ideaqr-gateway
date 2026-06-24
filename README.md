# IDEA QR DIGITAL GATEWAY — Developer Cheat Sheet 🛠️

> Личная шпаргалка создателя для управления MVP и проведения демо.
> Платформа цифровых взаимодействий: **QR — только точка входа, права решает движок, история неизменяема.**

---

## ⚡ Быстрый старт

```bash
# Запуск (нужен JDK 17 + Maven). ВНИМАНИЕ: Lombok ломается на JDK 25 — строго JDK 17.
mvn spring-boot:run            # → http://localhost:8080

mvn clean package              # fat jar → target/ideaqr-gateway.jar
mvn clean test                 # весь сьют (43 теста)
mvn test -Dtest=ИмяКласса      # один класс

# Docker
docker build -t ideaqr-gateway . && docker run -p 8080:8080 ideaqr-gateway
```

- **Сброс БД:** останови приложение и удали папку `./data/` — при следующем старте `DataSeeder` пересоздаст всё (7 аккаунтов, орг-ции, объекты).
- **H2 console (только локально):** http://localhost:8080/h2-console · JDBC `jdbc:h2:file:./data/ideaqr` · user `sa` · пароль пустой.
- **Prod:** профиль `postgres` (`SPRING_PROFILES_ACTIVE=postgres` + `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD`).

---

## 🏛️ Архитектура и Суть

### Стек
| Слой | Технология |
|---|---|
| Язык / Framework | Java 17 · Spring Boot 3.2.5 |
| Web / API | Spring MVC (REST/JSON) + раздача SPA |
| ORM / БД | Spring Data JPA / Hibernate · PostgreSQL (prod) / H2 (dev) |
| Миграции | Flyway `V1…V6` (схема под `ddl-auto=validate` — fail-fast при дрейфе) |
| Security | Spring Security · BCrypt · CSRF (double-submit cookie) |
| Сессии | Spring Session **JDBC** (сессии в БД → облачная устойчивость) |
| Rate limit | Bucket4j (token-bucket на login/guest/register/api) |
| Изоляция | Мультитенантность: ThreadLocal-контекст + Hibernate `@Filter` + `@PrePersist` штамп |
| Frontend | Vanilla JS SPA · self-hosted шрифты · html5-qrcode · тёмная/светлая темы |

### Где что лежит
```
src/main/java/com/ideaqr/gateway/
├── domain/            сущности (Identity, RegistryObject, Request, Decision, Interaction, Event, History, Qr…)
│   └── enums/         все перечисления (категории, статусы, роли, типы)
├── service/           бизнес-логика:
│     GatewayService           ← ядро: scan/report/sos + Owner Approval Flow
│     ValidationService        ← движок политик (роль / доверие / рабочее время)
│     RegistryClient           ← резолв объекта (DB → демо-реестр → префикс)   ← ДЕМО-ОБЪЕКТЫ ЗДЕСЬ
│     ObjectLifecycleService   ← жизненный цикл + transfer (передача владельца)
│     GuestService             ← слияние истории гостя (IDOR-safe merge token)
│     TrustScoreService · AuditService · EventService · UserService · UserAdminService …
├── web/               REST-контроллеры + TenantInterceptor
├── tenant/            TenantContext (ThreadLocal) · TenantFilterAspect (AOP) · TenantListener (@PrePersist)
├── config/            SecurityConfig · RateLimitingFilter · DataSeeder   ← СИДИРОВАНИЕ ЗДЕСЬ
└── resources/
      db/migration/    Flyway V1…V6
      static/          index.html · styles.css · app.js (SPA)
```

### 🔗 Золотой Конвейер
Любое действие проходит единую цепочку — обход запрещён архитектурно:
```
Identity → Role → Request → Decision → Interaction → Event → History → (Trust Score) → Audit
```
`GatewayService.scan()`: резолв объекта → `Request(PENDING)` → `ValidationService` выносит `Decision` (APPROVED/REJECTED/REVIEW) → `Interaction` → многоуровневая видимость (гость = публичная проекция, зарегистрированный = полная) → `Event` + `History` (хэш-цепочка) → пересчёт `Trust Score`. Отказ = HTTP 200 + `success:false` (это бизнес-вердикт, не ошибка).

**Админ = кросс-тенантный супер-админ** (видит и управляет всеми тенантами). Изоляция тенантов действует для всех НЕ-админов.

---

## 🔑 Учётные записи (Credentials)

Все пароли по схеме `Имя123!`. Создаются `DataSeeder` при первом старте.

| Логин | Пароль | Роль / профессия | Тенант (организация) | Для демо |
|---|---|---|---|---|
| `admin` | `Admin123!` | Администратор торговли (**ROLE_ADMIN**) | IDEAQR Retail | Полная админка, управление юзерами, **передача владельца** |
| `seller` | `Seller123!` | Продавец | IDEAQR Retail | Цель передачи авто, управляемый юзер |
| `doctor` | `Doctor123!` | Врач | Городская больница | Доступ к рецепту/медкарте (роль) |
| `pharmacist` | `Pharma123!` | Фармацевт | Городская больница | Доступ к рецептам (роль PHARMACIST) |
| `inspector` | `Inspect123!` | Инспектор инфраструктуры | АО «Астана-РЭК» | Доступ к умному замку (роль) |
| `citizen` | `Citizen123!` | Гражданин | — (public) | Обычный пользователь, P2P, жалобы |
| `aidos` | `Aidos123!` | Продавец (Цифровая визитка) | — (public) | **Владелец визитки** — подтверждает P2P-доступ |

**Гость:** кнопка «Продолжить как гость» на экране входа (без пароля). На форме логина есть кликабельные чипы — клик подставляет логин/пароль.

**Организации (тенанты):** `IDEAQR Retail` (RETAIL) · `Городская больница` (MEDICAL) · `АО «Астана-РЭК»` (INFRASTRUCTURE).

---

## 🎯 Тестовые данные (Cheat Codes)

Скопируй любой ID и вставь в строку «Идентификатор объекта» в терминале (или нажми кнопку быстрого доступа). Это и есть значения, зашитые в QR.

| # | ID (копировать) | Категория | Что показывает | Кем сканировать → вердикт |
|---|---|---|---|---|
| 1 | `RETAIL_NIKE_AF1` | Товар | **Конверсия гостя**: гость видит фото/имя/описание/рейтинг, цена/отзывы скрыты → регистрация → полная карточка | гость → PUBLIC · любой юзер → FULL |
| 2 | `MED_RX_5521` | Медицина | **Ролевой доступ** к рецепту (Амоксициллин) | citizen → ОТКАЗ · doctor/pharmacist → ДОСТУП* |
| 3 | `SERVICE_TRASH_PICKUP` | Услуга | **Request → Decision → Interaction** (вынос мусора от двери) | любой → ДОСТУП (видна цепочка) |
| 4 | `CAR_TOYOTA_CAMRY` | Авто (DB-объект) | **Передача владельца** (Transfer) — реальный объект в базе | citizen → ДОСТУП · admin → передаёт владельца |
| 5 | `LOCK_OFFICE_AITU` | Инфраструктура | **Запрос доступа** (умный замок офиса AITU) | citizen → ОТКАЗ · inspector → ДОСТУП* |
| 6 | `DOC_STUDENT_AITU` | Документ | **Образование** — студбилет AITU | любой → ДОСТУП |
| 7 | `IDENTITY:aaaaaaaa-0000-0000-0000-000000000007` | Личность | **P2P + Trust Score** (визитка «Айдос Серіков», TS 78) | citizen → REVIEW (запрос владельцу) |

\* Медицина и инфраструктура гейтятся ещё и **рабочим временем 08:00–18:00** по серверным часам — вне окна будет отказ `OUTSIDE_WORKING_HOURS`.

### Сценарии демо «по кнопкам»
1. **Guest Conversion** — выйти/гость → скан `RETAIL_NIKE_AF1` → урезанная карточка + кнопка «Зарегистрироваться» → регистрация → та же карточка, но с ценой/отзывами/историей (история гостя сливается).
2. **Owner Approval / P2P** — войти `citizen` → скан `IDENTITY:aaaaaaaa-0000-0000-0000-000000000007` → статус REVIEW, владельцу ушло уведомление. В другой сессии войти `aidos` → «Запросы доступа» → подтвердить → открывается профиль + Trust Score.
3. **Роли (DOCTOR/PHARMACIST)** — `citizen` скан `MED_RX_5521` → ОТКАЗ; `doctor` или `pharmacist` (в рабочее время) → ДОСТУП к рецепту.
4. **Request→Decision→Interaction** — любой логин, скан `SERVICE_TRASH_PICKUP` → анимированный конвейер с UUID каждого звена.
5. **Передача владельца** — `admin` → вкладка «Управление» → карточка `CAR_TOYOTA_CAMRY` → «Передать владельца» → выбрать `seller`/`aidos` (история сохраняется как `OBJECT_TRANSFERRED`).
6. **Access Request (инфра)** — `citizen` скан `LOCK_OFFICE_AITU` → ОТКАЗ; `inspector` (рабочее время) → ДОСТУП.

---

## 🌐 Полезные эндпоинты

| Метод · путь | Назначение |
|---|---|
| `POST /api/auth/register` · `POST /login` · `POST /logout` | Регистрация / вход / выход (JSON 200/401) |
| `POST /api/auth/guest` | Гостевой вход (+ merge-token) |
| `POST /api/v2/scan` | Скан объекта/личности (ядро конвейера) |
| `POST /api/v2/access/{interactionUid}/confirm\|reject` | Owner Approval: подтвердить/отклонить |
| `GET  /api/admin/users` | Список ВСЕХ пользователей (админ — кросс-тенантно) |
| `POST /api/admin/objects/{objectUid}/transfer` | Передача владельца объекта |
| `GET  /api/admin/audit/verify` | Проверка целостности хэш-цепочки журнала |
| `GET  /api/qr/{objectUid}.png` | PNG QR-кода объекта (для печати/демо) |
| `GET  /api/health` | Health-probe (не троттлится) |

> Сгенерировать сканируемый QR для любого cheat-кода: открой `http://localhost:8080/api/qr/RETAIL_NIKE_AF1.png` (подставь нужный ID).

---

## 🧪 Тесты (43, все зелёные)
Ключевые инварианты: `ValidationServiceTest` (политики + фармацевт), `GuestServiceMergeTest` (IDOR-safe merge), `ObjectLifecycleServiceTransferTest` (transfer без пересоздания), `PublicCardTest` (проекция гостя), `AuditServiceChainTest` (tamper-evidence), `TenantIsolationTest` (изоляция на уровне БД), `TenantHttpIsolationTest` (админ видит все тенанты), `ForeignKeyIntegrityTest`, `SecurityIntegrationTest`, `RateLimitingTest`, `UserManagementTest`, `PasswordLifecycleTest`.
