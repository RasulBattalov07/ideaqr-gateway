package com.ideaqr.gateway.dto;

import com.ideaqr.gateway.enums.IdentityStatus;
import com.ideaqr.gateway.enums.IdentityType;
import com.ideaqr.gateway.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Read model for an Identity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdentityResponse {

    private UUID identityUid;
    private IdentityType identityType;
    private IdentityStatus status;
    private Set<Role> roles;
    private LocalDateTime createdAt;
}
