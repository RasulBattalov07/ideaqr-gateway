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
 *
 * <p>The demo registry holds platform-grade reference objects across the core
 * spheres — transport, medicine, commercial real estate and household services —
 * so a demonstration reflects the breadth of the platform rather than toy data.</p>
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
        demoRegistry.put("CAR_TOYOTA_CAMRY", new Demo(ObjectCategory.RETAIL, "Toyota Camry 2024", CAR_TOYOTA_CAMRY));
        demoRegistry.put("PATIENT_1024", new Demo(ObjectCategory.MEDICAL, "Медкарта пациента №1024", PATIENT_1024));
        demoRegistry.put("REALTY_OFFICE_1205", new Demo(ObjectCategory.GENERAL, "Офис №1205, БЦ «Нурлы Тау»", REALTY_OFFICE_1205));
        demoRegistry.put("SERVICE_MASTER_CALL", new Demo(ObjectCategory.GENERAL, "Услуга: вызов мастера", SERVICE_MASTER_CALL));
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
        if (u.startsWith("CAR") || u.startsWith("AUTO") || u.startsWith("VEHICLE") || u.startsWith("TOYOTA")
                || u.startsWith("RETAIL") || u.startsWith("PROD")) return ObjectCategory.RETAIL;
        if (u.startsWith("INFRA") || u.startsWith("SUBSTATION")) return ObjectCategory.INFRASTRUCTURE;
        if (u.startsWith("ECO") || u.startsWith("BIN")) return ObjectCategory.ECO;
        if (u.startsWith("REALTY") || u.startsWith("OFFICE") || u.startsWith("PROPERTY")
                || u.startsWith("SERVICE") || u.startsWith("MASTER")
                || u.startsWith("GENERAL") || u.startsWith("OBJ")) return ObjectCategory.GENERAL;
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

    private static final String CAR_TOYOTA_CAMRY = """
            {
              "productName": "Toyota Camry 2.5 Prestige",
              "brand": "Toyota", "sku": "01 KZ 777 ABC",
              "price": 18900000, "currency": "₸",
              "rating": 4.8, "reviews": 342,
              "description": "Седан бизнес-класса, 2024 г., пробег 0 км. Официальный дилер Toyota в Казахстане.",
              "colors": ["Белый перламутр", "Чёрный металлик", "Серебристый"],
              "alternatives": [
                {"store": "Toyota Astana Motors", "price": 18900000, "url": "#", "note": "Официальный дилер · гарантия 5 лет"},
                {"store": "Toyota Almaty", "price": 18750000, "url": "#", "note": "В наличии · трейд-ин"},
                {"store": "Вторичный рынок", "price": 17200000, "url": "#", "note": "Аналог 2023 г. с пробегом"}
              ],
              "loyalty": {"code": "TRADE-IN-2024", "note": "Трейд-ин старого авто + автокредит от 4% годовых", "discount": "−6%"}
            }
            """;

    private static final String PATIENT_1024 = """
            {
              "patientName": "Медкарта пациента №1024",
              "patientId": "PATIENT_1024",
              "age": 47, "gender": "Мужской", "bloodType": "III (B) Rh+",
              "iinMasked": "7•••••••••18",
              "allergies": ["Пенициллин"],
              "chronicConditions": ["Сахарный диабет 2 типа", "Артериальная гипертензия II ст."],
              "medications": [
                {"name": "Метформин", "dose": "1000 мг", "schedule": "2 раза в день"},
                {"name": "Периндоприл", "dose": "5 мг", "schedule": "1 раз в день, утром"}
              ],
              "vitals": {"series": [
                {"label": "Янв", "systolic": 150, "diastolic": 95, "pulse": 80},
                {"label": "Фев", "systolic": 144, "diastolic": 90, "pulse": 78},
                {"label": "Мар", "systolic": 138, "diastolic": 86, "pulse": 74},
                {"label": "Апр", "systolic": 132, "diastolic": 84, "pulse": 72},
                {"label": "Май", "systolic": 128, "diastolic": 82, "pulse": 70}
              ]},
              "recentVisits": [
                {"date": "2026-05-18", "clinic": "Городская поликлиника №4", "reason": "Контроль гликемии", "doctor": "Эндокринолог Бекова"},
                {"date": "2026-03-10", "clinic": "Кардиологический центр", "reason": "Плановый осмотр", "doctor": "Кардиолог Жумабаев"}
              ],
              "immunizations": ["Грипп 2025", "COVID-19 (ревакцинация)"],
              "aiNotes": "Давление стабилизируется на фоне терапии. Рекомендован контроль гликированного гемоглобина через 3 месяца.",
              "note": "Доступ к медицинской карте возможен только врачам и только в рабочее время (08:00–18:00)."
            }
            """;

    private static final String REALTY_OFFICE_1205 = """
            {
              "title": "Офис №1205, БЦ «Нурлы Тау»",
              "kind": "Коммерческая недвижимость",
              "description": "Офисное помещение класса A в деловом центре. Открытая планировка, панорамное остекление, готово к въезду.",
              "details": {
                "Адрес": "г. Астана, пр. Кабанбай батыра 15/1",
                "Площадь": "85 м²",
                "Этаж": "12 из 18",
                "Назначение": "Офис (класс A)",
                "Кадастровый номер": "21-315-042-1205",
                "Статус": "Свободно · аренда",
                "Ставка аренды": "12 500 ₸/м² в месяц"
              },
              "note": "Полные документы и история сделок доступны после авторизации."
            }
            """;

    private static final String SERVICE_MASTER_CALL = """
            {
              "title": "Вызов мастера на дом",
              "kind": "Услуга · быт",
              "description": "Бытовая услуга по заявке: сантехник, электрик или мастер на час. Выезд в течение 2 часов.",
              "details": {
                "Категория": "Сантехнические работы",
                "Исполнитель": "Сервис «Уют-Сервис»",
                "Рейтинг исполнителя": "4.9 / 5",
                "Время выезда": "до 2 часов",
                "Стоимость выезда": "5 000 ₸",
                "Гарантия на работы": "12 месяцев",
                "Статус": "Принимает заявки"
              },
              "note": "Оформление заявки и оплата доступны после авторизации."
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
