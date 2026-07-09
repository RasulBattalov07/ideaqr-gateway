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
import com.ideaqr.gateway.service.ModuleService;
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
 * Seeds demo organizations, the demo accounts + memberships, the showcase Toyota Camry
 * (as a real, transferable DB object) and a fixed-UUID "digital business card" identity
 * for the P2P / Trust Score demo. Idempotent. Most quick-access reference objects are
 * served from {@link com.ideaqr.gateway.service.RegistryClient}; only the car needs a
 * DB row so the ownership-transfer demo works. Credentials are throwaway demo values
 * documented in the README.
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
    private final ModuleService moduleService;
    private final PasswordEncoder passwordEncoder;
    private final com.ideaqr.gateway.service.CitizenDossierService citizenDossierService;
    private final com.ideaqr.gateway.service.MedicalService medicalService;

    @Override
    public void run(String... args) {
        // Base MVP modules (spheres of interaction shown in the admin panel).
        moduleService.ensureModule("USERS", "Пользователи", "Цифровые личности и их основные QR.");
        moduleService.ensureModule("SERVICES", "Услуги и быт", "Бытовые услуги по заявке (Request → Decision → Interaction).");
        moduleService.ensureModule("GOODS", "Товары", "Карточки товаров, происхождение, цены и отзывы.");
        moduleService.ensureModule("MEDICINE", "Медицина", "Медицинские услуги и доступ к карте по разрешению.");
        moduleService.ensureModule("INFRASTRUCTURE", "Инфраструктура", "Объекты инфраструктуры и доступ по политикам.");
        moduleService.ensureModule("EDUCATION", "Образование", "Документы и студенческие билеты (интеграция с вузами).");

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
        // Двусторонний флоу «Услуги и быт» (P0): исполнитель, разбирающий очередь бытовых заявок.
        seed("operator", "Operator123!", "Багдат", "Жумабаев", "EMPLOYED",
                UserService.PROFESSION_SERVICE_OPERATOR, comfort, "SERVICE_OPERATOR");
        User citizen = seed("citizen", "Citizen123!", "Дамир", "Оспанов", "UNEMPLOYED",
                UserService.PROFESSION_CITIZEN, null, null);

        // Showcase objects: the Toyota Camry as a real DB object (for the transfer demo),
        // and the digital business card identity (for the P2P / Trust Score demo).
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

        // Phase 2 (единый QR): каждому демо-гражданину — полный цифровой пакет (медкарта,
        // правовое досье, визитка). Выполняется вне тенант-контекста, объекты — публичные.
        seedDossiers();
        // Стартовый рецепт на медкарте гражданина: фармацевт может демонстрировать выдачу
        // сразу, без предварительного шага врача.
        if (citizen != null) {
            seedDemoPrescription(citizen);
        }
    }

    /** Идемпотентно доукомплектовывает досье всем демо-аккаунтам (включая «Айдоса»). */
    private void seedDossiers() {
        for (String username : new String[]{"admin", "seller", "doctor", "pharmacist",
                "inspector", "police", "operator", "citizen", "aidos"}) {
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
            organizationService.ensureMembership(user.getIdentityUid(), org.getOrganizationUid(), workRole);
        }
        return user;
    }

    /**
     * Mint the Toyota Camry as a real {@link RegistryObject} so the admin can demonstrate
     * ownership transfer (which operates on DB objects). Public tenant, so it resolves for
     * every scanner; the same card is also in the demo registry as a universal fallback.
     */
    private void seedCarObject(UUID ownerIdentityUid) {
        if (registryObjectRepository.findByObjectUid("CAR_TOYOTA_CAMRY").isPresent()) {
            return;
        }
        // Empty context → stamped with the public tenant.
        registryObjectRepository.save(RegistryObject.builder()
                .objectUid("CAR_TOYOTA_CAMRY")
                .category(ObjectCategory.RETAIL)
                .displayName("Toyota Camry 2024")
                .dataJson(com.ideaqr.gateway.service.RegistryClient.CAR_TOYOTA_CAMRY)
                .createdByIdentityUid(ownerIdentityUid)
                .ownerIdentityUid(ownerIdentityUid)
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

    /**
     * Seed the demo "digital business card" identity with a STABLE UUID (documented in the
     * README) so scanning {@code IDENTITY:<uuid>} demonstrates the P2P Owner Approval Flow
     * and Trust Score. Public tenant, so it resolves for citizen / guest scanners.
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
                .trustScore(78)
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
