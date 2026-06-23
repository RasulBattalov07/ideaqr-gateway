# IDEA QR DIGITAL GATEWAY
## Полная техническая и бизнес-спецификация (Technical & Business Specification)

> **Платформа цифровых взаимодействий.** QR — это только точка входа; ценность системы — в управляемом доступе и неизменяемой истории взаимодействий между цифровыми сущностями.

| | |
|---|---|
| **Продукт** | IDEA QR Digital Gateway — универсальное ядро цифровых взаимодействий |
| **Стадия** | Investor-stage MVP (production-ready) |
| **Платформа** | Java 17 · Spring Boot 3.2.5 · PostgreSQL / H2 · Flyway · Vanilla-JS SPA |
| **Рынок** | Республика Казахстан (мультиотраслевая платформа) |
| **Состояние** | Скомпилировано, протестировано (42 теста, 0 ошибок), развёрнуто, в репозитории `origin/main` |
| **Документ** | Версия 1.0 · подготовлено CTO / Lead Technical Writer |

---

## Оглавление

1. [Executive Summary — бизнес-ценность](#1-executive-summary--бизнес-ценность)
2. [Архитектура и инфраструктура](#2-архитектура-и-инфраструктура)
3. [База данных и бизнес-логика (Золотой конвейер)](#3-база-данных-и-бизнес-логика-золотой-конвейер)
4. [Безопасность (Security)](#4-безопасность-security)
5. [Ключевые фрагменты кода](#5-ключевые-фрагменты-кода)
6. [Качество, тестирование и развёртывание](#6-качество-тестирование-и-развёртывание)
7. [Приложение: карта сущностей и эндпоинтов](#7-приложение-карта-сущностей-и-эндпоинтов)

---

# 1. Executive Summary — бизнес-ценность

## 1.1. Что это такое (и чем НЕ является)

IDEA QR Digital Gateway — это **не генератор QR-кодов**, **не маркетплейс**, **не CRM** и **не ERP**. Это **универсальное ядро цифровых взаимодействий**, на котором как независимые модули строятся любые отраслевые сценарии: товары, услуги, медицина, транспорт, инфраструктура, логистика, образование, государственные сервисы.

Ключевая инверсия ценности, отличающая платформу от классических систем маркировки:

```
Классическая маркировка:  QR  →  данные (QR несёт информацию и, по сути, права)
Платформа IDEA QR:        QR  →  Identity / Object  →  Request  →  Decision  →  права доступа
```

**QR не является носителем прав.** Его можно сфотографировать, распечатать, переслать — это ничего не даёт. После сканирования система всегда выполняет цепочку проверки прав, и только она решает, какие данные показать конкретному пользователю в конкретном контексте. Главная ценность — **неизменяемая история (audit trail)** того, кто, что, когда и почему сделал.

## 1.2. Главный архитектурный принцип

```
Один пользователь  →  Одна Identity (цифровая личность)  →  Один основной QR
```

Основной QR создаётся один раз при регистрации и сопровождает личность весь жизненный цикл. Объекты (товары, авто, недвижимость, услуги) привязываются к Identity владельца и могут менять владельца без пересоздания — с полным сохранением истории.

Любой сценарий внутри платформы обязан проходить через **единый governance-конвейер** («Золотой конвейер»):

```
Identity → Role → Request → Decision → Interaction → Event → History → (Trust Score) → Audit
```

Создание процессов в обход конвейера запрещено архитектурно — это гарантирует, что любое значимое действие оставляет проверяемый, неизменяемый след.

## 1.3. Бизнес-ценность для инвестора

| Ценность | Как обеспечена технически |
|---|---|
| **Масштабируемость на любую отрасль** | Универсальные сущности (Identity, Object, Request, Decision, Interaction, Event, History). Новый модуль переиспользует ядро, не создавая своей архитектуры. |
| **Доверие и комплаенс** | Неизменяемый журнал с криптографической хэш-цепочкой (tamper-evident audit) — нарушение целостности детектируется. |
| **Готовность к SaaS-модели** | Жёсткая мультитенантность на уровне БД: данные одного клиента физически недоступны другому. |
| **Облачная устойчивость** | Сессии вынесены в БД (Spring Session JDBC) — горизонтальное масштабирование без потери логина при рестартах/балансировке. |
| **Защита от злоупотреблений** | Rate limiting (Bucket4j) на чувствительных эндпоинтах: anti-brute-force, anti-flood. |
| **Конверсия пользователей** | Гостевой режим + бесшовное слияние истории гостя в зарегистрированную личность (Guest Conversion). |
| **Премиальный UX** | Инвестиционно-привлекательный интерфейс с национальной айдентикой — продаёт продукт с первой секунды. |

## 1.4. Национальная айдентика Казахстана в UI

Интерфейс спроектирован как **премиальный финтех-продукт с казахстанским характером** — уровня топовых банковских tier-продуктов, но с узнаваемой национальной идентичностью.

### Дизайн-язык
- **Палитра «Алтын түн» (Золотая ночь).** Глубокий midnight-teal холст (`#081420 → #0A1A28`) — не плоский чёрный, а благородный сине-зелёный. Действие — глубокий тил `#0E7E9E` (белый текст проходит контраст AA). Акцент — яркий sky-teal `#3CC9E8`. Доверие и премиум — «жидкое золото» `#E7B454 / #F3CC73` (прямая отсылка к золоту на флаге РК).
- **Казахский орнамент кошкар-муйiz («бараньи рога»).** Реализован как **inline-SVG** (8-лучевая розетка) — масштабируемый, без внешних ресурсов (CSP-safe). Размещён ненавязчиво: амбиентные розетки в углах страницы (opacity 0.05–0.06), золотой ватермарк на hero-панели входа (opacity 0.10) и тонкий золотой орнаментальный шов под верхним баннером.
- **Glassmorphism — точечно.** Полупрозрачное «стекло» (`backdrop-filter: blur()`) на хедере, тостах, модальных окнах и баннере — только на плавающих слоях, без избыточности.
- **Aurora-глубина.** Фон — не заливка, а три радиальных световых источника (тил + золото + sky), едва бьющих из углов.
- **UX-тренды года.** Воздух (увеличенные отступы и радиусы 10/14/18/26px), чистая типографика, плавные модалки на `cubic-bezier`, hover-свечение кнопок и карточек, светящийся governance-пайплайн как визуальный «герой» демонстрации.

### Типографика (self-hosted, CSP-совместимая)
| Шрифт | Назначение |
|---|---|
| **Manrope** (600–800) | Дисплейные заголовки |
| **Golos Text** (400–700) | Кириллический UI-текст — идеален для русского |
| **JetBrains Mono** (400–600) | UUID, коды, идентификаторы |

Шрифты захостены локально (`/assets/fonts/`) — никаких внешних CDN, работает в закрытых сетях и не нарушает строгий Content-Security-Policy.

### Адаптивность (Mobile-first)
QR сканируют с телефонов, поэтому интерфейс — mobile-first. Широкие таблицы (Пользователи, Аудит) на телефоне **сворачиваются в карточки label→value** (паттерн GOV.UK вместо горизонтального скролла). Камера-сканер с анимированным «лучом» и угловыми скобками прицела.

### Тёмная/светлая темы
Реализован тумблер Dark/Light. Тёмная премиальная тема — по умолчанию; светлая — опциональна. Переключение мгновенное, выбор сохраняется в `localStorage`. Вся тема построена на CSS-переменных — ~200 компонентов перекрашиваются согласованно, без рассинхрона.

---

# 2. Архитектура и инфраструктура

## 2.1. Технологический стек

| Слой | Технология | Назначение |
|---|---|---|
| **Язык / Runtime** | Java 17 (LTS) | Современный, производительный, типобезопасный |
| **Framework** | Spring Boot 3.2.5 | Auto-configuration, DI, web, security |
| **Web** | Spring MVC (REST/JSON) | API + раздача SPA |
| **ORM** | Spring Data JPA + Hibernate | Доступ к данным, repository-абстракция |
| **БД (prod)** | PostgreSQL | Управляемая реляционная БД |
| **БД (local/test)** | H2 (file / in-memory) | Нулевая настройка для разработки и CI |
| **Миграции** | Flyway V1–V6 | Версионируемая, воспроизводимая схема |
| **Security** | Spring Security + BCrypt | Аутентификация, авторизация, защита |
| **Сессии** | Spring Session JDBC | Сессии в БД (облачная устойчивость) |
| **Rate limiting** | Bucket4j | Token-bucket троттлинг |
| **Boilerplate** | Lombok | Геттеры/билдеры/конструкторы |
| **Frontend** | Vanilla JS SPA | Без фреймворков, минимальный bundle |
| **Сканер** | html5-qrcode (self-hosted) | Камера-сканер QR |
| **Контейнеризация** | Docker | Воспроизводимый образ |
| **Деплой** | Render (`render.yaml`) | Managed-хостинг + Postgres |

**Профили окружения:** дефолтный (локальный H2-файл) и `postgres` (активируется `SPRING_PROFILES_ACTIVE=postgres` + переменными окружения `DATABASE_URL`/`DB_*`). Секреты не коммитятся — берутся из окружения.

## 2.2. Мультитенантность (изоляция данных)

Платформа спроектирована как **SaaS** с **жёсткой изоляцией арендаторов (tenant) на уровне БД**: один клиент (организация) физически не может прочитать или записать данные другого. Реализована **defense-in-depth** из четырёх взаимодополняющих механизмов.

```
HTTP-запрос
   │
   ▼
[1] TenantInterceptor.preHandle()      → определяет tenant аутентифицированного
   │                                      пользователя и кладёт в ThreadLocal
   ▼
[2] TenantContext (ThreadLocal<UUID>)  → хранит tenant текущего потока
   │
   ├─[3] TenantFilterAspect (@Before)  → перед КАЖДЫМ вызовом repository включает
   │                                      Hibernate-фильтр `tenant_id = :tenantId`
   │
   └─[4] TenantListener (@PrePersist)  → штампует tenant_id на КАЖДУЮ новую строку
   │
   ▼
afterCompletion() → TenantContext.clear()  → защита от утечки в пул потоков
```

1. **`TenantContext`** — потокобезопасный `ThreadLocal<UUID>`, хранит tenant текущего запроса. Предусмотрен `PUBLIC_TENANT` (нулевой UUID) для системных/гостевых строк, не принадлежащих организации. Контекст **opt-in**: при отсутствии tenant фильтр не включается (это нужно для bootstrap-операций — например, чтобы при логине прочитать пользователя и узнать его tenant).

2. **`TenantInterceptor`** (`HandlerInterceptor`) — на входе каждого запроса резолвит tenant из аккаунта аутентифицированного пользователя и пишет в контекст; в `afterCompletion` **обязательно очищает** контекст, чтобы tenant не «протёк» на переиспользуемый поток пула.

3. **`TenantFilterAspect`** (Spring AOP `@Before`) — включает Hibernate-фильтр `tenantFilter` непосредственно перед каждым вызовом репозитория, на той самой сессии, которую этот вызов использует. Это надёжнее, чем включать фильтр один раз за запрос: гарантирует попадание на живую сессию независимо от того, как привязан EntityManager/транзакция.

4. **`TenantListener`** (`@PrePersist`) — штампует `tenant_id` из контекста на каждую новую `TenantScoped`-сущность в момент вставки. tenant фиксируется на insert и (для append-only журнала) больше не меняется.

На уровне сущностей это поддержано глобальным `@FilterDef` + `@Filter("tenant_id = :tenantId")` и интерфейсом-маркером `TenantScoped`. Полный код — в разделе [5.1](#51-мультитенантность-defense-in-depth).

## 2.3. Управление сессиями (Spring Session JDBC)

Аутентификация — сессионная (cookie `JSESSIONID`), но **сессии хранятся не в памяти узла, а в БД** через **Spring Session JDBC** (таблицы `SPRING_SESSION` / `SPRING_SESSION_ATTRIBUTES`, создаются миграцией V5).

Зачем это критично для облака:
- **Горизонтальное масштабирование.** Любая реплика видит ту же сессию — балансировщик может направить запрос на любой узел.
- **Устойчивость к рестартам.** Перезапуск/передеплой не разлогинивает пользователей.
- **Мгновенный отзыв прав.** `SpringSessionBackedSessionRegistry` позволяет администратору немедленно завершить сессии пользователя при смене привилегий (понижение администратора применяется на следующем же запросе на ЛЮБОЙ реплике, а не «когда-нибудь»).

Cookie остаётся `JSESSIONID` (а не переименовывается в `SESSION`), `HttpOnly`; idle-timeout 30 минут. Spring Session не запускает свой DDL (`initialize-schema=never`) — схемой владеет Flyway.

## 2.4. Защита от DDoS и спама (Bucket4j Rate Limiting)

Чувствительные публичные эндпоинты защищены **token-bucket** лимитером на базе **Bucket4j** (`RateLimitingFilter extends OncePerRequestFilter`). Фильтр включён максимально рано в цепочке Spring Security (до CSRF/auth-обработки), чтобы флуд отбрасывался с минимальной работой.

| Эндпоинт | Лимит (per window) | Ключ бакета | Защита от |
|---|---|---|---|
| `POST /login` | 10 | IP | brute-force паролей |
| `POST /api/auth/guest` | 5 | IP | флуд гостевых аккаунтов |
| `POST /api/auth/register` | 5 | IP | спам-регистрации |
| `/api/**` (прочее) | 100 | username (или IP) | злоупотребление API |
| `GET /api/health` | — | exempt | мониторинг не троттлится |

Окно — 60 секунд, refill — **greedy** (плавный): клиент может «выстрелить» burst до capacity, затем продолжает с rate пополнения. При превышении — `429 Too Many Requests` с локализованным JSON и корректным `Retry-After`. Аутентифицированный трафик ключуется по username (честно при общем NAT); анонимный — по IP (с учётом `X-Forwarded-For` за прокси Render/Heroku). Параметры конфигурируются без пересборки. Полный код — [5.2](#52-rate-limiting-bucket4j).

> **Инженерная честность.** Бакеты держатся per-node в памяти (`ConcurrentHashMap`) — дёшево и без внешних зависимостей. При N репликах эффективный публичный потолок = N×capacity, что приемлемо для MVP; строгий кластерный лимит достигается заменой на распределённый backend Bucket4j (Redis/PostgreSQL). При этом разделяемое **сессионное** состояние — реальный пререкизит мультиузловости — уже решено через Spring Session JDBC.

## 2.5. Версионирование БД (Flyway, V1–V6)

Схемой владеет **Flyway**, а не Hibernate: `spring.jpa.hibernate.ddl-auto=validate`. Это значит, что приложение на старте **верифицирует** соответствие entity-модели мигрированной схеме и **падает на любом расхождении (fail-fast)**, но никогда не мутирует БД само. Это исключает «дрейф схемы» между средами.

| Версия | Назначение |
|---|---|
| **V1** `init_baseline` | Полная базовая схема, сгенерированная из entity-модели; портируемый SQL-сабсет, валидный и на H2, и на PostgreSQL. |
| **V2** `add_foreign_keys` | Настоящие внешние ключи и JPA-ассоциации. |
| **V3** `add_tenant_id` | Колонка `tenant_id` на изолируемых таблицах — фундамент мультитенантности. |
| **V4** `add_user_blocking` | Блокировка пользователей: `blocked`, `blocked_reason`, `blocked_at`. |
| **V5** `spring_session_tables` | Таблицы Spring Session (`SPRING_SESSION*`); тип бинарной колонки параметризован под H2/Postgres через плейсхолдер Flyway. |
| **V6** `align_with_business_specs` | Явное владение объектом: `registry_objects.owner_identity_uid` (+ backfill из создателя) — поддержка передачи владельца без пересоздания объекта. |

Один и тот же набор миграций воспроизводимо строит схему на H2 (dev/test) и PostgreSQL (prod). В тестах Flyway строит свежую in-memory схему V1→V6, после чего Hibernate её валидирует — это и есть реальная проверка, что baseline соответствует модели.

## 2.6. Карта компонентов (высокоуровнево)

```
┌──────────────────────────── Frontend (SPA, vanilla JS) ────────────────────────────┐
│  index.html · styles.css (dark/light, ornament, glass) · app.js · html5-qrcode      │
│  CSRF: XSRF-TOKEN cookie → X-XSRF-TOKEN header. Fetch-based JSON API.                │
└───────────────────────────────────────────┬─────────────────────────────────────────┘
                                             │ HTTPS (JSESSIONID, XSRF-TOKEN)
┌────────────────────────────────────────────▼────────────────────────────────────────┐
│  Spring Security FilterChain                                                          │
│   RateLimitingFilter → CsrfFilter → SecurityContext → Authorization (URL + @PreAuth)  │
├───────────────────────────────────────────────────────────────────────────────────────┤
│  Web layer (REST controllers)                                                         │
│   Auth · Gateway(scan/report/sos) · Guest · QrAdmin · ObjectAdmin · UserAdmin ·       │
│   Admin(stats/analytics/users/audit) · Session · Notification · History · Complaint   │
│   + TenantInterceptor (per-request tenant)                                            │
├───────────────────────────────────────────────────────────────────────────────────────┤
│  Service layer (бизнес-логика)                                                        │
│   GatewayService · ValidationService · QrService · ObjectLifecycleService ·           │
│   GuestService · TrustScoreService · AuditService · EventService · NotificationService│
│   · SessionService · OrganizationService · UserService/UserAdminService · RegistryClient│
├───────────────────────────────────────────────────────────────────────────────────────┤
│  Tenancy aspect (TenantFilterAspect @Before repository.*)                             │
├───────────────────────────────────────────────────────────────────────────────────────┤
│  Persistence (Spring Data JPA / Hibernate)  — @Filter tenantFilter, @PrePersist stamp │
├───────────────────────────────────────────────────────────────────────────────────────┤
│  RDBMS (PostgreSQL / H2)  — схема под управлением Flyway V1..V6                        │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

---

# 3. База данных и бизнес-логика (Золотой конвейер)

## 3.1. Принципы модели данных

- **Связи как UUID-поля (там, где нужна гибкость).** Часть отношений хранится плоскими UUID-полями, а не JPA-ассоциациями — это позволяет, например, переуказать историю гостевой личности на основную без каскадных эффектов.
- **Настоящие FK там, где нужна целостность.** Где ссылочная целостность важна (например, `qrs.owner_identity_uid → identities`, side-таблицы element-collection), стоят реальные внешние ключи (V2). Тест `ForeignKeyIntegrityTest` подтверждает, что БД отвергает «висячие» строки.
- **Append-only журналы.** `History` и `Event` только дополняются — никогда не обновляются и не удаляются.
- **Soft-delete вместо удаления.** Объекты не удаляются — переводятся в статус `ARCHIVED` (история сохраняется).

## 3.2. Ключевые сущности

| Сущность | Таблица | Роль в платформе |
|---|---|---|
| **Identity** | `identities` | Цифровая личность — центральный субъект. Типы: `PRIMARY`, `GUEST`. Несёт роли, trustLevel, riskScore, trustScore, ссылку на основной QR, список слитых гостевых UID. |
| **User** | `users` | Аккаунт Spring Security (BCrypt), связан с Identity по `identity_uid`. Профессия → роли. |
| **Qr** | `qrs` | QR под управлением. Типы `PRIMARY`/`OBJECT`. Чистый идентификатор — без ролей и прав. FK на владельца. |
| **RegistryObject (Universal Object)** | `registry_objects` | Универсальный объект: товар, авто, недвижимость, услуга, оборудование, инфраструктура. Категория, статус жизненного цикла, владелец, JSON-карточка, trustScore. |
| **RequestRecord** | `requests` | Запрос, входящий в конвейер. Никакое действие не выполняется напрямую — сначала становится Request. |
| **Decision** | `decisions` | Результат оценки Request движком политик: `APPROVED`/`REJECTED`/`REVIEW` + причина + риск. |
| **Interaction** | `interactions` | Зафиксированное взаимодействие. Статусы `PENDING`/`CONFIRMED`/`REJECTED`; несёт targetIdentityUid для person-to-person. |
| **Event** | `events` | Машиночитаемый факт унифицированной event-модели (кто/что/когда) — для аналитики, уведомлений, будущего AI-слоя. |
| **History** | `histories` | Человекочитаемый append-only жур-нал со SHA-256 хэш-цепочкой (tamper-evident). |
| **Organization / Membership** | `organizations`, `organization_memberships` | Организация как участник платформы + членство пользователей (рабочие роли). |
| **Notification** | `notifications` | Уведомления (например, владельцу о запросе доступа). |
| **Complaint** | `complaints` | Жалоба, привязанная к Interaction. Статусы NEW/IN_PROGRESS/RESOLVED/REJECTED. |
| **PlatformModule** | `platform_modules` | Отраслевой модуль (вкл/выкл). |
| **UserSession** | `user_sessions` | Рабочий/личный режим, активная организация и роль. |
| **Workflow** | `workflows` | Каркас многошагового согласования (связан с SOS). |
| **Assignment** | `assignments` | Назначение QR ↔ Identity (роль управления). |

### Trust Score — взвешенное инженерное решение
Trust Score (0–100) реализован как **лёгкая, объяснимая модель** в `TrustScoreService`:
```
score = base(trustLevel) + 2·подтверждённыеInteraction − 8·жалобы   → clamp[0..100]
```
Расчёт **alias-aware** (учитывает слитые гостевые личности). Кэшируется в колонке (на read-путях не пересчитывается — никакой работы на GET). **Важное архитектурное решение:** финальное ТЗ заказчика прямо отнесло «тяжёлый» Trust Score к списку «не реализовывать в MVP», поэтому модель сделана намеренно простой и заменяемой, без инвестиций в полноценный репутационный модуль — это сознательный выбор в пользу фокуса MVP.

## 3.3. Золотой конвейер (на примере сканирования объекта)

`GatewayService.scan(...)` реализует полный конвейер:

```
1. RegistryClient.resolve(objectUid)
      разрешает идентификатор: (1) объекты из БД → (2) обогащённый демо-реестр → (3) инференс по префиксу
2. RequestRecord (тип ACCESS, статус PENDING)
3. ValidationService.decideAccess(...)
      оценивает: категорию объекта, роль, уровень доверия, рабочее время (server-side)
      → Verdict { outcome, reasonCode, reason, riskLevel }
4. Decision (APPROVED / REJECTED / REVIEW)
5. Interaction (фиксация факта)
6. Request → PROCESSED / FAILED
7. Многоуровневая видимость (Scenario #1): GUEST → публичная проекция карточки;
      зарегистрированный → полная карточка
8. History (append-only, в хэш-цепочку) + Event (машинный факт)
9. Trust Score пересчитывается (… → History → Trust Score)
10. Ответ: verdict + локализованная причина + уровень риска + полная цепочка UUID
```

Отклонённый скан возвращает **HTTP 200** с `success:false` — решения governance не являются HTTP-ошибками, это бизнес-вердикты. Политики (`ValidationService`): медицина требует роли `DOCTOR` + достаточного доверия + рабочего времени; инфраструктура — `INSPECTOR`/`ENGINEER`; розница/эко/общие — публичны. **Гейт рабочего времени вычисляется только по серверным часам** (инъекция `Clock`) — клиент не может его подделать.

## 3.4. Owner Approval Flow (подтверждение доступа владельцем)

Сценарий person-to-person: пользователь А сканирует **основной QR** пользователя Б. Сканирование — это **только просмотр**, оно ничего не авторизует.

```
А сканирует QR Б (IDENTITY:<uuid>)
   │
   ▼
GatewayService.scanIdentityProfile()
   ├─ Request (тип ACCESS, статус REVIEW)
   ├─ Decision (REVIEW, reasonCode = OWNER_CONFIRMATION_REQUIRED)
   ├─ Interaction (PROFILE_SCAN, статус PENDING, targetIdentityUid = Б)
   ├─ History (PROFILE_ACCESS_REQUESTED) + Event (QR_VIEWED, ACCESS_REQUESTED)
   └─ Notification → владельцу Б («Подтвердите или отклоните»)
   │
   ▼  Б решает:
   ├─ confirmProfileAccess()  → Interaction CONFIRMED, Decision APPROVED,
   │                            Request APPROVED, открывается расширенный профиль,
   │                            Trust Score владельца пересчитывается
   └─ rejectProfileAccess()   → Interaction REJECTED, Decision REJECTED
```

Защита от подделки: `requirePendingTarget(...)` проверяет, что запрос действительно адресован этому владельцу (`targetIdentityUid`) и ещё не обработан. Расширенная информация открывается **только после явного подтверждения владельцем**. Эндпоинты: `POST /api/v2/access/{interactionUid}/confirm|reject`, список ожидающих — `GET /api/v2/access/pending`.

## 3.5. Guest Conversion (гость → регистрация → слияние истории)

Это сценарий №1 заказчика — воронка конверсии, встроенная в ядро.

```
1. Незарегистрированный посетитель сканирует QR
2. POST /api/auth/guest → создаётся Guest Identity (тип GUEST) + аутентифицированная
   сессия + одноразовый MERGE-TOKEN, выданный ТОЛЬКО этому браузеру
3. Гость сканирует объект → APPROVED, но получает ТОЛЬКО публичную проекцию карточки
   (имя, изображение, краткое описание, рейтинг). Цена, отзывы, история, поставщик —
   скрыты (PublicCard, default-deny whitelist). accessTier = PUBLIC.
4. Под карточкой — премиальный CTA «Зарегистрироваться»
5. Гость регистрируется → создаётся PRIMARY Identity + основной QR + личный кабинет
6. GuestService.merge(target, guestUid, mergeToken):
      • проверяет одноразовый токен (доказательство владения гостевой сессией)
      • НЕ переписывает append-only журнал гостя, а добавляет его UID как soft-alias
        к основной личности; read-пути объединяются по alias
      • гостевая личность переводится в SUSPENDED, токен сжигается (single-use)
7. Теперь тот же скан → accessTier = FULL: видны цена, отзывы, история, поставщик
```

**Почему это сильное решение:** слияние реализовано как **append-only alias**, а не перезапись истории — это сохраняет неизменяемость журнала и закрывает класс IDOR-уязвимостей (см. [5.3](#53-guest-conversion-idor-safe-merge)). Многоуровневая видимость («ПУБЛИЧНАЯ СТРАНИЦА» из ТЗ) — единое правило для любого объекта любого модуля.

## 3.6. Object Lifecycle и передача владельца

Каждый объект проходит жизненный цикл `CREATED → ACTIVE → MODIFIED → ARCHIVED`, и **каждый переход проходит через тот же governance-конвейер** (`ObjectLifecycleService`), пополняя неизменяемую историю.

**Передача владельца (сценарий продажи).** `transfer(actor, objectUid, newOwner, note)`: объект **не пересоздаётся** — меняется `ownerIdentityUid`, статус → `MODIFIED`, а факт фиксируется как `OBJECT_TRANSFERRED` в журнале. Полная «цепочка владения» (chain of custody) сохраняется. Эндпоинт: `POST /api/admin/objects/{objectUid}/transfer`. Это прямая реализация принципа ТЗ «после продажи система не создаёт новый объект — изменяется владелец, история сохраняется».

---

# 4. Безопасность (Security)

Платформа прошла внутренний security-аудит; ниже — модель и закрытые классы уязвимостей.

## 4.1. Аутентификация и авторизация
- **Пароли** — BCrypt (адаптивный hash). Аккаунты постоянные.
- **Сессии** — `JSESSIONID`, `HttpOnly`; хранятся в БД (Spring Session JDBC).
- **Авторизация двухслойная:** URL-matcher (`/api/admin/**` → `hasRole('ADMIN')`, прочее `/api/**` → `authenticated()`) **плюс** метод-level `@PreAuthorize("hasRole('ADMIN')")` на админ-контроллерах (вторая линия защиты сверх URL-правила).
- **Ответы — JSON (200/401), а не редиректы** — корректно для SPA на fetch.

## 4.2. Ролевая модель
Профессия → роли + уровень доверия (`UserService`). Роли: `CITIZEN`, `SELLER`, `SERVICE_OPERATOR`, `PHARMACIST`, `DOCTOR`, `INSPECTOR`, `ENGINEER`, `RETAIL_ADMIN`, `OBJECT_OWNER`, `ORG_STAFF`, `ADMIN`. **Самостоятельная регистрация всегда создаёт только `CITIZEN`** — специализированные и админ-роли назначает администратор после проверки (привилегированный путь `POST /api/admin/users/{username}/profession`). Архитектура позволяет добавлять роли без изменения ядра.

## 4.3. Закрытые классы уязвимостей

| Угроза | Решение |
|---|---|
| **Cross-tenant доступ** | Жёсткая изоляция на уровне БД (раздел 2.2): фильтр Hibernate + штамп tenant + ThreadLocal-контекст. |
| **CSRF** | Double-submit cookie: сервер выдаёт `XSRF-TOKEN`, SPA возвращает его в заголовке `X-XSRF-TOKEN`. Cookie-сессионные POST нельзя подделать со стороннего сайта. |
| **Brute-force / флуд** | Rate limiting (раздел 2.4): жёсткие IP-лимиты на login/guest/register. |
| **IDOR при слиянии гостя** | Одноразовый merge-token как доказательство владения сессией; знание UID гостя недостаточно. |
| **Подделка «рабочего времени»** | Гейт времени — только серверные часы (инъекция `Clock`), нет клиентского параметра. |
| **Session fixation** | Ротация session id при аутентификации (`changeSessionId`). |
| **«Залипшие» привилегии** | `SpringSessionBackedSessionRegistry` — отзыв сессий на смене прав действует на всех репликах немедленно. |
| **Подделка журнала** | SHA-256 хэш-цепочка + `verifyChain()` — любое редактирование/удаление детектируется. |
| **Раскрытие H2-консоли** | Консоль маршрутизируется и фреймится same-origin только в dev (`spring.h2.console.enabled`); в prod путь не разрешён, фрейминг запрещён. |
| **Clickjacking** | `frame-options: DENY` в prod. |
| **Утечка данных через заголовки** | Строгий **Content-Security-Policy** в prod (`default-src 'self'`, `script-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`), HSTS, Referrer-Policy. Никаких внешних ресурсов — всё self-hosted. |
| **Сброс пароля / навязанная смена** | Самосервисная смена пароля; форс-смена после админ-сброса (одноразовый временный пароль показывается один раз). |
| **Раскрытие демо-паролей** | Пароли демо-аккаунтов не печатаются на экране входа (только в README для оценщиков). |
| **Блокировка пользователя** | Блокировка с причиной (V4): заблокированный не может войти и выполнять запросы. |

## 4.4. Неизменяемый аудит (tamper-evidence)
`AuditService` пишет только вставки — нет операций update/delete. Каждая запись связана в SHA-256 хэш-цепочку (`prevHash → entryHash`), а `createdAt` форсируется строго монотонным, чтобы порядок вставки был однозначен. `verifyChain()` пересчитывает всю цепочку от генезиса и возвращает первую нарушенную запись — «неизменяемость журнала» не декларируется, а **проверяется** (эндпоинт `GET /api/admin/audit/verify`). Чтения alias-aware: журнал личности включает историю слитых гостевых личностей.

---

# 5. Ключевые фрагменты кода

> Реальные фрагменты из кодовой базы — для оценки инженерного уровня.

## 5.1. Мультитенантность (defense-in-depth)

**Потоковый контекст (`TenantContext`):**
```java
public final class TenantContext {
    /** Fixed tenant for non-organisation data: citizens, guests and system rows. */
    public static final UUID PUBLIC_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    public static void setTenantId(UUID tenantId) { CURRENT.set(tenantId); }
    public static UUID getTenantId() { return CURRENT.get(); }

    /** The tenant to stamp on a new row: the current tenant, or the public tenant. */
    public static UUID currentOrPublic() {
        UUID tenantId = CURRENT.get();
        return tenantId != null ? tenantId : PUBLIC_TENANT;
    }
    /** Must be called at the end of every request to avoid thread-pool leakage. */
    public static void clear() { CURRENT.remove(); }
}
```

**Прозрачное включение фильтра на каждый вызов репозитория (`TenantFilterAspect`):**
```java
@Aspect
@Component
public class TenantFilterAspect {
    @PersistenceContext
    private EntityManager entityManager;

    @Before("execution(* com.ideaqr.gateway.repository..*(..))")
    public void enableTenantFilter() {
        UUID tenant = TenantContext.getTenantId();
        if (tenant == null) {
            return; // system/bootstrap: run unscoped (e.g. resolve a user to learn their tenant)
        }
        try {
            entityManager.unwrap(Session.class)
                    .enableFilter("tenantFilter")
                    .setParameter("tenantId", tenant);
        } catch (Exception ignored) {
            // No session bound — nothing to scope.
        }
    }
}
```

**Автоштамп tenant на каждую новую строку (`TenantListener`):**
```java
public class TenantListener {
    @PrePersist
    public void stampTenant(Object entity) {
        if (entity instanceof TenantScoped scoped && scoped.getTenantId() == null) {
            scoped.setTenantId(TenantContext.currentOrPublic());
        }
    }
}
```

**Объявление фильтра на сущности (пример — `Identity`):**
```java
@Entity
@Table(name = "identities")
@EntityListeners(TenantListener.class)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = UUID.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class Identity implements TenantScoped {
    @Column(name = "tenant_id")
    private UUID tenantId;
    // ...
}
```

## 5.2. Rate limiting (Bucket4j)

```java
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_KEYS = 50_000;
    private final RateLimitProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) { chain.doFilter(request, response); return; }

        BucketSpec spec = bucketFor(request);
        if (spec == null) { chain.doFilter(request, response); return; }

        ConsumptionProbe probe = resolveBucket(spec).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-Rate-Limit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value()); // 429
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.getWriter().write(
                "{\"success\":false,\"message\":\"Слишком много запросов. Повторите попытку позже.\"}");
    }

    /** The bucket key + capacity for this request, or null if it is not throttled. */
    private BucketSpec bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = HttpMethod.POST.matches(request.getMethod());
        if (post && "/login".equals(path))              return new BucketSpec("login:"    + clientIp(request), properties.getLoginCapacity());
        if (post && "/api/auth/guest".equals(path))      return new BucketSpec("guest:"    + clientIp(request), properties.getGuestCapacity());
        if (post && "/api/auth/register".equals(path))   return new BucketSpec("register:" + clientIp(request), properties.getRegisterCapacity());
        if (path.startsWith("/api/") && !path.equals("/api/health"))
            return new BucketSpec("api:" + principalOrIp(request), properties.getAuthenticatedCapacity());
        return null;
    }

    private Bucket newBucket(int capacity) {
        Duration window = Duration.ofSeconds(properties.getWindowSeconds());
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .build();
    }
}
```

## 5.3. Guest Conversion (IDOR-safe merge)

```java
@Service
@RequiredArgsConstructor
public class GuestService {

    @Transactional
    public int merge(Identity target, UUID guestIdentityUid, String mergeToken) {
        if (guestIdentityUid.equals(target.getIdentityUid())) {
            throw new IllegalArgumentException("Нельзя объединить личность с самой собой.");
        }
        Identity guest = identityRepository.findById(guestIdentityUid)
                .orElseThrow(() -> new IllegalArgumentException("Гостевая личность не найдена."));
        if (guest.getIdentityType() != IdentityType.GUEST) {
            throw new IllegalArgumentException("Указанная личность не является гостевой.");
        }
        // Ownership proof: the caller must present the one-time token issued to THIS
        // guest's browser. Knowing the guest UID is not sufficient (closes the IDOR).
        String expected = guest.getMergeTokenHash();
        if (mergeToken == null || expected == null
                || !Hashing.constantTimeEquals(expected, Hashing.sha256Hex(mergeToken))) {
            throw new AccessDeniedException("Недостаточно прав для объединения этой гостевой личности.");
        }
        // Append-only alias instead of rewriting the guest's immutable journal.
        long movedHistory = historyRepository.findByIdentityUid(guestIdentityUid).size();
        target.getLinkedGuestUids().add(guestIdentityUid);
        identityRepository.save(target);
        // Retire (not delete) the guest identity and burn the token (single use).
        guest.setStatus(IdentityStatus.SUSPENDED);
        guest.setMergeTokenHash(null);
        identityRepository.save(guest);

        auditService.record(target.getIdentityUid(), null, HistoryEventType.GUEST_MERGED,
                "История гостевой личности связана с основным профилем. Событий перенесено: " + movedHistory + ".");
        eventService.record(EventType.GUEST_MERGED, target.getIdentityUid(),
                "Объединение истории гостя (" + movedHistory + " событий).");
        return (int) movedHistory;
    }
}
```

## 5.4. Tamper-evident audit (SHA-256 hash chain)

```java
@Transactional
public synchronized History record(UUID identityUid, String objectUid, HistoryEventType eventType,
                                   String description, UUID requestUid, UUID decisionUid, UUID interactionUid) {
    History tip = historyRepository.findTopByOrderByCreatedAtDescHistoryUidDesc();
    String prevHash = tip != null ? tip.getEntryHash() : GENESIS;

    UUID historyUid = UUID.randomUUID();
    LocalDateTime createdAt = LocalDateTime.now();
    if (tip != null && !createdAt.isAfter(tip.getCreatedAt())) {
        createdAt = tip.getCreatedAt().plusNanos(1); // strictly monotonic → unambiguous order
    }
    String entryHash = computeHash(prevHash, historyUid, identityUid, objectUid, eventType,
            description, requestUid, decisionUid, interactionUid, createdAt);
    return historyRepository.save(History.builder()
            .historyUid(historyUid).identityUid(identityUid).objectUid(objectUid)
            .eventType(eventType).description(description)
            .requestUid(requestUid).decisionUid(decisionUid).interactionUid(interactionUid)
            .prevHash(prevHash).entryHash(entryHash).createdAt(createdAt)
            .build());
}

/** Recompute the whole chain from genesis; returns the first broken link, if any. */
public ChainVerification verifyChain() {
    List<History> all = historyRepository.findAllByOrderByCreatedAtAscHistoryUidAsc();
    String expectedPrev = GENESIS;
    long checked = 0;
    for (History h : all) {
        String recomputed = computeHash(h.getPrevHash(), h.getHistoryUid(), h.getIdentityUid(),
                h.getObjectUid(), h.getEventType(), h.getDescription(), h.getRequestUid(),
                h.getDecisionUid(), h.getInteractionUid(), h.getCreatedAt());
        if (!Objects.equals(expectedPrev, h.getPrevHash()) || !Objects.equals(recomputed, h.getEntryHash())) {
            return new ChainVerification(false, checked, String.valueOf(h.getHistoryUid()));
        }
        expectedPrev = h.getEntryHash();
        checked++;
    }
    return new ChainVerification(true, checked, null);
}
```

## 5.5. Tiered visibility — публичная проекция (Scenario #1)

```java
public final class PublicCard {
    /** Lower-cased names of the only fields a guest is allowed to see on any card. */
    private static final Set<String> PUBLIC_FIELDS = Set.of(
            "title", "displayname", "name", "productname", "brand",
            "photo", "image", "imageurl", "images", "cover",
            "description", "shortdescription", "summary", "rating");

    /** Default-deny projection: only whitelisted public fields survive. */
    public static Map<String, Object> project(Map<String, Object> full) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (full == null) return out;
        for (Map.Entry<String, Object> e : full.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            if (PUBLIC_FIELDS.contains(key)) out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
```

---

# 6. Качество, тестирование и развёртывание

## 6.1. Автоматические тесты (42 теста, 0 ошибок)
Покрыты критические инварианты безопасности и бизнес-логики:

| Тест | Что фиксирует |
|---|---|
| `ValidationServiceTest` | Политики доступа (роль, доверие, рабочее время, публичные категории). |
| `GuestServiceMergeTest` | IDOR-safe слияние: неверный токен отвергается; верный — алиасит гостя и сжигает токен. |
| `ObjectLifecycleServiceTransferTest` | Передача владельца не пересоздаёт объект; пишет `OBJECT_TRANSFERRED`. |
| `PublicCardTest` | Гость видит только публичные поля; цена/отзывы/поставщик скрыты. |
| `AuditServiceChainTest` | Хэш-цепочка цела; подмена записи детектируется. |
| `ForeignKeyIntegrityTest` | БД отвергает «висячие» строки (реальные FK). |
| `TenantIsolationTest`, `TenantHttpIsolationTest` | Один tenant не видит данные другого (на уровне сервиса и HTTP). |
| `SecurityIntegrationTest`, `RateLimitingTest` | Авторизация эндпоинтов; срабатывание 429. |
| `UserManagementTest`, `PasswordLifecycleTest`, `UserServiceRegistrationTest`, `UserServiceChangePasswordTest`, `HashingTest` | Управление пользователями, жизненный цикл пароля, утилиты. |

Тесты используют изолированную in-memory H2, Flyway строит схему V1→V6, Hibernate её валидирует — то есть тест-сьют проверяет и соответствие baseline entity-модели на каждом прогоне.

## 6.2. Развёртывание
- **Локально:** `mvn spring-boot:run` (порт 8080), файловый H2, dev H2-консоль.
- **Docker:** `docker build -t ideaqr-gateway .` → `docker run -p 8080:8080 ideaqr-gateway`.
- **Production:** профиль `postgres` + переменные окружения; Render (`render.yaml`) поднимает приложение и managed PostgreSQL. Flyway мигрирует схему на старте, Hibernate валидирует (fail-fast).

## 6.3. Сборка и инструментарий
Maven, Java 17. Команды: `mvn clean package` (fat jar), `mvn test` (сьют), `mvn test -Dtest=<Class>` (один класс).

---

# 7. Приложение: карта сущностей и эндпоинтов

## 7.1. Перечисления (enums)
- **IdentityType:** PRIMARY, GUEST
- **IdentityStatus:** ACTIVE, SUSPENDED, PENDING
- **RoleType:** CITIZEN, DOCTOR, ENGINEER, INSPECTOR, RETAIL_ADMIN, OBJECT_OWNER, ORG_STAFF, ADMIN, PHARMACIST, SELLER, SERVICE_OPERATOR
- **ObjectCategory:** MEDICAL, RETAIL, ECO, INFRASTRUCTURE, GENERAL, UNKNOWN
- **ObjectStatus:** CREATED, ACTIVE, MODIFIED, ARCHIVED
- **RequestType:** ACCESS, QR_CREATION, REPORT_ISSUE, SOS, WORKING_MODE, OBJECT_LIFECYCLE
- **RequestStatus:** PENDING, PROCESSED, FAILED, NEW, REVIEW, APPROVED, REJECTED, COMPLETED
- **DecisionOutcome:** APPROVED, REJECTED, REVIEW
- **InteractionStatus:** PENDING, CONFIRMED, REJECTED
- **ComplaintStatus:** NEW, IN_PROGRESS, RESOLVED, REJECTED
- **EventType / HistoryEventType:** USER_REGISTERED, IDENTIFIER/QR_VIEWED, ACCESS_REQUESTED/CONFIRMED/REJECTED, OBJECT_CREATED/MODIFIED/ARCHIVED/TRANSFERRED, GUEST_CREATED/MERGED, SOS_CREATED, COMPLAINT_CREATED, и др.

## 7.2. Основные эндпоинты

| Группа | Метод · путь | Назначение |
|---|---|---|
| **Auth** | `POST /api/auth/register` | Регистрация (всегда CITIZEN) |
| | `POST /login` · `POST /logout` | Вход / выход (JSON 200/401) |
| | `POST /api/auth/guest` | Гостевой вход + merge-token |
| | `GET /api/auth/me` | Текущий пользователь |
| | `POST /api/auth/change-password` | Самосервисная смена пароля |
| **Gateway** | `POST /api/v2/scan` | Скан объекта/личности (конвейер) |
| | `POST /api/v2/report` | Обращение по объекту |
| | `POST /api/v2/sos` | SOS-эскалация |
| **Access** | `GET /api/v2/access/pending` | Входящие запросы доступа |
| | `POST /api/v2/access/{id}/confirm\|reject` | Подтверждение/отклонение владельцем |
| **Guest** | `POST /api/v2/guest/merge` | Слияние истории гостя |
| **Citizen** | `GET /api/v2/my-qr`, `/history/me`, `/session`, `/notifications`, `/complaints/me` | Личный кабинет |
| **Admin (ROLE_ADMIN)** | `POST /api/admin/qr/create` · `GET /api/admin/qr/list` | Создание/список объектов |
| | `POST /api/admin/objects/{uid}/activate\|modify\|archive\|transfer` | Жизненный цикл + передача владельца |
| | `GET /api/admin/users` · `POST /api/admin/users/{u}/block\|unblock\|role\|profession\|reset-password` | Управление пользователями |
| | `GET /api/admin/stats\|analytics\|complaints\|modules\|events` | Статистика, аналитика, модули |
| | `GET /api/admin/audit/verify` | Проверка целостности журнала |
| **System** | `GET /api/health` | Health-probe (не троттлится) |

---

## Заключение

IDEA QR Digital Gateway — это не демонстрация одного экрана, а **масштабируемое ядро платформы цифровых взаимодействий** с продуманной инженерией на каждом слое: жёсткая мультитенантность, облачно-устойчивые сессии, защита от злоупотреблений, версионируемая схема, неизменяемый проверяемый аудит и премиальный интерфейс с национальной айдентикой. Универсальные сущности и единый governance-конвейер позволяют наращивать любые отрасли, **не переписывая ядро** — именно это превращает MVP в фундамент полноценной платформы.

> *Подготовлено в роли CTO / Lead Technical Writer. Все архитектурные утверждения соответствуют фактической кодовой базе репозитория на момент версии 1.0 документа.*
