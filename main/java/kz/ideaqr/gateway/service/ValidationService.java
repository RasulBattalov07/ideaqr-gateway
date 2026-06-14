package kz.ideaqr.gateway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Имитация ИИ-валидации контекста сканирования.
 *
 * В продакшен-версии здесь:
 *   — запрос к внешнему Identity Provider (e-GOV, EGKK)
 *   — проверка согласия субъекта
 *   — верификация цифровой подписи QR-кода
 *   — ML-модель определения контекста
 *
 * Для MVP: детерминированные правила на основе префиксов объектов и ролей.
 */
@Service
@Slf4j
public class ValidationService {

    /**
     * Матрица доступа: роль → разрешённые префиксы объектов
     * Логика: идентификатор — «дверь», верифицированная роль — «ключ».
     *
     * Доступ РАЗРЕШАЕТСЯ если:
     *   а) objectId начинается с разрешённого для роли префикса, ИЛИ
     *   б) sectorType соответствует домену роли
     */
    public boolean hasAccess(String role, String objectId, String sectorType) {
        if (role == null || role.isBlank() || objectId == null || objectId.isBlank()) {
            log.warn("Validation failed: null or blank role/objectId");
            return false;
        }

        final String r = role.trim().toUpperCase();
        final String o = objectId.trim().toUpperCase();
        final String s = (sectorType != null && !sectorType.isBlank())
                ? sectorType.trim().toUpperCase()
                : "";

        boolean result = switch (r) {
            case "DOCTOR" ->
                o.startsWith("MED_")     ||
                o.startsWith("PATIENT_") ||
                o.startsWith("CLINIC_")  ||
                o.startsWith("RX_")      ||
                "MEDICAL".equals(s);

            case "ENGINEER" ->
                o.startsWith("INFRA_")     ||
                o.startsWith("DOOR_")      ||
                o.startsWith("EQUIPMENT_") ||
                o.startsWith("FACILITY_")  ||
                o.startsWith("SENSOR_")    ||
                "INFRASTRUCTURE".equals(s);

            case "CITIZEN" ->
                o.startsWith("PRODUCT_") ||
                o.startsWith("QR_")      ||
                o.startsWith("PUBLIC_")  ||
                o.startsWith("STORE_")   ||
                o.startsWith("ITEM_")    ||
                o.startsWith("TENGE_")   ||
                "RETAIL".equals(s)       ||
                "FINANCE".equals(s);

            default -> false;
        };

        log.debug("AI Validation: role={} object={} sector={} → {}", r, o, s, result ? "GRANTED" : "DENIED");
        return result;
    }

    /**
     * Формирует детальную причину отказа для журнала аудита.
     */
    public String buildDenyReason(String role, String objectId) {
        if (role == null || role.isBlank()) {
            return "Роль субъекта не определена или не передана.";
        }
        final String o = (objectId != null) ? objectId.trim() : "N/A";

        return switch (role.trim().toUpperCase()) {
            case "DOCTOR" ->
                String.format("ИИ-валидация: роль ВРАЧ не имеет допуска к объекту «%s». " +
                    "Разрешены только объекты медицинского профиля: MED_, PATIENT_, CLINIC_, RX_.", o);
            case "ENGINEER" ->
                String.format("ИИ-валидация: роль ИНЖЕНЕР не имеет допуска к объекту «%s». " +
                    "Разрешены только инфраструктурные объекты: INFRA_, DOOR_, EQUIPMENT_, FACILITY_.", o);
            case "CITIZEN" ->
                String.format("ИИ-валидация: роль ГРАЖДАНИН не имеет допуска к объекту «%s». " +
                    "Разрешены только публичные и платёжные объекты: PRODUCT_, QR_, PUBLIC_, STORE_, TENGE_.", o);
            default ->
                String.format("Неизвестная роль «%s». Доступ запрещён по умолчанию согласно политике нулевого доверия.", role);
        };
    }
}
