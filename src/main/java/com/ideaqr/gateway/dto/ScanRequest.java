package com.ideaqr.gateway.dto;

import lombok.Data;

@Data
public class ScanRequest {
    private String objectUid;
    private Integer contextHour;
}
