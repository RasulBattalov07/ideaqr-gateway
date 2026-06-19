package com.ideaqr.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a scanned object identifier to its category, display name and card
 * payload. Resolution order mirrors the brief: (1) objects created in the DB via
 * the admin panel, (2) the enriched demo registry (mock data simulating external
 * state/commercial registries), (3) prefix inference for anything else.
 */
@Service
@RequiredArgsConstructor
public class RegistryClient {

    private final RegistryObjectRepository registryObjectRepository;
    private final ObjectMapper objectMapper;

    /** Result of a resolution. {@code known} = backed by real data (DB or demo registry). */
    public record Resolved(boolean known, ObjectCategory category, String displayName, Map<String, Object> data) {}

    private record Demo(ObjectCategory category, String displayName, String json) {}

    private final Map<String, Demo> demoRegistry = new LinkedHashMap<>();

    @PostConstruct
    void loadDemoRegistry() {
        demoRegistry.put("PATIENT_7291", new Demo(ObjectCategory.MEDICAL, "Пациент 7291", PATIENT_7291));
        demoRegistry.put("RETAIL_ADIDAS_SHIRT", new Demo(ObjectCategory.RETAIL, "Adidas — чёрная футболка", RETAIL_ADIDAS_SHIRT));
        demoRegistry.put("ECO_SMART_BIN_102", new Demo(ObjectCategory.ECO, "Умный контейнер №102", ECO_SMART_BIN_102));
        demoRegistry.put("INFRA_SUBSTATION_07", new Demo(ObjectCategory.INFRASTRUCTURE, "Подстанция №07", INFRA_SUBSTATION_07));
    }

    public Resolved resolve(String objectUid) {
        String key = objectUid == null ? "" : objectUid.trim();
        if (key.isEmpty()) {
            return new Resolved(false, ObjectCategory.UNKNOWN, "—", minimal(key, "Пустой идентификатор."));
        }

        // 1. Objects minted through the admin panel.
        Optional<RegistryObject> dbObject = registryObjectRepository.findByObjectUid(key);
        if (dbObject.isPresent()) {
            RegistryObject o = dbObject.get();
            return new Resolved(true, o.getCategory(), o.getDisplayName(), parse(o.getDataJson()));
        }

        // 2. Enriched demo registry.
        Demo demo = demoRegistry.get(key.toUpperCase(Locale.ROOT));
        if (demo != null) {
            return new Resolved(true, demo.category(), demo.displayName(), parse(demo.json()));
        }

        // 3. Prefix inference.
        ObjectCategory inferred = inferCategory(key);
        if (inferred == ObjectCategory.UNKNOWN) {
            return new Resolved(false, ObjectCategory.UNKNOWN, key, minimal(key, "Объект не найден в реестре."));
        }
        return new Resolved(false, inferred, key,
                minimal(key, "Объект распознан по префиксу, но подробные данные в реестре отсутствуют."));
    }

    private ObjectCategory inferCategory(String key) {
        String u = key.toUpperCase(Locale.ROOT);
        if (u.startsWith("PATIENT") || u.startsWith("MED")) return ObjectCategory.MEDICAL;
        if (u.startsWith("RETAIL") || u.startsWith("PROD")) return ObjectCategory.RETAIL;
        if (u.startsWith("ECO") || u.startsWith("BIN")) return ObjectCategory.ECO;
        if (u.startsWith("INFRA") || u.startsWith("SUBSTATION")) return ObjectCategory.INFRASTRUCTURE;
        if (u.startsWith("GENERAL") || u.startsWith("OBJ")) return ObjectCategory.GENERAL;
        return ObjectCategory.UNKNOWN;
    }

