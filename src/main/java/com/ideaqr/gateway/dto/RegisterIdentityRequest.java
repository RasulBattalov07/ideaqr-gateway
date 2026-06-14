package com.ideaqr.gateway.dto;

import com.ideaqr.gateway.enums.Role;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Payload to register a new PRIMARY identity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterIdentityRequest {

    @NotEmpty(message = "at least one role is required")
    private Set<Role> roles;
}
