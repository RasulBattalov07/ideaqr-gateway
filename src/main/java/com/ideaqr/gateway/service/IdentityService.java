package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.RoleType;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class IdentityService {

    public static final int TRUST_CITIZEN = 1;
    public static final int TRUST_VERIFIED = 2;
    public static final int TRUST_SPECIALIST = 3;

    public Identity createPrimaryIdentity(Set<RoleType> roles, int trustLevel) {
        Identity identity = new Identity();
        // Генерируем реальный ID, чтобы избежать NullPointerException
        identity.setIdentityUid(UUID.randomUUID());
        identity.setRoles(roles != null ? roles : new HashSet<>());
        identity.setTrustLevel(trustLevel);
        return identity;
    }

    public void save(Identity identity) {
        // Оставляем пустым для MVP. Если у вас есть репозиторий, тут будет identityRepository.save(identity);
    }

    public Identity findById(Object uid) {
        Identity identity = new Identity();
        if (uid instanceof UUID) {
            identity.setIdentityUid((UUID) uid);
        }
        // Обязательно инициализируем роли, чтобы при входе (логине) не падал код .stream()
        identity.setRoles(new HashSet<>());
        identity.setTrustLevel(TRUST_CITIZEN);
        identity.setPrimaryQrUid(UUID.randomUUID());
        return identity;
    }
}
