package com.ideaqr.gateway.service;

import com.ideaqr.gateway.enums.RequestType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock client simulating integration with external Kazakhstan state registries.
 * In production this would proxy to real government APIs; here it returns rich,
 * demo-friendly mock payloads keyed by the object's sub-network.
 *
 * The "record" field carries the realistic, presentation-ready data that the
 * frontend renders as a styled card (medical chart, facility panel, product info).
 */
@Service
@Slf4j
public class RegistryClient {

    /**
     * Fetch mock registry data for an approved access.
     *
     * @param objectUid   the target object
     * @param requestType the approved request type
     * @return a structured mock payload
     */
    public Map<String, Object> fetchMockData(String objectUid, RequestType requestType) {
        String registry = resolveRegistryName(objectUid);
        String category = resolveCategory(objectUid, requestType);
        log.info("Routing approved access to mock registry '{}' (category {}) for object {}",
                registry, category, objectUid);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registry", registry);
        payload.put("category", category);          // MEDICAL | INFRASTRUCTURE | FINANCE | CIVIL | GENERIC
        payload.put("objectUid", objectUid);
        payload.put("requestType", requestType.name());
        payload.put("source", "MOCK_KZ_GOV_API");
        payload.put("fetchedAt", LocalDateTime.now().toString());
        payload.put("record", buildRecord(category, objectUid));
        return payload;
    }

    /**
     * Build the rich, demo-ready record for a given category.
     */
    private Map<String, Object> buildRecord(String category, String objectUid) {
        Map<String, Object> record = new LinkedHashMap<>();
        switch (category) {
            case "MEDICAL":
                record.put("patientName", "Анонимный профиль PAT-7291");
                record.put("ageGroup", "35–45 лет");
                record.put("bloodType", "A(II) Rh+ (Вторая положительная)");
                record.put("allergies", List.of("Пенициллин (высокий риск)", "Аспирин", "Пыльца"));
                record.put("chronicConditions", List.of("Легкая астма"));
                record.put("consentStatus", "✅ Информированное согласие подтверждено через eGov");
                record.put("lastVisit", "22.05.2026");
                record.put("aiRecommendation",
                        "Обратите внимание на риск аллергической реакции. Рекомендуется консультация кардиолога.");
                break;
            case "INFRASTRUCTURE":
                record.put("objectCode", "Склад №7 (Зона А)");
                record.put("clearanceLevel", "Уровень 3 — Ограниченный доступ");
                record.put("operationalStatus", "АКТИВЕН");
                record.put("lastInspection", "01.06.2026");
                record.put("maintenanceWindow", "08:00 - 18:00");
                record.put("securityAlerts", "Нарушений не зафиксировано");
                break;
            case "FINANCE":
                record.put("productName", "Молоко пастеризованное «Агромол» 2.5%");
                record.put("manufacturer", "АО Агромол, г. Алматы");
                record.put("expiryDate", "25.06.2026");
                record.put("certification", "✓ СТ РК 2913-2019");
                record.put("price", "320 ₸");
                break;
            case "CIVIL":
                record.put("service", "Портал государственных услуг");
                record.put("objectCode", objectUid);
                record.put("citizenPortal", "✅ Доступ к публичным сервисам открыт");
                record.put("status", "АКТИВЕН");
                break;
            default:
                record.put("objectCode", objectUid);
                record.put("classification", "Общий объект");
                record.put("status", "АКТИВЕН");
                break;
        }
        return record;
    }

    /**
     * Map an object/request to a high-level data category for the frontend card.
     */
    private String resolveCategory(String objectUid, RequestType requestType) {
        if (objectUid != null) {
            if (isMedical(objectUid)) return "MEDICAL";
            if (objectUid.startsWith("INFRA_")) return "INFRASTRUCTURE";
            if (objectUid.startsWith("FIN_")) return "FINANCE";
            if (objectUid.startsWith("CIV_")) return "CIVIL";
        }
        // Fall back to the request type when the prefix is ambiguous.
        if (requestType == RequestType.MEDICAL_ACCESS) return "MEDICAL";
        if (requestType == RequestType.INFRASTRUCTURE_ACCESS) return "INFRASTRUCTURE";
        if (requestType == RequestType.FINANCE_ACCESS) return "FINANCE";
        return "GENERIC";
    }

    /** Medical objects may use several historical prefixes. */
    private boolean isMedical(String objectUid) {
        return objectUid.startsWith("MED_")
                || objectUid.startsWith("PATIENT_")
                || objectUid.startsWith("CLINIC_")
                || objectUid.startsWith("RX_");
    }

    private String resolveRegistryName(String objectUid) {
        if (objectUid == null) {
            return "GENERIC_REGISTER";
        }
        if (isMedical(objectUid)) {
            return "MED_REGISTER";
        }
        if (objectUid.startsWith("INFRA_")) {
            return "INFRA_REGISTER";
        }
        if (objectUid.startsWith("FIN_")) {
            return "FIN_REGISTER";
        }
        if (objectUid.startsWith("CIV_")) {
            return "CIV_REGISTER";
        }
        if (objectUid.startsWith("ADM_")) {
            return "ADM_REGISTER";
        }
        return "GENERIC_REGISTER";
    }
}
