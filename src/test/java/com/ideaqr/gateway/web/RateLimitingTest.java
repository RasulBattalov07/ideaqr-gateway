package com.ideaqr.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies the Bucket4j login throttle (audit 4.9): a burst of password attempts
 * from a single client is allowed up to the configured per-minute budget
 * ({@code app.rate-limit.login-capacity}, default 10) and is then answered with
 * HTTP 429 Too Many Requests, with a {@code Retry-After} hint.
 *
 * <p>The burst carries a documentation-range {@code X-Forwarded-For} so it occupies
 * its own per-IP bucket and never interferes with the other tests that share this
 * application context (and the loopback address).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitingTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void loginReturns429AfterTheBudgetIsExhausted() throws Exception {
        String spammerIp = "198.51.100.23"; // TEST-NET-2: never a real client, never collides
        List<Integer> statuses = new ArrayList<>();
        MvcResult last = null;

        for (int i = 0; i < 20; i++) {
            last = mvc.perform(post("/login").with(csrf())
                            .header("X-Forwarded-For", spammerIp)
                            .param("username", "nobody")
                            .param("password", "wrong-" + i))
                    .andReturn();
            statuses.add(last.getResponse().getStatus());
        }

        assertThat(statuses.get(0))
                .as("the first attempt is within budget — it reaches authentication and fails (401), it is not throttled")
                .isEqualTo(401);
        assertThat(statuses)
                .as("once the per-minute login budget is spent, the burst is throttled")
                .contains(429);
        assertThat(statuses.get(19))
                .as("still throttled at the end of a 20-request burst")
                .isEqualTo(429);
        assertThat(last.getResponse().getHeader("Retry-After"))
                .as("a 429 tells the client when it may retry")
                .isNotNull();
    }
}
