package kz.ideaqr.gateway.service;

import kz.ideaqr.gateway.dto.GatewayResponse;
import kz.ideaqr.gateway.dto.ScanRequest;
import kz.ideaqr.gateway.entity.AuditLog;
import kz.ideaqr.gateway.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сервис маршрутизации — ядро шлюза.
 *
 * Алгоритм:
 *  1. ИИ-валидация (ValidationService): кто + к чему + контекст → разрешено / нет
 *  2. Атомарная запись в журнал аудита НЕЗАВИСИМО от результата
 *  3. При GRANTED: имитация запроса к внешней системе, возврат моковых данных
 *  4. При DENIED:  возврат причины отказа, запись зафиксирована
 *
 * Платформа не является хранилищем данных.
 * Она управляет «дверьми», а не «содержимым комнат».
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoutingService {

    private final AuditLogRepository auditLogRepository;
    private final ValidationService validationService;

    /**
     * Основной метод обработки запроса сканирования.
     * @Transactional гарантирует атомарность: запись в аудит либо завершена целиком, либо откатывается.
     */
    @Transactional
    public GatewayResponse processRequest(ScanRequest req) {
        log.info("→ Gateway scan: userId={} role={} objectId={} sector={}",
                req.getUserId(), req.getRole(), req.getObjectId(), req.getSectorType());

        final boolean granted = validationService.hasAccess(
                req.getRole(), req.getObjectId(), req.getSectorType());

        final String status  = granted ? "GRANTED" : "DENIED";
        final String details = granted
                ? "Запрос маршрутизирован к внешнему источнику. Данные успешно получены."
                : validationService.buildDenyReason(req.getRole(), req.getObjectId());

        // ── Атомарная запись в журнал (независимо от результата) ──────────────
        final LocalDateTime now = LocalDateTime.now();
        AuditLog log = AuditLog.builder()
                .subjectId(req.getUserId() != null ? req.getUserId() : "ANONYMOUS")
                .objectId(req.getObjectId())
                .role(req.getRole() != null ? req.getRole().toUpperCase() : "UNKNOWN")
                .accessStatus(status)
                .sectorType(req.getSectorType() != null ? req.getSectorType().toUpperCase() : "GENERAL")
                .details(details)
                .timestamp(now)
                .build();

        AuditLog saved = auditLogRepository.save(log);
        this.log.info("← Audit #{} written: status={}", saved.getId(), status);

        // ── Формирование ответа ────────────────────────────────────────────────
        if (granted) {
            return GatewayResponse.builder()
                    .status("GRANTED")
                    .message("Доступ разрешён. Запрос маршрутизирован к внешнему источнику.")
                    .role(req.getRole())
                    .subjectId(req.getUserId())
                    .objectId(req.getObjectId())
                    .sectorType(req.getSectorType())
                    .mockData(buildMockData(req))
                    .auditId(saved.getId())
                    .timestamp(saved.getTimestamp())
                    .build();
        } else {
            return GatewayResponse.builder()
                    .status("DENIED")
                    .message("Доступ запрещён. " + details)
                    .role(req.getRole())
                    .subjectId(req.getUserId())
                    .objectId(req.getObjectId())
                    .sectorType(req.getSectorType())
                    .auditId(saved.getId())
                    .timestamp(saved.getTimestamp())
                    .build();
        }
    }

    public List<AuditLog> getFullAuditLog() {
        return auditLogRepository.findAllByOrderByTimestampDesc();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Генераторы моковых данных — имитация ответа от внешних систем
    // В продакшен-версии здесь — HTTP-вызовы к реальным API реестров
    // ════════════════════════════════════════════════════════════════════════════

    private Map<String, Object> buildMockData(ScanRequest req) {
        if (req.getRole() == null) return Map.of("info", "Данные недоступны.");
        return switch (req.getRole().toUpperCase()) {
            case "DOCTOR"   -> mockMedical(req.getObjectId());
            case "ENGINEER" -> mockInfra(req.getObjectId());
            case "CITIZEN"  -> mockRetail(req.getObjectId());
            default         -> Map.of("info", "Данные недоступны.");
        };
    }

    private Map<String, Object> mockMedical(String objectId) {
        int hash = Math.abs((objectId != null ? objectId : "").hashCode() % 9000) + 1000;

        Map<String, Object> profile = new HashMap<>();
        profile.put("anonymizedId",  "PAT-" + hash);
        profile.put("ageGroup",      "35–45");
        profile.put("bloodType",     "A(II) Rh+");
        profile.put("allergies",     List.of("Пенициллин", "Аспирин"));
        profile.put("lastVisit",     "2025-11-22");
        profile.put("consentStatus", "✓ Согласие пациента подтверждено");
        profile.put("aiNote",        "Рекомендована консультация кардиолога на основе анамнеза.");

        Map<String, Object> data = new HashMap<>();
        data.put("source",       "МедРеестр КЗ — Внешняя система [MOCK]");
        data.put("accessLevel",  "МЕДИЦИНСКИЙ · ПОЛНЫЙ ДОПУСК");
        data.put("patientProfile", profile);
        return data;
    }

    private Map<String, Object> mockInfra(String objectId) {
        int hash = Math.abs((objectId != null ? objectId : "").hashCode() % 900) + 100;

        Map<String, Object> facility = new HashMap<>();
        facility.put("objectCode",          "INFRA-" + hash);
        facility.put("zone",                "Зона А — Технический корпус");
        facility.put("operationalStatus",   "АКТИВЕН");
        facility.put("lastInspection",      "2025-12-01");
        facility.put("clearanceLevel",      "Уровень 3 — Допуск разрешён");
        facility.put("nextMaintenance",     "2026-03-01");
        facility.put("aiNote",              "Объект в норме. Нарушений и аномалий не зафиксировано.");

        Map<String, Object> data = new HashMap<>();
        data.put("source",       "ИнфраСистема КЗ — Внешняя система [MOCK]");
        data.put("accessLevel",  "ИНФРАСТРУКТУРА · ОПЕРАЦИОННЫЙ ДОСТУП");
        data.put("facilityInfo", facility);
        return data;
    }

    private Map<String, Object> mockRetail(String objectId) {
        List<String> recs = new ArrayList<>();
        recs.add("Хранить при +2°C..+6°C, не допускать заморозки");
        recs.add("Похожие товары: Молоко «Шын» 3.2%, Молоко «Family» 1%");
        recs.add("Скидка 15% при покупке от 3 шт. до 20 января");

        Map<String, Object> product = new HashMap<>();
        product.put("name",                "Молоко пастеризованное «Агромол» 2.5%");
        product.put("manufacturer",        "АО Агромол, г. Алматы, Казахстан");
        product.put("expiryDate",          "2026-01-25");
        product.put("certificationStatus", "✓ Сертифицирован СТ РК 2913-2019");
        product.put("barcode",             objectId);
        product.put("retailPrice",         "320 ₸");
        product.put("aiRecommendations",   recs);

        Map<String, Object> data = new HashMap<>();
        data.put("source",       "ТорговыйРеестр КЗ — Внешняя система [MOCK]");
        data.put("accessLevel",  "ПУБЛИЧНЫЙ · ОТКРЫТЫЕ ДАННЫЕ");
        data.put("productInfo",  product);
        return data;
    }
}
