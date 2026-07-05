package com.ideaqr.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * «Единый национальный QR» (Phase 2): у гражданина ОДИН личный QR, а данные за ним —
 * это связанный пакет реестровых объектов («цифровое досье»), который формируется
 * автоматически при регистрации через mock-eGov:
 *
 * <ul>
 *   <li><b>MED-*</b> — медицинская карта (CONFIDENTIAL; врач — только с согласия пациента,
 *       фармацевт — только срез рецептов);</li>
 *   <li><b>LEGAL-*</b> — правовое досье: справка о несудимости, штрафы, розыск
 *       (SECRET; только полиция при исполнении);</li>
 *   <li><b>VCARD-*</b> — публичная цифровая визитка (PUBLIC; открывается любому гражданину).</li>
 * </ul>
 *
 * <p>Идентификаторы детерминированы от identity UID, поэтому досье находится без
 * дополнительных связей и <b>лениво доусоздаётся</b> для аккаунтов, заведённых до Phase 2.
 * Все объекты досье живут в ПУБЛИЧНОМ тенанте: врач больницы или полицейский из своего
 * тенанта обязаны уметь их разрешить — доступ к содержимому при этом по-прежнему решает
 * движок политик, а не факт разрешимости идентификатора. Мок-данные генерируются
 * детерминированно от UID личности (без внешних систем — investor-stage MVP).</p>
 */
@Service
@RequiredArgsConstructor
public class CitizenDossierService {

    public static final String MEDICAL_PREFIX = "MED-";
    public static final String LEGAL_PREFIX = "LEGAL-";
    public static final String VCARD_PREFIX = "VCARD-";

    private final RegistryObjectRepository registryObjectRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Полный пакет досье одной личности. */
    public record Dossier(RegistryObject medical, RegistryObject legal, RegistryObject vcard) {}

    public static String medicalUidFor(UUID identityUid) { return MEDICAL_PREFIX + shortUid(identityUid); }
    public static String legalUidFor(UUID identityUid) { return LEGAL_PREFIX + shortUid(identityUid); }
    public static String vcardUidFor(UUID identityUid) { return VCARD_PREFIX + shortUid(identityUid); }

    /** True для объектов-досье (их не показываем среди передаваемого имущества в «Мои объекты»). */
    public static boolean isDossierObject(String objectUid) {
        String u = objectUid == null ? "" : objectUid.toUpperCase(Locale.ROOT);
        return u.startsWith(MEDICAL_PREFIX) || u.startsWith(LEGAL_PREFIX) || u.startsWith(VCARD_PREFIX);
    }

    /**
     * Гарантирует полный пакет досье для личности (идемпотентно). {@code person} — снимок
     * mock-eGov при регистрации по телефону; {@code null} для классической регистрации и
     * ленивого доусоздания — тогда паспортные поля генерируются детерминированно.
     */
    @Transactional
    public Dossier ensureFor(User user, Identity identity, MockEgovService.EgovPerson person) {
        if (identity == null || identity.getIdentityType() != IdentityType.PRIMARY) {
            return null;
        }
        UUID uid = identity.getIdentityUid();
        boolean[] created = {false};
        RegistryObject med = ensureObject(medicalUidFor(uid), ObjectCategory.MEDICAL,
                "Медкарта · " + fullName(user), uid, created,
                () -> toJson(medicalPayload(user, identity, person)));
        RegistryObject legal = ensureObject(legalUidFor(uid), ObjectCategory.LEGAL,
                "Правовой статус · " + fullName(user), uid, created,
                () -> toJson(legalPayload(user, identity, person)));
        RegistryObject vcard = ensureObject(vcardUidFor(uid), ObjectCategory.GENERAL,
                "Цифровая визитка · " + fullName(user), uid, created,
                () -> toJson(vcardPayload(user, identity, person)));
        if (created[0]) {
            auditService.record(uid, vcard.getObjectUid(), HistoryEventType.IDENTITY_VERIFIED,
                    "Сформирован цифровой пакет гражданина: медкарта, правовой статус, визитка.");
        }
        return new Dossier(med, legal, vcard);
    }

    /** Ленивое доусоздание по identity UID (для аккаунтов, созданных до Phase 2). */
    @Transactional
    public Optional<Dossier> ensureForIdentity(Identity identity) {
        return userRepository.findByIdentityUid(identity.getIdentityUid())
                .map(u -> ensureFor(u, identity, null));
    }

    public Optional<RegistryObject> find(String objectUid) {
        return registryObjectRepository.findByObjectUidAnyTenant(objectUid);
    }

