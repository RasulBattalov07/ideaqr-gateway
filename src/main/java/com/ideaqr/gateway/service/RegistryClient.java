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
 * the admin panel / seeder, (2) the curated demo registry (reference objects that
 * are universally scannable regardless of tenant), (3) prefix inference.
 *
 * <p>The demo registry is the showcase set used by the quick-access buttons. Each
 * object exercises a distinct platform capability — guest conversion, role-gated
 * access, the Request→Decision→Interaction pipeline, ownership transfer, restricted
 * infrastructure access and an education document.</p>
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
        demoRegistry.put("RETAIL_NIKE_AF1", new Demo(ObjectCategory.RETAIL, "Кроссовки Nike Air Force 1", RETAIL_NIKE_AF1));
        demoRegistry.put("MED_RX_5521", new Demo(ObjectCategory.MEDICAL, "Медкарта · Серіков Айдос", MED_RX_5521));
        demoRegistry.put("SERVICE_TRASH_PICKUP", new Demo(ObjectCategory.GENERAL, "Вынос мусора от двери квартиры", SERVICE_TRASH_PICKUP));
        demoRegistry.put("CAR_TOYOTA_CAMRY", new Demo(ObjectCategory.RETAIL, "Toyota Camry 2024", CAR_TOYOTA_CAMRY));
        demoRegistry.put("LOCK_OFFICE_AITU", new Demo(ObjectCategory.INFRASTRUCTURE, "Умный замок · офис AITU", LOCK_OFFICE_AITU));
        demoRegistry.put("DOC_STUDENT_AITU", new Demo(ObjectCategory.GENERAL, "Студенческий билет AITU", DOC_STUDENT_AITU));
    }

    public Resolved resolve(String objectUid) {
        String key = objectUid == null ? "" : objectUid.trim();
        if (key.isEmpty()) {
            return new Resolved(false, ObjectCategory.UNKNOWN, "—", minimal(key, "Пустой идентификатор."));
        }

        // 1. Objects minted through the admin panel / seeded into the DB.
        Optional<RegistryObject> dbObject = registryObjectRepository.findByObjectUid(key);
        if (dbObject.isPresent()) {
            RegistryObject o = dbObject.get();
            return new Resolved(true, o.getCategory(), o.getDisplayName(), parse(o.getDataJson()));
        }

        // 2. Curated demo registry (universally scannable).
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
        if (u.startsWith("PATIENT") || u.startsWith("MED") || u.startsWith("RX")) return ObjectCategory.MEDICAL;
        if (u.startsWith("CAR") || u.startsWith("AUTO") || u.startsWith("VEHICLE") || u.startsWith("TOYOTA")
                || u.startsWith("RETAIL") || u.startsWith("NIKE") || u.startsWith("PROD")) return ObjectCategory.RETAIL;
        if (u.startsWith("INFRA") || u.startsWith("SUBSTATION") || u.startsWith("LOCK")) return ObjectCategory.INFRASTRUCTURE;
        if (u.startsWith("ECO") || u.startsWith("BIN")) return ObjectCategory.ECO;
        if (u.startsWith("SERVICE") || u.startsWith("TRASH") || u.startsWith("DOC") || u.startsWith("STUDENT")
                || u.startsWith("REALTY") || u.startsWith("OFFICE") || u.startsWith("GENERAL") || u.startsWith("OBJ"))
            return ObjectCategory.GENERAL;
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
    //  Curated demo data (reference objects for the quick-access buttons)
    // ------------------------------------------------------------------

    private static final String RETAIL_NIKE_AF1 = """
            {
              "productName": "Nike Air Force 1 '07",
              "brand": "Nike", "sku": "RETAIL_NIKE_AF1",
              "price": 54990, "currency": "₸",
              "rating": 4.9, "reviews": 2841,
              "description": "Классические кожаные кроссовки унисекс, белый цвет. Оригинал.",
              "sizes": [
                {"size": "40", "stock": 7}, {"size": "41", "stock": 12},
                {"size": "42", "stock": 3}, {"size": "43", "stock": 0}
              ],
              "colors": ["Белый", "Чёрный", "Серый"],
              "alternatives": [
                {"store": "Kaspi Магазин", "price": 52990, "url": "#", "note": "Доставка 1–2 дня"},
                {"store": "Lamoda", "price": 53500, "url": "#", "note": "Бесплатная примерка"},
                {"store": "Wildberries", "price": 51990, "url": "#", "note": "Завтра в пункте выдачи"}
              ],
              "loyalty": {"code": "IDEAQR-NIKE-10", "note": "Скидка 10% в фирменном магазине", "discount": "−10%"}
            }
            """;

    // The patient of record is carried in {@code patientIdentityUid} (the demo "Айдос" identity,
    // a fixed-UUID account seeded by DataSeeder). The gateway uses it to require the PATIENT'S
    // explicit consent before a doctor/pharmacist can open this card (Owner-Approval for medical
    // records) — the professional gates alone are no longer enough.
    private static final String MED_RX_5521 = """
            {
              "patientIdentityUid": "aaaaaaaa-0000-0000-0000-000000000007",
              "patientName": "Серіков Айдос Бағланұлы",
              "patientId": "MED_RX_5521",
              "age": 21, "gender": "Мужской", "bloodType": "II (A) Rh+",
              "iinMasked": "0•••••••••57",
              "allergies": ["Пенициллин"],
              "chronicConditions": ["Бронхиальная астма (лёгкая форма)"],
              "medications": [
                {"name": "Сальбутамол", "dose": "100 мкг", "schedule": "по потребности (ингалятор)"},
                {"name": "Будесонид", "dose": "200 мкг", "schedule": "2 раза в день"}
              ],
              "recentVisits": [
                {"date": "2026-06-18", "clinic": "Студенческая поликлиника AITU", "reason": "Профилактический осмотр", "doctor": "Терапевт Ким"}
              ],
              "aiNotes": "Состояние стабильное. Контроль ингаляционной терапии, повторный осмотр через 6 месяцев.",
              "note": "Медкарта пациента. Доступ — врачам и фармацевтам в рабочее время И только с согласия пациента."
            }
            """;

    private static final String SERVICE_TRASH_PICKUP = """
            {
              "title": "Вынос мусора от двери квартиры",
              "kind": "Услуга · ЖКХ",
              "description": "Ежедневный вынос бытовых отходов от двери квартиры по заявке жильца. Каждая заявка проходит конвейер Request → Decision → Interaction.",
              "details": {
                "Адрес": "ЖК «Хайвилл», блок Б, кв. 84",
                "Оператор": "УК «Comfort Service»",
                "График": "Ежедневно, 08:00–10:00",
                "Тариф": "3 000 ₸ в месяц",
                "Рейтинг сервиса": "4.7 / 5",
                "Статус": "Принимает заявки"
              },
              "note": "Оформление и оплата заявки доступны после авторизации."
            }
            """;

    /** Public so the seeder can mint the same Toyota Camry as a real DB object (for the transfer demo). */
    public static final String CAR_TOYOTA_CAMRY = """
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

    private static final String LOCK_OFFICE_AITU = """
            {
              "title": "Умный замок — офис AITU",
              "assetId": "LOCK_OFFICE_AITU",
              "assetType": "Электронный замок СКУД", "status": "Активен",
              "voltage": "—",
              "location": "Astana IT University, блок C, ауд. 1201",
              "operator": "Служба безопасности AITU",
              "lastInspection": "2026-06-01", "nextMaintenance": "2026-09-01",
              "technicalNotes": "Доступ предоставляется только авторизованному персоналу (инспектор / инженер) в рабочее время. Каждая попытка входа фиксируется как Request → Decision."
            }
            """;

    private static final String DOC_STUDENT_AITU = """
            {
              "title": "Студенческий билет — AITU",
              "kind": "Документ · образование",
              "description": "Цифровой студенческий билет Astana IT University. Идентификатор студента в экосистеме платформы.",
              "details": {
                "ФИО": "Серіков Айдос Бағланұлы",
                "Университет": "Astana IT University (AITU)",
                "Факультет": "Вычислительная техника и ПО",
                "Специальность": "Программная инженерия",
                "Курс": "3 курс",
                "Студенческий №": "AITU-2022-1457",
                "Статус": "Активен",
                "Действителен до": "30.06.2026"
              },
              "note": "Полные данные и история доступны после авторизации."
            }
            """;
}
