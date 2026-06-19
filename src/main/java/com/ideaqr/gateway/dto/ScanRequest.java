package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload for a terminal scan. {@code objectUid} is the identifier encoded in the
 * QR (or typed manually); {@code contextHour} optionally simulates the hour of day
 * so the time-of-day policy window can be demonstrated at any real wall-clock time.
 */
@Data
public class ScanRequest {

    @NotBlank(message = "Не указан идентификатор объекта")
    private String objectUid;

    /** Simulated hour of day (0–23) for the working-hours policy demo; null = real time. */
    private Integer contextHour;
}
