package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.PlatformModule;
import com.ideaqr.gateway.domain.enums.ModuleStatus;
import com.ideaqr.gateway.repository.PlatformModuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Manages platform modules — the spheres of interaction shown in the admin panel.
 * Modules are metadata; they never bypass the platform chain. Base MVP modules are
 * seeded idempotently on startup.
 */
@Service
@RequiredArgsConstructor
public class ModuleService {

    private final PlatformModuleRepository moduleRepository;

    /** Idempotently create a module by code (used by the seeder). */
    @Transactional
    public PlatformModule ensureModule(String code, String name, String description) {
        return moduleRepository.findByCode(code)
                .orElseGet(() -> moduleRepository.save(PlatformModule.builder()
                        .code(code)
                        .name(name)
                        .description(description)
                        .status(ModuleStatus.ACTIVE)
                        .build()));
    }

    public List<PlatformModule> list() {
        return moduleRepository.findAllByOrderByCreatedAtAsc();
    }

    /** Toggle a module between ACTIVE and DISABLED (admin action). */
    @Transactional
    public PlatformModule toggle(UUID moduleUid) {
        PlatformModule module = moduleRepository.findById(moduleUid)
                .orElseThrow(() -> new IllegalArgumentException("Модуль не найден."));
        module.setStatus(module.getStatus() == ModuleStatus.ACTIVE ? ModuleStatus.DISABLED : ModuleStatus.ACTIVE);
        return moduleRepository.save(module);
    }
}
