package com.ideaqr.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Result of a guest-to-primary migration.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationResponse {

    private UUID guestIdentityUid;
    private UUID primaryIdentityUid;
    private long rebindedRequests;
    private long rebindedInteractions;
    private long appendedHistoryEvents;
    private String message;
}
