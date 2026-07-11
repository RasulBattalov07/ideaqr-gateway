package com.ideaqr.gateway.config;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Organization;
import com.ideaqr.gateway.domain.Qr;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.EmploymentStatus;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import com.ideaqr.gateway.domain.enums.RoleType;
import com.ideaqr.gateway.dto.RegistrationRequest;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.RegistryObjectRepository;
import com.ideaqr.gateway.repository.UserRepository;
import com.ideaqr.gateway.service.IdentityService;
import com.ideaqr.gateway.service.OrganizationService;
import com.ideaqr.gateway.service.QrService;
import com.ideaqr.gateway.service.UserService;
import com.ideaqr.gateway.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Seeds the full demo stand for the v2 architecture. Idempotent on every boot — on an
 * existing database it also REPAIRS drifted state (memberships are re-promoted to ACTIVE,
 * missing dossiers are provisioned), so a stale deployment self-heals to demo-ready.
 *
 * <ul>
 *   <li><b>Accounts:</b> eleven demo users; every specialist (doctor, pharmacist, inspector,
 *       police, operator, executor, cashier, seller, admin) gets an ACTIVE
 *       {@code OrganizationMembership} — without one a specialist role is inert (working
 *       mode, and thus every professional gate, requires ACTIVE membership). Executor
 *       (универсальный исполнитель) and cashier drive the three-party household-services
 *       and retail-checkout demos.</li>
 *   <li><b>Citizen dossiers (Phase 2):</b> MED-/LEGAL-/VCARD- registry objects for each
 *       account, public tenant, plus a starter prescription so the pharmacist demo works
 *       out of the box.</li>
 *   <li><b>Starter service order:</b> one open PLUMBER заявка from the citizen so the
 *       operator's dispatch queue is populated on first login (assign → arrival → pay).</li>
 *   <li><b>Universal object governance:</b> OWNED demo items — Toyota Camry (owner: admin)
 *       and the citizen's personal jacket + fridge (P2P owner-consent, AI card, police
 *       disclosure demos) — as real DB rows in the public tenant. UNOWNED showcase items
 *       (Nike sneakers, AITU lock, студбилет…) stay in {@link
 *       com.ideaqr.gateway.service.RegistryClient} as pre-ownership catalog cards, claimable
 *       via {@code /api/v2/objects/{uid}/claim} — the QR never changes on claim/transfer.</li>
 *   <li><b>Business card:</b> the fixed-UUID «Айдос» identity for the P2P Owner-Approval
 *       demo (also the {@code MED_RX_5521} patient of record).</li>
 * </ul>
 *
 * Credentials are throwaway demo values documented in the README.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    /** Stable identity for the demo "digital business card" — documented in the README. */
    private static final UUID BUSINESS_CARD_UID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000007");

    private final UserRepository userRepository;
    private final IdentityRepository identityRepository;
    private final RegistryObjectRepository registryObjectRepository;
    private final UserService userService;
    private final QrService qrService;
    private final OrganizationService organizationService;
    private final PasswordEncoder passwordEncoder;
    private final com.ideaqr.gateway.service.CitizenDossierService citizenDossierService;
    private final com.ideaqr.gateway.service.MedicalService medicalService;
    private final com.ideaqr.gateway.service.ServiceOrderService serviceOrderService;

    @Override
    public void run(String... args) {
        Organization hospital = organizationService.ensureOrganization("Городская больница", "MEDICAL");
        Organization grid = organizationService.ensureOrganization("АО «Астана-РЭК»", "INFRASTRUCTURE");
        Organization retail = organizationService.ensureOrganization("IDEAQR Retail", "RETAIL");
        Organization police = organizationService.ensureOrganization("Департамент полиции Астаны", "GOVERNMENT");
        Organization comfort = organizationService.ensureOrganization("УК «Comfort Service»", "SERVICES");

        User admin = seed("admin", "Admin123!", "Аружан", "Сапарова", "EMPLOYED",
                UserService.PROFESSION_RETAIL_ADMIN, retail, "RETAIL_ADMIN");
        // A regular employee in the admin's own tenant, so the User Management module
        // has someone to block / change role / manage.
        seed("seller", "Seller123!", "Ербол", "Нурлан", "EMPLOYED",
                UserService.PROFESSION_SELLER, retail, "SELLER");
        seed("doctor", "Doctor123!", "Санжар", "Ким", "EMPLOYED",
                UserService.PROFESSION_DOCTOR, hospital, "DOCTOR");
        seed("pharmacist", "Pharma123!", "Алия", "Тулегенова", "EMPLOYED",
                UserService.PROFESSION_PHARMACIST, hospital, "PHARMACIST");
        seed("inspector", "Inspect123!", "Гульнара", "Ахметова", "EMPLOYED",
                UserService.PROFESSION_INSPECTOR, grid, "INSPECTOR");
        // Phase 2 (контекстный QR): полицейский видит по личному QR правовое досье.
        seed("police", "Police123!", "Нурлан", "Тлеубаев", "EMPLOYED",
                UserService.PROFESSION_POLICE, police, "POLICE");
        // Трёхсторонний флоу «Услуги и быт»: оператор-диспетчер назначает исполнителя,
        // универсальный исполнитель (сантехник/электрик/уборка — одна роль) выезжает,
        // заказчик сверяет его личность сканом QR и подтверждает приход/оплату.
        seed("operator", "Operator123!", "Багдат", "Жумабаев", "EMPLOYED",
                UserService.PROFESSION_SERVICE_OPERATOR, comfort, "SERVICE_OPERATOR");
        seed("executor", "Executor123!", "Арман", "Бекетов", "EMPLOYED",
                UserService.PROFESSION_EXECUTOR, comfort, "EXECUTOR");
        // Розничный чек-аут «Бизнес и магазины»: кассир сканирует QR покупателя, видит
        // корзину и «оплачен, но не выдан», принимает оплату и выдаёт (передача владения).
        User cashier = seed("cashier", "Cashier123!", "Динара", "Ким", "EMPLOYED",
                UserService.PROFESSION_CASHIER, retail, "CASHIER");
        User citizen = seed("citizen", "Citizen123!", "Дамир", "Оспанов", "UNEMPLOYED",
                UserService.PROFESSION_CITIZEN, null, null);

        // Showcase objects: the Toyota Camry as a real DB object (for the transfer demo),
        // and the digital business card identity (for the P2P Owner-Approval demo).
        if (admin != null) {
            seedCarObject(admin.getIdentityUid());
        }
        seedBusinessCard();

        // УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ: личные вещи гражданина (одежда + техника) как
        // реальные DB-объекты с владельцем — демо контекстного скана (гость / пользователь /
        // владелец / полиция), P2P-согласия на профиль владельца и AI-гардероба.
        if (citizen != null) {
            seedPersonalItems(citizen.getIdentityUid());
        }

        // СЦЕНАРИЙ «БИЗНЕС И МАГАЗИНЫ»: витрина демо-магазина — товары с ценой и forSale,
        // владелец — кассир (склад магазина), публичный тенант. Покупатель сканирует товар
        // («Оплатить на месте» / «В корзину»), кассир сканирует покупателя и выдаёт.
        if (cashier != null) {
            seedShopItems(cashier.getIdentityUid());
        }

        // Phase 2 (единый QR): каждому демо-гражданину — полный цифровой пакет (медкарта,
        // правовое досье, визитка). Выполняется вне тенант-контекста, объекты — публичные.
        seedDossiers();
        // Стартовый рецепт на медкарте гражданина: фармацевт может демонстрировать выдачу
        // сразу, без предварительного шага врача.
        if (citizen != null) {
            seedDemoPrescription(citizen);
        }
        // Стартовая заявка «Услуги и быт»: диспетчерская оператора не пуста на первом
        // показе — оператор сразу назначает исполнителя, без ручного шага заказчика.
        if (citizen != null) {
            seedDemoServiceOrder(citizen);
        }
    }

    /** Идемпотентно доукомплектовывает досье всем демо-аккаунтам (включая «Айдоса»). */
    private void seedDossiers() {
        for (String username : new String[]{"admin", "seller", "doctor", "pharmacist",
                "inspector", "police", "operator", "executor", "cashier", "citizen", "aidos"}) {
            userRepository.findByUsername(username).ifPresent(u ->
                    identityRepository.findById(u.getIdentityUid()).ifPresent(identity ->
                            citizenDossierService.ensureFor(u, identity, null)));
        }
    }

    /** Первый рецепт от врача Кима на карте гражданина — если карта ещё пуста. */
    private void seedDemoPrescription(User citizen) {
        String medUid = com.ideaqr.gateway.service.CitizenDossierService
                .medicalUidFor(citizen.getIdentityUid());
        try {
            if (!medicalService.listForObject(medUid).isEmpty()) {
                return;
            }
            userRepository.findByUsername("doctor")
                    .flatMap(d -> identityRepository.findById(d.getIdentityUid()))
                    .ifPresent(doctor -> medicalService.prescribe(doctor, medUid,
                            "Амоксициллин", "500 мг", "3 раза в день · 7 дней"));
            log.info("DataSeeder: seeded demo prescription on {}.", medUid);
        } catch (Exception e) {
            log.warn("DataSeeder: demo prescription skipped: {}", e.getMessage());
        }
    }

    /**
     * Первая заявка гражданина на услугу (сантехник) — штатным конвейером
     * {@link com.ideaqr.gateway.service.ServiceOrderService#order}, как если бы её оформил
     * сам заказчик: Request(SERVICE_ORDER) → Decision → Interaction(stage NEW) → History →
     * уведомление. Идемпотентно: если у гражданина уже есть хоть одна заявка (в т.ч. с
     * прошлого показа), новая не создаётся. Тенант-контекст — публичный, как в живой сессии
     * гражданина, чтобы журнальные записи легли в его тенант.
     */
    private void seedDemoServiceOrder(User citizen) {
        try {
            Identity identity = identityRepository.findById(citizen.getIdentityUid()).orElse(null);
            if (identity == null) {
                return;
            }
            TenantContext.setTenantId(TenantContext.PUBLIC_TENANT);
            try {
                if (!serviceOrderService.mine(identity).isEmpty()) {
                    return;
                }
                serviceOrderService.order(identity, "PLUMBER", "Течёт смеситель на кухне");
                log.info("DataSeeder: seeded demo service order (PLUMBER) for the operator queue.");
            } finally {
                TenantContext.clear();
            }
        } catch (Exception e) {
            log.warn("DataSeeder: demo service order skipped: {}", e.getMessage());
        }
    }

    private User seed(String username, String password, String firstName, String lastName,
                      String employmentStatus, String profession, Organization org, String workRole) {
        User user;
        if (userRepository.existsByUsername(username)) {
            user = userRepository.findByUsername(username).orElse(null);
        } else {
            RegistrationRequest request = new RegistrationRequest();
            request.setUsername(username);
            request.setPassword(password);
            request.setFirstName(firstName);
            request.setLastName(lastName);
            request.setEmploymentStatus(employmentStatus);
            request.setProfession(profession);
            // Stamp every row created for this account with the org's tenant (audit 5.3),
            // so each demo organisation becomes its own isolated tenant. Citizens with no
            // organisation fall to the public tenant.
            UUID tenant = org != null ? org.getOrganizationUid() : TenantContext.PUBLIC_TENANT;
            TenantContext.setTenantId(tenant);
            try {
                // Trusted server-side path: only DataSeeder may mint specialist/admin
                // accounts. The public register() endpoint always yields CITIZEN (audit 4.1/4.2).
                user = userService.provisionTrusted(request, profession);
            } finally {
                TenantContext.clear();
            }
            log.info("DataSeeder: provisioned demo account '{}' in tenant {}.", username, tenant);
        }
        if (user != null && org != null && workRole != null) {
            // ensureActiveMembership (не ensureMembership): на существующей базе членство,
            // застрявшее в PENDING/REJECTED, принудительно чинится до ACTIVE — иначе у
            // специалиста серый «Рабочий режим» и закрыты все профессиональные гейты.
            organizationService.ensureActiveMembership(user.getIdentityUid(), org.getOrganizationUid(), workRole);
        }
        return user;
    }

    /**
     * Mint the Toyota Camry as a real {@link RegistryObject} so the admin can demonstrate
     * ownership transfer (which operates on DB objects). Public tenant, so it resolves for
     * every scanner; the same card is also in the demo registry as a universal fallback.
     */
    private void seedCarObject(UUID ownerIdentityUid) {
        // Any-tenant lookup для идемпотентности (как в seedItem): tenant-фильтрованный поиск
        // на существующей базе мог не увидеть строку и попытаться вставить дубликат.
        if (registryObjectRepository.findByObjectUidAnyTenant("CAR_TOYOTA_CAMRY").isPresent()) {
            return;
        }
        registryObjectRepository.save(RegistryObject.builder()
                .objectUid("CAR_TOYOTA_CAMRY")
                .category(ObjectCategory.RETAIL)
                .displayName("Toyota Camry 2024")
                .dataJson(com.ideaqr.gateway.service.RegistryClient.CAR_TOYOTA_CAMRY)
                .createdByIdentityUid(ownerIdentityUid)
                .ownerIdentityUid(ownerIdentityUid)
                .tenantId(TenantContext.PUBLIC_TENANT)
                .build());
        log.info("DataSeeder: seeded transferable object CAR_TOYOTA_CAMRY.");
    }

    /**
     * Личные вещи гражданина (public tenant — вещь принадлежит человеку, не организации):
     * куртка (CLOTHING — AI-подбор и «гардероб») и холодильник (APPLIANCE — фильтры,
     * гарантия, сервис-центры). Владелец — демо-«citizen»: на них показываются все четыре
     * контекстных представления и служебное раскрытие владельца полицией.
     */
    private void seedPersonalItems(UUID citizenIdentityUid) {
        seedItem("ITEM_JACKET_UNIQLO", "Куртка Uniqlo Ultra Light Down", JACKET_JSON, citizenIdentityUid);
        seedItem("ITEM_FRIDGE_SAMSUNG", "Холодильник Samsung RB37 No Frost", FRIDGE_JSON, citizenIdentityUid);
    }

    /** Витрина демо-магазина: продаваемые через кассу товары (forSale + цена), владелец — кассир. */
    private void seedShopItems(UUID cashierIdentityUid) {
        seedItem("SHOP_TSHIRT_UNIQLO", "Футболка Uniqlo U Crew (белая)", SHOP_TSHIRT_JSON, cashierIdentityUid);
        seedItem("SHOP_JEANS_LEVIS", "Джинсы Levi's 501 Original", SHOP_JEANS_JSON, cashierIdentityUid);
        seedItem("SHOP_SNEAKERS_NB", "Кроссовки New Balance 574", SHOP_SNEAKERS_JSON, cashierIdentityUid);
    }

    private void seedItem(String objectUid, String displayName, String json, UUID ownerIdentityUid) {
        if (registryObjectRepository.findByObjectUidAnyTenant(objectUid).isPresent()) {
            return;
        }
        registryObjectRepository.save(RegistryObject.builder()
                .objectUid(objectUid)
                .category(ObjectCategory.RETAIL)
                .displayName(displayName)
                .dataJson(json)
                .createdByIdentityUid(ownerIdentityUid)
                .ownerIdentityUid(ownerIdentityUid)
                .tenantId(TenantContext.PUBLIC_TENANT)
                .build());
        log.info("DataSeeder: seeded personal item {}.", objectUid);
    }

    private static final String JACKET_JSON = """
            {
              "productName": "Куртка Uniqlo Ultra Light Down",
              "brand": "Uniqlo", "sku": "UL-DOWN-2025-M",
              "itemType": "CLOTHING",
              "price": 24990, "currency": "₸",
              "rating": 4.7, "reviews": 512,
              "description": "Ультралёгкий пуховик, тёмно-синий, размер M. Сезон: осень–зима.",
              "colors": ["Тёмно-синий"],
              "details": {
                "Размер": "M",
                "Материал": "Нейлон · пух 90/10",
                "Сезон": "Осень–Зима",
                "Уход": "Деликатная стирка 30°, не отбеливать"
              }
            }
            """;

    private static final String FRIDGE_JSON = """
            {
              "productName": "Холодильник Samsung RB37 No Frost",
              "brand": "Samsung", "sku": "RB37A5200SA",
              "itemType": "APPLIANCE",
              "price": 329990, "currency": "₸",
              "rating": 4.8, "reviews": 903,
              "description": "Двухкамерный холодильник, инверторный компрессор, класс A+, No Frost.",
              "serialNumber": "S/N 0D2G4-77H1",
              "warrantyUntil": "2027-11-01",
              "filters": ["Фильтр воды HAF-QIN/EXP", "Угольный фильтр запахов"],
              "details": {
                "Объём": "367 л",
                "Класс энергии": "A+",
                "Гарантия": "до 01.11.2027",
                "Серийный №": "0D2G4-77H1"
              }
            }
            """;

    private static final String SHOP_TSHIRT_JSON = """
            {
              "productName": "Футболка Uniqlo U Crew (белая)",
              "brand": "Uniqlo", "sku": "U-CREW-2026-M",
              "itemType": "CLOTHING",
              "price": 4990, "currency": "₸",
              "forSale": true,
              "store": "IDEAQR Store · ТРЦ Керуен",
              "rating": 4.6, "reviews": 214,
              "description": "Базовая футболка из плотного хлопка, белая, унисекс.",
              "details": {
                "Размер": "M",
                "Материал": "100% хлопок",
                "Уход": "Стирка 30°"
              }
            }
            """;

    private static final String SHOP_JEANS_JSON = """
            {
              "productName": "Джинсы Levi's 501 Original",
              "brand": "Levi's", "sku": "501-0193-W32",
              "itemType": "CLOTHING",
              "price": 24990, "currency": "₸",
              "forSale": true,
              "store": "IDEAQR Store · ТРЦ Керуен",
              "rating": 4.8, "reviews": 689,
              "description": "Классические прямые джинсы, тёмно-синие, посадка Original Fit.",
              "details": {
                "Размер": "W32 L32",
                "Материал": "99% хлопок · 1% эластан",
                "Цвет": "Тёмно-синий"
              }
            }
            """;

    private static final String SHOP_SNEAKERS_JSON = """
            {
              "productName": "Кроссовки New Balance 574",
              "brand": "New Balance", "sku": "ML574EVG-42",
              "itemType": "FOOTWEAR",
              "price": 39990, "currency": "₸",
              "forSale": true,
              "store": "IDEAQR Store · ТРЦ Керуен",
              "rating": 4.7, "reviews": 502,
              "description": "Классические кроссовки, замша и сетка, цвет серый.",
              "details": {
                "Размер": "42",
                "Материал": "Замша · текстиль",
                "Цвет": "Серый"
              }
            }
            """;

    /**
     * Seed the demo "digital business card" identity with a STABLE UUID (documented in the
     * README) so scanning {@code IDENTITY:<uuid>} demonstrates the P2P Owner Approval Flow.
     * Public tenant, so it resolves for citizen / guest scanners. Trust is the SINGLE
     * {@code trustLevel} metric — the retired gamified trustScore is not seeded.
     */
    private void seedBusinessCard() {
        if (userRepository.existsByUsername("aidos")) {
            return;
        }
        Identity identity = identityRepository.save(Identity.builder()
                .identityUid(BUSINESS_CARD_UID)
                .identityType(IdentityType.PRIMARY)
                .status(IdentityStatus.ACTIVE)
                .roles(new LinkedHashSet<>(Set.of(RoleType.CITIZEN, RoleType.SELLER)))
                .trustLevel(IdentityService.TRUST_VERIFIED)
                .riskScore("NORMAL")
                .build());
        Qr qr = qrService.createPrimaryQr(identity);
        identity.setPrimaryQrUid(qr.getQrUid());
        identityRepository.save(identity);

        userRepository.save(User.builder()
                .username("aidos")
                .passwordHash(passwordEncoder.encode("Aidos123!"))
                .firstName("Айдос")
                .lastName("Серіков")
                .employmentStatus(EmploymentStatus.EMPLOYED)
                .profession(UserService.PROFESSION_SELLER)
                .admin(false)
                .identity(identity)
                .build());
        log.info("DataSeeder: seeded digital business card identity {}.", BUSINESS_CARD_UID);
    }
}
