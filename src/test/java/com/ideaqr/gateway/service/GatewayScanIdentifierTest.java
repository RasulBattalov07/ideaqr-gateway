package com.ideaqr.gateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the registry "не найдено" fix: a scanned platform QR encodes an absolute deep link
 * ({@code <origin>/s/<identifier>}); the gateway must peel it back to the bare identifier before
 * resolution, so a freshly created object or a personal {@code IDENTITY:} QR is found immediately
 * regardless of which reader produced the value. Bare identifiers must pass through untouched.
 */
class GatewayScanIdentifierTest {

    @Test
    void stripsNativeScanUrlWrapperToBareIdentifier() {
        assertThat(GatewayService.normalizeIdentifier("http://localhost:8080/s/RETAIL_3F9A1C2D"))
                .isEqualTo("RETAIL_3F9A1C2D");
        assertThat(GatewayService.normalizeIdentifier(
                "https://ideaqr.example.com/s/IDENTITY:aaaaaaaa-0000-0000-0000-000000000007"))
                .isEqualTo("IDENTITY:aaaaaaaa-0000-0000-0000-000000000007");
    }

    @Test
    void leavesBareIdentifiersUnchanged() {
        assertThat(GatewayService.normalizeIdentifier("RETAIL_NIKE_AF1")).isEqualTo("RETAIL_NIKE_AF1");
        assertThat(GatewayService.normalizeIdentifier("  CAR_TOYOTA_CAMRY  ")).isEqualTo("CAR_TOYOTA_CAMRY");
        assertThat(GatewayService.normalizeIdentifier("IDENTITY:aaaa")).isEqualTo("IDENTITY:aaaa");
    }

    @Test
    void dropsQueryAndFragmentAndDecodesEncodedColon() {
        assertThat(GatewayService.normalizeIdentifier("https://host/s/RETAIL_X?utm=1#frag")).isEqualTo("RETAIL_X");
        assertThat(GatewayService.normalizeIdentifier("https://host/s/IDENTITY%3Aabc")).isEqualTo("IDENTITY:abc");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(GatewayService.normalizeIdentifier(null)).isEmpty();
        assertThat(GatewayService.normalizeIdentifier("   ")).isEmpty();
    }
}
