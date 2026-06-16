package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.RoleType;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class IdentityService {

    public static final int TRUST_CITIZEN = 1;
    public static final int TRUST_VERIFIED = 2;
    public static final int TRUST_SPECIALIST = 3;

    public Identity createPrimaryIdentity(Set<RoleType> roles, int trustLevel) {
        // Возвращаем null для успешной компиляции (логика базы данных будет добавлена позже)
        return null; 
    }

    public void save(Identity identity) {
    }

    public Identity findById(Object uid) {
        return null;
    }
}
