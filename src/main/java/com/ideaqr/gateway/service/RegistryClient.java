package com.ideaqr.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Stands in for the Kazakhstan state / commercial registries. The platform
 * itself stores only metadata; the actual record content lives "behind" this
 * client. Resolution order:
 *
 * <ol>
 *   <li>objects created by an administrator (persisted {@link RegistryObject});</li>
 *   <li>the enriched, hard-coded demonstration objects below;</li>
 *   <li>category inference by identifier prefix (so unknown-but-typed codes
 *       still route through the correct policy).</li>
 * </ol>
 *
 * All card content is authored in natural Russian. The data keys are English
 * and match exactly the keys the frontend renderers expect.
 */
@Service
@RequiredArgsConstructor
public class RegistryClient {

    private final RegistryObjectRepository registryObjectRepository;
    private final ObjectMapper objectMapper;

    /** Resolved registry record passed back to the gateway. */
    public record ResolvedObject(ObjectCategory category, String displayName, Map<String, Object> data) {
    }

    public Optional<ResolvedObject> resolve(String objectUid) {
        if (objectUid == null || objectUid.isBlank()) {
            return Optional.empty();
        }
        String key = objectUid.trim();

        // 1. Administrator-created objects take precedence.
        Optional<RegistryObject> persisted = registryObjectRepository.findByObjectUid(key);
        if (persisted.isPresent()) {
            RegistryObject obj = persisted.get();
            Map<String, Object> data = parseJson(obj.getDataJson());
            return Optional.of(new ResolvedObject(obj.getCategory(), obj.getDisplayName(), data));
        }

        // 2. Built-in demonstration objects (case-insensitive match).
        String upper = key.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "PATIENT_7291":
                return Optional.of(new ResolvedObject(ObjectCategory.MEDICAL, "Алия Нурланова", patient7291()));
            case "RETAIL_ADIDAS_SHIRT":
                return Optional.of(new ResolvedObject(ObjectCategory.RETAIL,
                        "Adidas Originals Trefoil — чёрная футболка", adidasShirt()));
            case "ECO_SMART_BIN_102":
                return Optional.of(new ResolvedObject(ObjectCategory.ECO, "Умный контейнер №102", smartBin102()));
            case "INFRA_SUBSTATION_07":
                return Optional.of(new ResolvedObject(ObjectCategory.INFRASTRUCTURE,
                        "Трансформаторная подстанция №07", substation07()));
            default:
                // 3. Fall back to prefix inference.
                ObjectCategory inferred = inferCategory(upper);
                if (inferred == ObjectCategory.UNKNOWN) {
                    return Optional.empty();
                }
                return Optional.of(new ResolvedObject(inferred, key, unknownButTyped(inferred, key)));
        }
    }

    public ObjectCategory inferCategory(String objectUid) {
        if (objectUid == null) {
            return ObjectCategory.UNKNOWN;
        }
        String u = objectUid.toUpperCase(Locale.ROOT);
        if (u.startsWith("PATIENT")) {
            return ObjectCategory.MEDICAL;
        }
        if (u.startsWith("RETAIL")) {
            return ObjectCategory.RETAIL;
        }
        if (u.startsWith("ECO")) {
            return ObjectCategory.ECO;
        }
        if (u.startsWith("INFRA")) {
            return ObjectCategory.INFRASTRUCTURE;
        }
        return ObjectCategory.UNKNOWN;
    }

    // ------------------------------------------------------------------
    //  SCENARIO A — Medical record (PATIENT_7291)
    // ------------------------------------------------------------------

    private Map<String, Object> patient7291() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("patientName", "Алия Нурланова");
        d.put("patientId", "PATIENT_7291");
        d.put("age", 34);
        d.put("gender", "Женский");
        d.put("bloodType", "II (A) Rh+");
        d.put("iinMasked", "8901••••••23");
        d.put("allergies", List.of("Пенициллин", "Пыльца берёзы"));
        d.put("chronicConditions", List.of("Бронхиальная астма", "Гипотиреоз"));
        d.put("medications", List.of(
                medication("Левотироксин", "50 мкг", "1 раз в день, утром"),
                medication("Сальбутамол (ингалятор)", "100 мкг", "По потребности"),
                medication("Витамин D3", "2000 МЕ", "1 раз в день")));
        Map<String, Object> vitals = new LinkedHashMap<>();
        vitals.put("series", List.of(
                vital("Янв", 122, 80, 74),
                vital("Фев", 126, 82, 78),
                vital("Мар", 119, 78, 72),
                vital("Апр", 131, 85, 81),
                vital("Май", 124, 81, 76),
                vital("Июн", 128, 83, 79)));
        d.put("vitals", vitals);
        d.put("recentVisits", List.of(
                visit("2026-05-21", "Городская поликлиника №4", "Плановый осмотр эндокринолога", "Доктор С. Ким"),
                visit("2026-03-08", "Медцентр «Сункар»", "Обострение астмы", "Доктор А. Жумабаев"),
                visit("2025-12-15", "Городская поликлиника №4", "Контроль ТТГ", "Доктор С. Ким")));
        d.put("immunizations", List.of("Грипп (2025)", "COVID-19 (бустер, 2024)", "Столбняк (2021)"));
        d.put("aiNotes", "ИИ-ассистент: уровень ТТГ стабилизировался на фоне терапии. "
                + "Отмечается лёгкая тенденция к повышению систолического давления за последние 2 месяца — "
                + "рекомендуется контроль АД и консультация кардиолога. Критических взаимодействий "
                + "назначенных препаратов не выявлено. Важно: аллергия на пенициллин — исключить "
                + "бета-лактамные антибиотики.");
        return d;
    }

    private Map<String, Object> medication(String name, String dose, String schedule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("dose", dose);
        m.put("schedule", schedule);
        return m;
    }

    private Map<String, Object> vital(String label, int systolic, int diastolic, int pulse) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("label", label);
        v.put("systolic", systolic);
        v.put("diastolic", diastolic);
        v.put("pulse", pulse);
        return v;
    }

    private Map<String, Object> visit(String date, String clinic, String reason, String doctor) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("date", date);
        v.put("clinic", clinic);
        v.put("reason", reason);
        v.put("doctor", doctor);
        return v;
    }

    // ------------------------------------------------------------------
    //  SCENARIO B — Retail item (RETAIL_ADIDAS_SHIRT)
    // ------------------------------------------------------------------

    private Map<String, Object> adidasShirt() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("productName", "Adidas Originals Trefoil — чёрная футболка");
        d.put("brand", "Adidas");
        d.put("sku", "RETAIL_ADIDAS_SHIRT");
        d.put("price", 25000L);
        d.put("currency", "₸");
        d.put("description", "Классическая хлопковая футболка Adidas Originals с логотипом Trefoil. "
                + "Мягкий джерси 100% хлопок, прямой крой унисекс. Оригинальная продукция с гарантией бренда.");
        d.put("rating", 4.7);
        d.put("reviews", 318);
        d.put("colors", List.of("Чёрный", "Белый", "Тёмно-синий"));
        d.put("sizes", List.of(
                size("S", 5),
                size("M", 12),
                size("L", 0),
                size("XL", 3)));
        d.put("alternatives", List.of(
                alternative("Kaspi Магазин", 22990L, "https://kaspi.kz", "Дешевле на 2010 ₸, доставка 1–2 дня"),
                alternative("Wildberries", 21500L, "https://www.wildberries.kz", "Самая низкая цена, доставка 3–5 дней"),
                alternative("Lamoda", 23700L, "https://www.lamoda.kz", "Бесплатная примерка при доставке")));
        Map<String, Object> loyalty = new LinkedHashMap<>();
        loyalty.put("code", "IDEAQR-ADIDAS-10");
        loyalty.put("discount", "10%");
        loyalty.put("note", "Эксклюзивный промокод участника IDEAQR. Действует в фирменном магазине Adidas.");
        d.put("loyalty", loyalty);
        return d;
    }

    private Map<String, Object> size(String size, int stock) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("size", size);
        s.put("stock", stock);
        return s;
    }

    private Map<String, Object> alternative(String store, long price, String url, String note) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("store", store);
        a.put("price", price);
        a.put("url", url);
        a.put("note", note);
        return a;
    }

    // ------------------------------------------------------------------
    //  SCENARIO C — Eco smart bin (ECO_SMART_BIN_102)
    // ------------------------------------------------------------------

    private Map<String, Object> smartBin102() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("binId", "ECO_SMART_BIN_102");
        d.put("title", "Умный контейнер №102");
        d.put("location", "г. Астана, ул. Кабанбай батыра, 53");
        d.put("coordinates", "51.0909, 71.4187");
        d.put("fillLevel", 82);
        d.put("status", "Требует вывоза");
        d.put("environmentalTier", "Зелёный уровень B");
        d.put("wasteTypes", List.of("Смешанные отходы", "Пластик", "Бумага"));
        d.put("lastEmptied", "2026-06-14 07:30");
        d.put("pickupSchedule", List.of(
                pickup("Понедельник", "07:00"),
                pickup("Среда", "07:00"),
                pickup("Пятница", "07:00"),
                pickup("Воскресенье", "08:30")));
        d.put("operator", "ТОО «Astana Tazalyk»");
        d.put("co2SavedKg", 1240);
        d.put("recyclingRate", 63);
        d.put("actions", List.of("report"));
        return d;
    }

    private Map<String, Object> pickup(String day, String time) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("day", day);
        p.put("time", time);
        return p;
    }

    // ------------------------------------------------------------------
    //  Infrastructure asset (INFRA_SUBSTATION_07) — role + time gated
    // ------------------------------------------------------------------

    private Map<String, Object> substation07() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("assetId", "INFRA_SUBSTATION_07");
        d.put("title", "Трансформаторная подстанция №07");
        d.put("assetType", "Электроподстанция 10/0.4 кВ");
        d.put("location", "г. Астана, район Есиль, ул. Сыганак, 18");
        d.put("status", "В эксплуатации");
        d.put("lastInspection", "2026-05-28");
        d.put("nextMaintenance", "2026-07-15");
        d.put("voltage", "10 кВ / 0.4 кВ");
        d.put("operator", "АО «Астана-РЭК»");
        d.put("technicalNotes", "Плановое обслуживание выполнено. Замечаний по изоляции трансформатора Т-2 нет. "
                + "Рекомендована проверка контуров заземления при следующем визите.");
        d.put("actions", List.of("report"));
        return d;
    }

    // ------------------------------------------------------------------
    //  Typed-but-unknown fallback
    // ------------------------------------------------------------------

    private Map<String, Object> unknownButTyped(ObjectCategory category, String objectUid) {
        Map<String, Object> d = new LinkedHashMap<>();
        switch (category) {
            case MEDICAL -> {
                d.put("patientName", "Карта пациента");
                d.put("patientId", objectUid);
                d.put("note", "Запись найдена в медицинском реестре, но детальная карта недоступна в демо-режиме.");
            }
            case RETAIL -> {
                d.put("productName", "Товар");
                d.put("sku", objectUid);
                d.put("price", 0L);
                d.put("currency", "₸");
                d.put("description", "Товар найден в торговом реестре, но карточка не заполнена.");
                d.put("sizes", List.of());
                d.put("alternatives", List.of());
            }
            case ECO -> {
                d.put("binId", objectUid);
                d.put("title", "Экологический объект");
                d.put("status", "Нет данных");
                d.put("actions", List.of("report"));
            }
            case INFRASTRUCTURE -> {
                d.put("assetId", objectUid);
                d.put("title", "Инфраструктурный объект");
                d.put("status", "Нет данных");
                d.put("actions", List.of("report"));
            }
            default -> {
                d.put("title", objectUid);
                d.put("description", "Объект найден, но данные отсутствуют.");
            }
        }
        return d;
    }

    // ------------------------------------------------------------------
    //  JSON helper
    // ------------------------------------------------------------------

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }
}
