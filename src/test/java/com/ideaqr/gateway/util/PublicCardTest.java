package com.ideaqr.gateway.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the guest half of Scenario #1 (ГОСТЕВОЙ ДОСТУП): a guest sees only the
 * public subset of an object card — name, image, short description, rating — and
 * never the commercial / sensitive fields (price, reviews, supplier, history). The
 * registration-merge half is covered by {@code GuestServiceMergeTest}.
 */
class PublicCardTest {

    @Test
    void guestSeesOnlyThePublicFieldsOfAProductCard() {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("productName", "Adidas — чёрная футболка");
        full.put("brand", "Adidas");
        full.put("description", "Хлопковая футболка Adidas Originals.");
        full.put("rating", 4.6);
        // sensitive — must be stripped for a guest
        full.put("price", 25000);
        full.put("currency", "₸");
        full.put("reviews", 1280);
        full.put("loyalty", Map.of("code", "IDEAQR-ADIDAS-10"));
        full.put("alternatives", "...");
        full.put("supplier", "Adidas KZ");

        Map<String, Object> pub = PublicCard.project(full);

        assertThat(pub).containsOnlyKeys("productName", "brand", "description", "rating");
        assertThat(pub).doesNotContainKeys("price", "currency", "reviews", "loyalty", "alternatives", "supplier");
    }

    @Test
    void fieldMatchingIsCaseInsensitiveButPreservesOriginalKeys() {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("ProductName", "X");
        full.put("PRICE", 10);

        Map<String, Object> pub = PublicCard.project(full);

        assertThat(pub).containsKey("ProductName"); // original casing preserved
        assertThat(pub).doesNotContainKey("PRICE");
    }

    @Test
    void nullCardProjectsToAnEmptyCard() {
        assertThat(PublicCard.project(null)).isEmpty();
    }
}
