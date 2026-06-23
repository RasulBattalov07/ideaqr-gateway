package com.ideaqr.gateway.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Public projection of an object card for unregistered (GUEST) visitors.
 *
 * <p>The FINAL ТЗ makes a public page mandatory for every identifier
 * ("ПУБЛИЧНАЯ СТРАНИЦА") and limits a guest to the public subset only
 * ("ГОСТЕВОЙ ДОСТУП" / Scenario&nbsp;#1): a guest may see the name, an image, a
 * short description and the overall rating — but never the price, discounts,
 * reviews, movement history, supplier, origin or extended specifications.</p>
 *
 * <p>This is implemented as a <b>default-deny whitelist</b>: only fields known to
 * be public survive the projection, so a new sensitive field added to any module's
 * card is hidden from guests by default rather than leaking until someone remembers
 * to blacklist it. The access <i>decision</i> is unchanged — this only governs how
 * much of an already-approved card a guest receives.</p>
 */
public final class PublicCard {

    /** Lower-cased names of the only fields a guest is allowed to see on any card. */
    private static final Set<String> PUBLIC_FIELDS = Set.of(
            // identity of the object
            "title", "displayname", "name", "productname", "brand",
            // a single image
            "photo", "image", "imageurl", "images", "cover",
            // a short description
            "description", "shortdescription", "summary",
            // the overall rating (Scenario #1 — "Общий рейтинг")
            "rating"
    );

    private PublicCard() {
    }

    /**
     * Returns a new map containing only the whitelisted public fields of {@code full}.
     * Field names are matched case-insensitively; the original key casing is preserved
     * in the result. A {@code null} input yields an empty card.
     */
    public static Map<String, Object> project(Map<String, Object> full) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (full == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : full.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (PUBLIC_FIELDS.contains(key)) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
