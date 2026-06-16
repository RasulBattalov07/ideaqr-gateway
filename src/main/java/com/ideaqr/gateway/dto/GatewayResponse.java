package com.ideaqr.gateway.dto;

import lombok.Data;

@Data
public class GatewayResponse {
    private String status;
    private String message;
    private Object dataPayload;
}
