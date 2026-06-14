package kz.ideaqr.gateway.dto;

import lombok.Data;

/**
 * DTO входящего запроса на сканирование.
 * Имитирует данные, которые платформа определяет автоматически:
 * кто сканирует (userId + role) и что сканирует (objectId + sectorType).
 */
@Data
public class ScanRequest {

    /** Идентификатор субъекта (e-GOV ключ, токен, ИИН) */
    private String userId;

    /** Роль: DOCTOR | ENGINEER | CITIZEN */
    private String role;

    /** Идентификатор объекта (QR-код, ID медкарты, код двери) */
    private String objectId;

    /** Сектор: MEDICAL | INFRASTRUCTURE | RETAIL | FINANCE */
    private String sectorType;
}
