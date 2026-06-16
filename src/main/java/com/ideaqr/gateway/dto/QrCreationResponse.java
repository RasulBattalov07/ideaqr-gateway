package com.ideaqr.gateway.dto;

import lombok.Data;

@Data
public class QrCreationResponse {
    private String qrImageDataUri;
    private String objectUid;
}
