package com.ideaqr.gateway.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void sha256MatchesKnownVector() {
        // SHA-256("abc") — canonical FIPS-180 test vector.
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void randomTokenIsUniqueAndHighEntropy() {
        String a = Hashing.randomToken();
        String b = Hashing.randomToken();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.length()).isGreaterThanOrEqualTo(40); // 256 bits, base64url
    }

    @Test
    void constantTimeEqualsBehavesLikeEquals() {
        assertThat(Hashing.constantTimeEquals("token", "token")).isTrue();
        assertThat(Hashing.constantTimeEquals("token", "other")).isFalse();
        assertThat(Hashing.constantTimeEquals(null, "x")).isFalse();
        assertThat(Hashing.constantTimeEquals("x", null)).isFalse();
    }
}
