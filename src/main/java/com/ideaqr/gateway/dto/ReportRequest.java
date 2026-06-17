package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Payload for reporting an issue against an infrastructure / eco object. */
@Data
public class ReportRequest {

    @NotBlank(message = "Не указан идентификатор объекта")
    private String objectUid;

    /** Free-text description of the problem (Russian, from the citizen). */
    private String message;
}