    /** Разбор data_json объекта досье в карту (для сборки визитки/профиля на сервере). */
    public Map<String, Object> payload(RegistryObject object) {
        try {
            return objectMapper.readValue(object.getDataJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------
    //  Object provisioning
    // ------------------------------------------------------------------

    private RegistryObject ensureObject(String objectUid, ObjectCategory category, String displayName,
                                        UUID identityUid, boolean[] created, Supplier<String> json) {
        return registryObjectRepository.findByObjectUidAnyTenant(objectUid).orElseGet(() -> {
            created[0] = true;
            return registryObjectRepository.save(RegistryObject.builder()
                    .objectUid(objectUid)
                    .category(category)
                    .displayName(displayName)
                    .dataJson(json.get())
                    .createdByIdentityUid(identityUid)
                    .ownerIdentityUid(identityUid)
                    // Досье принадлежит гражданину, а не организации: всегда публичный тенант,
                    // чтобы специалист из любого тенанта мог разрешить идентификатор (доступ к
                    // содержимому решает движок политик, а не изоляция тенантов).
                    .tenantId(TenantContext.PUBLIC_TENANT)
                    .build());
        });
    }

    // ------------------------------------------------------------------
    //  Deterministic mock data (investor-stage: no real registries behind)
    // ------------------------------------------------------------------

    private Map<String, Object> medicalPayload(User user, Identity identity, MockEgovService.EgovPerson person) {
        Random rnd = seeded(identity.getIdentityUid());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("patientIdentityUid", identity.getIdentityUid().toString());
        d.put("patientName", fullName(user));
        d.put("patientId", medicalUidFor(identity.getIdentityUid()));
        d.put("age", person != null ? age(person.birthDateIso()) : 22 + rnd.nextInt(34));
        d.put("gender", person != null ? person.gender() : (rnd.nextBoolean() ? "Мужской" : "Женский"));
        d.put("bloodType", pick(rnd, List.of("I (0) Rh+", "II (A) Rh+", "III (B) Rh+", "IV (AB) Rh+", "II (A) Rh−")));
        d.put("iinMasked", maskIin(person != null ? person.iin() : pseudoIin(rnd)));

        List<String> allergies = rnd.nextInt(10) < 4
                ? List.of(pick(rnd, List.of("Пенициллин", "Пыльца растений", "Лактоза", "Орехи")))
                : List.of();
        d.put("allergies", allergies);

        boolean chronic = rnd.nextInt(10) < 3;
        d.put("chronicConditions", chronic
                ? List.of(pick(rnd, List.of("Бронхиальная астма (лёгкая форма)", "Гастрит (ремиссия)",
                        "Артериальная гипертензия I ст.")))
                : List.of());
        d.put("medications", chronic
                ? List.of(Map.of("name", pick(rnd, List.of("Сальбутамол", "Омепразол", "Лизиноприл")),
                        "dose", pick(rnd, List.of("100 мкг", "20 мг", "10 мг")),
                        "schedule", pick(rnd, List.of("по потребности", "1 раз в день утром", "2 раза в день"))))
                : List.of());

        List<Map<String, Object>> visits = new ArrayList<>();
        visits.add(Map.of("date", "2026-0" + (4 + rnd.nextInt(3)) + "-" + (10 + rnd.nextInt(18)),
                "clinic", pick(rnd, List.of("Городская поликлиника №4", "Медцентр «Демеу»", "Городская больница №1")),
                "reason", pick(rnd, List.of("Профилактический осмотр", "ОРВИ", "Консультация терапевта")),
                "doctor", pick(rnd, List.of("Терапевт Ким", "ВОП Сағынова", "Терапевт Абенов"))));
        d.put("recentVisits", visits);

        d.put("immunizations", List.of("Комплексная (детство) — полная", "COVID-19 (2021, 2022)",
                rnd.nextBoolean() ? "Грипп (2025)" : "Грипп (2024)"));

        List<Map<String, Object>> series = new ArrayList<>();
        String[] labels = {"Фев", "Мар", "Апр", "Май", "Июн"};
        for (String label : labels) {
            series.add(Map.of("label", label,
                    "systolic", 110 + rnd.nextInt(20),
                    "diastolic", 70 + rnd.nextInt(15),
                    "pulse", 62 + rnd.nextInt(20)));
        }
        d.put("vitals", Map.of("series", series));

        d.put("aiNotes", "Состояние стабильное. Показатели в пределах возрастной нормы. "
                + (allergies.isEmpty() ? "Аллергологический анамнез не отягощён." :
                "Учитывать аллергию при назначениях.") + " Плановый осмотр — через 6 месяцев.");
        d.put("note", "Единый QR: карта открывается врачу в рабочем режиме и только с согласия пациента.");
        return d;
    }

    private Map<String, Object> legalPayload(User user, Identity identity, MockEgovService.EgovPerson person) {
        Random rnd = seeded(identity.getIdentityUid());
        String sfx = shortUid(identity.getIdentityUid());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("subjectIdentityUid", identity.getIdentityUid().toString());
        d.put("fullName", fullName(user));
        d.put("iin", person != null ? person.iin() : pseudoIin(rnd));
        d.put("birthDate", person != null ? person.birthDateDisplay() : (1 + rnd.nextInt(28)) + ".0"
                + (1 + rnd.nextInt(9)) + "." + (1975 + rnd.nextInt(30)));
        d.put("address", "г. " + (person != null ? person.city() : "Астана") + ", "
                + (person != null ? person.address() : "ул. Республики " + (1 + rnd.nextInt(90))));
        d.put("criminalRecord", Map.of(
                "status", "НЕ СУДИМ(А)",
                "certificateNo", "KZ-" + sfx + "-2026",
                "issuedAt", "2026-07-01",
                "source", "КПСиСУ ГП РК (демо-реестр)"));
        d.put("wanted", "В розыске не числится");

        List<Map<String, Object>> fines = new ArrayList<>();
        int fineCount = rnd.nextInt(3);
        List<Map<String, Object>> pool = List.of(
                Map.of("article", "ст. 592 КоАП — превышение скорости", "amount", 13_800),
                Map.of("article", "ст. 597 КоАП — парковка в неположенном месте", "amount", 6_900),
                Map.of("article", "ст. 434 КоАП — нарушение тишины", "amount", 9_200));
        for (int i = 0; i < fineCount; i++) {
            Map<String, Object> base = pool.get((rnd.nextInt(pool.size())));
            Map<String, Object> fine = new LinkedHashMap<>(base);
            fine.put("date", "2026-0" + (1 + rnd.nextInt(6)) + "-" + (10 + rnd.nextInt(18)));
            fine.put("status", rnd.nextBoolean() ? "ОПЛАЧЕН" : "НЕ ОПЛАЧЕН");
            fines.add(fine);
        }
        d.put("fines", fines);

        if (rnd.nextInt(10) < 6) {
            d.put("drivingLicense", Map.of(
                    "number", "KZ" + String.format("%07d", Math.abs(identity.getIdentityUid().hashCode()) % 10_000_000),
                    "categories", "B, B1",
                    "validTill", "203" + rnd.nextInt(3) + "-06-30"));
        }
        d.put("restrictions", "Действующих ограничений нет");
        d.put("note", "Уровень SECRET. Доступ — только сотрудникам полиции при исполнении; каждый просмотр фиксируется в журнале.");
        return d;
    }

    private Map<String, Object> vcardPayload(User user, Identity identity, MockEgovService.EgovPerson person) {
        String handle = translit(user.getFirstName()) + "." + translit(user.getLastName());
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ownerIdentityUid", identity.getIdentityUid().toString());
        d.put("fullName", fullName(user));
        d.put("profession", user.getProfession());
        d.put("city", person != null ? person.city() : "Астана");
        String phone = user.getUsername() != null && user.getUsername().matches("7\\d{10}")
                ? "+7 " + user.getUsername().substring(1, 4) + " " + user.getUsername().substring(4, 7)
                + "-" + user.getUsername().substring(7, 9) + "-" + user.getUsername().substring(9, 11)
                : null;
        if (phone != null) {
            d.put("phone", phone);
        }
        d.put("email", handle + "@mail.kz");
        d.put("telegram", "@" + handle.replace(".", "_"));
        d.put("about", "Гражданин Республики Казахстан · Единый национальный QR");
        d.put("note", "Публичная визитка. Полный профиль — только с подтверждения владельца.");
        return d;
    }

    // ------------------------------------------------------------------

    private static String shortUid(UUID uid) {
        return uid.toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static Random seeded(UUID uid) {
        return new Random(uid.getMostSignificantBits() ^ uid.getLeastSignificantBits());
    }

    private static <T> T pick(Random rnd, List<T> pool) {
        return pool.get(rnd.nextInt(pool.size()));
    }

    private static String fullName(User user) {
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    private static int age(String birthIso) {
        try {
            return Period.between(LocalDate.parse(birthIso), LocalDate.of(2026, 7, 1)).getYears();
        } catch (Exception e) {
            return 30;
        }
    }

    private static String maskIin(String iin) {
        if (iin == null || iin.length() < 4) {
            return "••••••••••••";
        }
        return iin.charAt(0) + "•••••••••" + iin.substring(iin.length() - 2);
    }

    private static String pseudoIin(Random rnd) {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(rnd.nextInt(10));
        }
        return sb.toString();
    }

    /** Компактная транслитерация для email/telegram-хэндлов визитки. */
    private static String translit(String value) {
        String src = "абвгдежзийклмнопрстуфхцчшщъыьэюяёәіңғүұқөһ";
        String[] dst = {"a","b","v","g","d","e","zh","z","i","y","k","l","m","n","o","p","r","s","t","u",
                "f","h","c","ch","sh","sch","","y","","e","yu","ya","e","a","i","n","g","u","u","k","o","h"};
        StringBuilder sb = new StringBuilder();
        for (char ch : (value == null ? "" : value).toLowerCase(Locale.ROOT).toCharArray()) {
            int idx = src.indexOf(ch);
            if (idx >= 0) {
                sb.append(dst[idx]);
            } else if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            }
        }
        return sb.length() == 0 ? "citizen" : sb.toString();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать данные досье", e);
        }
    }
}
