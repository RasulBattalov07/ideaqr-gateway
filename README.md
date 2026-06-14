# IDEAQR Digital Gateway — MVP

> Архитектурный слой маршрутизации цифровых идентификаторов.  
> Проект «Цифровой Казахстан». Stack: Java 17 · Spring Boot 3.2 · H2 · Vanilla JS.

---

## Структура проекта

```
ideaqr-gateway/
├── Dockerfile
├── render.yaml
├── pom.xml
└── src/main/
    ├── java/kz/ideaqr/gateway/
    │   ├── IdeaqrApplication.java
    │   ├── config/
    │   │   └── SecurityConfig.java       # CSRF off, H2 console разрешён
    │   ├── controller/
    │   │   └── GatewayController.java    # POST /scan, GET /audit, GET /health
    │   ├── dto/
    │   │   ├── ScanRequest.java
    │   │   └── GatewayResponse.java
    │   ├── entity/
    │   │   └── AuditLog.java             # Таблица журнала аудита
    │   ├── repository/
    │   │   └── AuditLogRepository.java
    │   └── service/
    │       ├── ValidationService.java    # «ИИ»-проверка прав по матрице ролей
    │       └── RoutingService.java       # Атомарная маршрутизация + аудит
    └── resources/
        ├── application.properties
        └── static/
            └── index.html               # SPA: форма + схема маршрутизации + лог
```

---

## Матрица доступа (MVP)

| Роль      | Разрешённые префиксы объектов              | Сектор              |
|-----------|--------------------------------------------|---------------------|
| DOCTOR    | `MED_` `PATIENT_` `CLINIC_` `RX_`         | MEDICAL             |
| ENGINEER  | `INFRA_` `DOOR_` `EQUIPMENT_` `FACILITY_` | INFRASTRUCTURE      |
| CITIZEN   | `PRODUCT_` `QR_` `PUBLIC_` `STORE_` `TENGE_` | RETAIL / FINANCE |

---

## Запуск локально

**Требования:** JDK 17+, Maven 3.8+

```bash
cd ideaqr-gateway
mvn spring-boot:run
```

Открыть: **http://localhost:8080**

**H2 Console** (браузер с доступом к БД журнала):  
→ http://localhost:8080/h2-console  
`JDBC URL:` `jdbc:h2:mem:ideaqrdb`  
`User:` `sa` · `Password:` _(пусто)_

---

## Сборка JAR и запуск вручную

```bash
mvn clean package -DskipTests
java -jar target/ideaqr-gateway-0.1.0-SNAPSHOT.jar
```

---

## Деплой на Render.com (бесплатно)

1. **Push** проект на GitHub (public или private репозиторий).

2. Перейдите на [render.com](https://render.com) → **New → Web Service**.

3. Подключите репозиторий.

4. Настройки:
   | Поле | Значение |
   |---|---|
   | **Environment** | `Docker` |
   | **Dockerfile Path** | `./Dockerfile` |
   | **Plan** | `Free` |

5. Нажмите **Deploy** — Render сам соберёт образ и запустит.

6. После деплоя ваш URL: `https://ideaqr-gateway.onrender.com`

> ⚠️ **Важно:** Render free tier засыпает после 15 мин. без трафика.  
> Первый запрос после сна занимает ~30 сек.  
> H2 хранит данные **в памяти** — при рестарте журнал сбрасывается.  
> Для продакшена замените H2 на PostgreSQL (Render предоставляет бесплатную БД).

---

## API Reference

```
POST /api/gateway/scan
Content-Type: application/json

{
  "userId":     "USR-DOC-7291",
  "role":       "DOCTOR",
  "objectId":   "PATIENT_7291",
  "sectorType": "MEDICAL"
}
```

```
GET  /api/gateway/audit    → List<AuditLog> (все записи, desc по времени)
GET  /api/gateway/health   → { "status": "UP" }
GET  /h2-console           → H2 Web Console
```

---

## Переход к продакшену (чеклист)

- [ ] Заменить H2 → PostgreSQL (Spring Data JPA, без изменения кода логики)
- [ ] Подключить реальный Identity Provider (e-GOV, Keycloak) вместо mock-валидации
- [ ] Заменить `generateMockData()` на реальные HTTP-вызовы к API реестров
- [ ] Добавить JWT-авторизацию в `SecurityConfig`
- [ ] Настроить HTTPS / reverse proxy (Render делает это автоматически)
- [ ] Перевести `AuditLog` в append-only (Hibernate Envers или PartitionedTable)
