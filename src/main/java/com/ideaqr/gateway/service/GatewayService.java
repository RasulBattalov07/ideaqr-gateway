package com.ideaqr.gateway.service;

import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.domain.Identity;
import org.springframework.stereotype.Service;

@Service
public class GatewayService {

    public GatewayResponse scan(Identity identity, ScanRequest request) {
        GatewayResponse response = new GatewayResponse();
        response.setStatus("SUCCESS");
        response.setMessage("QR Code scanned successfully");
        return response;
    }

    public GatewayResponse report(Identity identity, ReportRequest request) {
        GatewayResponse response = new GatewayResponse();
        response.setStatus("SUCCESS");
        response.setMessage("Report generated successfully");
        return response;
    }
}
