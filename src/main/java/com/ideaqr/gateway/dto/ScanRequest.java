package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Payload for a terminal scan. {@code objectUid} is the identifier encoded in the
 * QR (or typed manually).
 *
 * <p><b>Security (audit 4.3):</b> the request intentionally carries no client-supplied
 * time. The working-hours policy is evaluated against the <i>server</i> clock only, so
 * a caller cannot bypass the time gate by sending an arbitrary hour.</p>
 */
@Data
public class ScanRequest {

    @NotBlank(message = "Не указан идентификатор объекта")
    private String objectUid;
}
