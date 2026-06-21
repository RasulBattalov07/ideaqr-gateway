package com.ideaqr.gateway.config;

import com.ideaqr.gateway.web.PasswordChangeEnforcementInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC customizations. Registers the forced-password-change enforcement (audit 4.9)
 * across the API surface.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final PasswordChangeEnforcementInterceptor passwordChangeEnforcementInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(passwordChangeEnforcementInterceptor).addPathPatterns("/api/**");
    }
}
