package com.ideaqr.gateway.dto;

import com.ideaqr.gateway.enums.Role;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
import java.util.UUID;

/**
 * Payload to migrate a GUEST identity into a permanent PRIMARY identity.
 * The guest's history is re-pointed onto the new primary identity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationRequest {

    @NotNull(message = "guestIdentityUid must not be null")
    private UUID guestIdentityUid;

    /** Roles to grant to the newly created PRIMARY identity. May be empty. */
    private Set<Role> roles;
}
