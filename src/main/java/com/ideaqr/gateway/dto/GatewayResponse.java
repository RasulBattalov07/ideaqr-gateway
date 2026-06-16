package com.ideaqr.gateway.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class GatewayResponse {
    private String outcome;
    private String reason;
    private String riskLevel;
    private String category;
    private String objectUid;
    private Object data;

    // Поля для анимации цепочки управления
    private UUID identityUid;
    private UUID requestUid;
    private UUID decisionUid;
    private UUID interactionUid;
    private UUID historyUid;
}
