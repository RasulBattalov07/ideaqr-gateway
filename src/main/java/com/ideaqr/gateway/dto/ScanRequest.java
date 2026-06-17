package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload sent by the citizen terminal when an object is scanned.
 */
@Data
public class ScanRequest {

    /** The scanned object identifier (e.g. PATIENT_7291, RETAIL_ADIDAS_SHIRT). */
    @NotBlank(message = "Не указан идентификатор объекта")
    private String objectUid;

    /**
     * Optional context override used purely for live demonstrations of the
     * context/time policy engine. When provided (0–23), the decision engine
     * evaluates the working-hours gate against this hour instead of the real
     * server clock. When null, the real time is used.
     */
    private Integer contextHour;
}