    private Map<String, Object> minimal(String key, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", key);
        m.put("note", note);
        return m;
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Повреждённые данные объекта в реестре", e);
        }
    }

    // ------------------------------------------------------------------
    //  Enriched demo data (mock — simulates external registries)
    // ------------------------------------------------------------------

    private static final String PATIENT_7291 = """
            {
              "patientName": "Айгерим Нұрланқызы",
              "patientId": "PATIENT_7291",
              "age": 34, "gender": "Женский", "bloodType": "II (A) Rh+",
              "iinMasked": "8•••••••••42",
              "allergies": ["Пенициллин", "Амоксициллин"],
              "chronicConditions": ["Гипертония I ст.", "Гипотиреоз"],
              "medications": [
                {"name": "Лозартан", "dose": "50 мг", "schedule": "1 раз в день, утром"},
                {"name": "L-тироксин", "dose": "75 мкг", "schedule": "натощак"}
              ],
              "vitals": {"series": [
                {"label": "Янв", "systolic": 145, "diastolic": 92, "pulse": 78},
                {"label": "Фев", "systolic": 138, "diastolic": 88, "pulse": 74},
                {"label": "Мар", "systolic": 132, "diastolic": 85, "pulse": 72},
                {"label": "Апр", "systolic": 128, "diastolic": 82, "pulse": 70},
                {"label": "Май", "systolic": 125, "diastolic": 80, "pulse": 68}
              ]},
              "recentVisits": [
                {"date": "2026-05-12", "clinic": "ГП №4", "reason": "Плановый осмотр", "doctor": "Терапевт Сейтова"},
                {"date": "2026-03-03", "clinic": "Эндокринологический центр", "reason": "Контроль ТТГ", "doctor": "Эндокринолог Қасым"}
              ],
              "immunizations": ["COVID-19 (2 дозы)", "Грипп 2025", "Столбняк 2022"],
              "aiNotes": "Давление стабилизируется на фоне терапии. Рекомендован контроль ТТГ через 3 месяца.",
              "note": "Доступ к медицинской карте возможен только врачам и только в рабочее время."
            }
            """;

    private static final String RETAIL_ADIDAS_SHIRT = """
            {
              "productName": "Adidas — чёрная футболка",
              "brand": "Adidas", "sku": "RETAIL_ADIDAS_SHIRT",
              "price": 25000, "currency": "₸",
              "rating": 4.6, "reviews": 1280,
              "description": "Хлопковая футболка Adidas Originals, унисекс, чёрная.",
              "sizes": [
                {"size": "S", "stock": 5}, {"size": "M", "stock": 12},
                {"size": "L", "stock": 0}, {"size": "XL", "stock": 3}
              ],
              "colors": ["Чёрный", "Белый", "Тёмно-синий"],
              "alternatives": [
                {"store": "Kaspi Магазин", "price": 22990, "url": "https://kaspi.kz", "note": "Доставка 1–2 дня"},
                {"store": "Wildberries", "price": 23500, "url": "https://wildberries.kz", "note": "Завтра в пункте выдачи"},
                {"store": "Lamoda", "price": 24100, "url": "https://lamoda.kz", "note": "Бесплатная примерка"}
              ],
              "loyalty": {"code": "IDEAQR-ADIDAS-10", "note": "Скидка 10% в фирменном магазине", "discount": "−10%"}
            }
            """;

    private static final String ECO_SMART_BIN_102 = """
            {
              "title": "Умный контейнер №102",
              "binId": "ECO_SMART_BIN_102",
              "fillLevel": 82, "status": "Требует вывоза",
              "environmentalTier": "Класс А — раздельный сбор",
              "location": "г. Астана, ул. Кабанбай батыра 53",
              "operator": "Оператор РОП «Tazalyq»",
              "lastEmptied": "2026-06-15 07:40",
              "coordinates": "51.0909, 71.4187",
              "co2SavedKg": 1240, "recyclingRate": 63,
              "wasteTypes": ["Пластик", "Бумага", "Стекло", "Металл"],
              "pickupSchedule": [
                {"day": "Пн", "time": "08:00"}, {"day": "Ср", "time": "08:00"}, {"day": "Пт", "time": "08:00"}
              ]
            }
            """;

    private static final String INFRA_SUBSTATION_07 = """
            {
              "title": "Подстанция №07",
              "assetId": "INFRA_SUBSTATION_07",
              "assetType": "Трансформаторная подстанция", "status": "В работе",
              "voltage": "10/0.4 кВ",
              "location": "г. Астана, промзона Сарыарка",
              "operator": "АО «Астана-РЭК»",
              "lastInspection": "2026-04-20", "nextMaintenance": "2026-07-20",
              "technicalNotes": "Плановое ТО без отключений. Замена масла в Т-2 запланирована на III квартал."
            }
            """;
}
