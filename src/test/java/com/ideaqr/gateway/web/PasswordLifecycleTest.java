package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password-lifecycle behaviour (audit 1.7 / 4.9): the change-password endpoint needs
 * auth + CSRF, and a forced-change flag blocks mutating calls server-side until the
 * password is actually changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordLifecycleTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void changePasswordRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/change-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"WhateverPass12\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "citizen", roles = "USER")
    void changePasswordRequiresCsrf() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"WhateverPass12\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "doctor", roles = "USER")
    void forcedChangeBlocksMutationsUntilPasswordIsChanged() throws Exception {
        User doctor = userRepository.findByUsername("doctor").orElseThrow();
        doctor.setMustChangePassword(true);
        userRepository.save(doctor);

        // A state-changing call is blocked server-side while the flag is set.
        mvc.perform(post("/api/v2/sos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // The change-password call itself stays open; a valid change clears the flag.
        mvc.perform(post("/api/auth/change-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Doctor123!\",\"newPassword\":\"FreshDoctorPass1\"}"))
                .andExpect(status().isOk());

        // With the flag cleared, mutations are accepted again.
        mvc.perform(post("/api/v2/sos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }
}
