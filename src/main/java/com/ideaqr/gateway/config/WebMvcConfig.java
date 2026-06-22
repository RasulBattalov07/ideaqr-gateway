package com.ideaqr.gateway.config;

import com.ideaqr.gateway.web.PasswordChangeEnforcementInterceptor;
import com.ideaqr.gateway.web.TenantInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC customizations. Registers the per-request tenant scoping (audit 5.3) and the
 * forced-password-change enforcement (audit 4.9) across the API surface. The tenant
 * interceptor runs first so the isolation filter is active for everything downstream.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;
    private final PasswordChangeEnforcementInterceptor passwordChangeEnforcementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor).addPathPatterns("/api/**").order(0);
        registry.addInterceptor(passwordChangeEnforcementInterceptor).addPathPatterns("/api/**").order(1);
    }
}
