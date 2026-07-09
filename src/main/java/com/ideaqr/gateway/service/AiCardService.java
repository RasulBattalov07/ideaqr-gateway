package com.ideaqr.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.domain.enums.ObjectCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Интеллектуальная AI-карточка объекта (BLOCK 3 — AI Context). После определения
 * пользователя любой QR вещи становится «умной» цифровой карточкой: модуль понимает,
 * КТО сканирует (Identity + его «гардероб» — вещи, которыми он владеет на платформе)
 * и ЧТО за объект перед ним ({@code itemType} в payload или эвристика по названию),
 * и генерирует персональные рекомендации.
 *
 * <p>Investor-stage МОК: правило-ориентированный детерминированный генератор без внешних
 * вызовов. Контракт {@link #generate} устойчив — реальная LLM-интеграция подменяет только
 * внутренности этого сервиса, не трогая конвейер и клиентов.</p>
 */
@Service
@RequiredArgsConstructor
public class AiCardService {

    /** Категории «вещей», к которым применимо универсальное правило объектов. */
    public static final Set<ObjectCategory> ITEM_CATEGORIES =
            Set.of(ObjectCategory.RETAIL, ObjectCategory.ECO, ObjectCategory.GENERAL);

    private final com.ideaqr.gateway.repository.RegistryObjectRepository registryObjectRepository;
    private final ObjectMapper objectMapper;

    /**
     * Собрать AI-карточку для аутентифицированного сканирующего. {@code owner} переключает
     * фокус: владельцу — обслуживание и уход, покупателю/гостю платформы — подбор и сочетания.
     * Возвращает {@code null}, если категория не является «вещью» (для таких сканов AI-блок
     * не показывается вовсе).
     */
    public Map<String, Object> generate(Identity scanner, String objectUid, ObjectCategory category,
                                        Map<String, Object> data, boolean owner) {
        if (category == null || !ITEM_CATEGORIES.contains(category)) {
            return null;
        }
        String itemType = itemTypeOf(data);
        String name = displayNameOf(data, objectUid);
        List<Map<String, Object>> wardrobe = wardrobeOf(scanner.getIdentityUid(), objectUid);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("engine", "IDEA QR AI · демо-модель");
        card.put("itemType", itemType);
        card.put("focus", owner
                ? "Режим владельца: уход, обслуживание и сохранение стоимости"
                : "Персональный подбор под ваш профиль и ваши вещи");
        switch (itemType) {
            case "CLOTHING" -> clothing(card, name, wardrobe);
            case "FOOTWEAR" -> footwear(card, name, wardrobe);
            case "VEHICLE" -> vehicle(card, name, data);
            case "APPLIANCE" -> appliance(card, name, data);
            case "DOCUMENT" -> document(card, name);
            default -> generic(card, name);
        }
        card.put("disclaimer", "Рекомендации сгенерированы AI-модулем платформы по типу объекта "
                + "и вашему профилю. Демо-режим: без внешних сервисов.");
        return card;
    }

    // ------------------------------------------------------------------
    //  Category playbooks (BLOCK 3 требования: одежда / обувь / авто / техника)
    // ------------------------------------------------------------------

    private void clothing(Map<String, Object> card, String name, List<Map<String, Object>> wardrobe) {
        card.put("headline", "С чем носить: " + name);
        card.put("items", List.of(
                tip("👖", "Сочетания", "Хорошо работает с прямыми джинсами, чиносами и однотонными брюками; "
                        + "верхний слой — базовые оттенки (графит, беж, тёмно-синий)."),
                tip("👟", "Обувь", "Белые кожаные кроссовки или челси — универсальная пара к этой вещи."),
                tip("🧢", "Аксессуары", "Минималистичная шапка-бини, кожаный ремень в тон обуви, рюкзак-ролл-топ."),
                tip("🧺", "Уход", "Деликатная стирка 30°, сушка в расправленном виде; пух — с шариками для стирки."),
                tip("🛍", "Докупить", "Термо-лонгслив как базовый слой и водоотталкивающая пропитка для сезона дождей.")
        ));
        card.put("pairings", pairingsFrom(wardrobe, Set.of("CLOTHING", "FOOTWEAR")));
    }

    private void footwear(Map<String, Object> card, String name, List<Map<String, Object>> wardrobe) {
        card.put("headline", "Образ вокруг пары: " + name);
        card.put("items", List.of(
                tip("🧥", "Одежда", "Прямые или зауженные джинсы, свободные худи и лёгкие куртки-бомберы; "
                        + "для классической белой пары — монохромные образы."),
                tip("🧴", "Уход", "Пропитка от влаги раз в 2 недели, пена-очиститель для кожи, кедровые колодки."),
                tip("🍂", "Сезон", "Осенью добавьте водостойкие носки; зимой пара «на выход», не на каждый день."),
                tip("🛍", "Докупить", "Запасные шнурки (белые/контрастные) и носки-невидимки под низкий крой.")
        ));
        card.put("pairings", pairingsFrom(wardrobe, Set.of("CLOTHING", "FOOTWEAR")));
    }

    private void vehicle(Map<String, Object> card, String name, Map<String, Object> data) {
        card.put("headline", "Обслуживание и владение: " + name);
        card.put("items", List.of(
                tip("🔧", "Ближайшие сервисы", "Toyota Astana Motors (2,1 км, офиц. дилер) · AutoService KZ (3,4 км) "
                        + "· Bosch Service (5,0 км). Запись — онлайн."),
                tip("🛞", "Расходники и запчасти", "Масло 0W-20 (5,0 л), фильтр масляный 04152-YZZA6, "
                        + "щётки стеклоочистителя 26\"/18\", шины 235/45 R18 по сезону."),
                tip("🛡", "Страховка", "ОГПО истекает в этом квартале — продление онлайн ~14 500 ₸/год; "
                        + "КАСКО для этой модели от 0,9% стоимости."),
                tip("📅", "Регламент", "ТО каждые 10 000 км или 12 месяцев; ближайшее — замена масла и фильтров.")
        ));
    }

    private void appliance(Map<String, Object> card, String name, Map<String, Object> data) {
        Object warranty = data != null ? data.get("warrantyUntil") : null;
        Object filters = data != null ? data.get("filters") : null;
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(tip("🧊", "Аксессуары и фильтры", filters instanceof List<?> list && !list.isEmpty()
                ? "Совместимые расходники: " + joinList(list) + ". Замена фильтра воды — раз в 6 месяцев."
                : "Подберите оригинальные фильтры и аксессуары по серийному номеру устройства."));
        items.add(tip("🛡", "Гарантия", warranty != null
                ? "Гарантия активна до " + warranty + ". Сохраняйте цифровой чек в карточке объекта."
                : "Статус гарантии уточняется по серийному номеру у производителя."));
        items.add(tip("📖", "Инструкция", "Полное руководство пользователя и коды ошибок — в цифровой карточке "
                + "(раздел «Документы объекта»)."));
        items.add(tip("🛠", "Сервисные центры", "Samsung Care Астана: ул. Достык 5 (1,8 км) · ТРЦ Хан Шатыр (4,2 км). "
                + "Выезд мастера — по заявке через «Услуги и быт»."));
        card.put("headline", "Умная карточка техники: " + name);
        card.put("items", items);
    }

    private void document(Map<String, Object> card, String name) {
        card.put("headline", "Цифровой документ: " + name);
        card.put("items", List.of(
                tip("✅", "Подлинность", "Документ подтверждён реестром платформы; QR неизменяем, история — в журнале."),
                tip("🔔", "Напоминания", "Продление/срок действия можно отслеживать: уведомление придёт за 30 дней."),
                tip("🔒", "Приватность", "Показывайте документ по QR — получатель увидит только разрешённый срез.")
        ));
    }

    private void generic(Map<String, Object> card, String name) {
        card.put("headline", "Цифровая карточка объекта: " + name);
        card.put("items", List.of(
                tip("📋", "Паспорт объекта", "Характеристики, происхождение и история переходов — в неизменяемом журнале."),
                tip("🛡", "Подлинность", "QR неизменяем и привязан к реестру: подделка карточки невозможна."),
                tip("♻️", "Вторичный рынок", "Передача владения фиксируется конвейером Request → Decision → History — "
                        + "покупатель видит всю цепочку владельцев.")
        ));
    }

    // ------------------------------------------------------------------
    //  Wardrobe: вещи сканирующего на платформе → персональные сочетания
    // ------------------------------------------------------------------

    /** «Гардероб» пользователя: его вещи (без объектов-досье и без самого сканируемого). */
    private List<Map<String, Object>> wardrobeOf(UUID identityUid, String excludeObjectUid) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegistryObject o : registryObjectRepository.findOwnedAnyTenant(identityUid)) {
            if (o.getObjectUid().equalsIgnoreCase(excludeObjectUid)
                    || CitizenDossierService.isDossierObject(o.getObjectUid())
                    || !ITEM_CATEGORIES.contains(o.getCategory())) {
                continue;
            }
            Map<String, Object> data = parse(o.getDataJson());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", displayNameOf(data, o.getDisplayName()));
            row.put("itemType", itemTypeOf(data));
            out.add(row);
        }
        return out;
    }

    private List<String> pairingsFrom(List<Map<String, Object>> wardrobe, Set<String> matchingTypes) {
        List<String> pairings = new ArrayList<>();
        for (Map<String, Object> item : wardrobe) {
            if (matchingTypes.contains(String.valueOf(item.get("itemType")))) {
                pairings.add("Сочетается с вашей вещью: «" + item.get("name") + "»");
            }
        }
        if (pairings.isEmpty()) {
            pairings.add("В вашем цифровом гардеробе пока нет вещей для подбора — "
                    + "оформленные во владение вещи появятся здесь автоматически.");
        }
        return pairings;
    }

    // ------------------------------------------------------------------

    /** Тип вещи: явный {@code itemType} из payload, иначе эвристика по названию/описанию. */
    static String itemTypeOf(Map<String, Object> data) {
        if (data == null) {
            return "GENERIC";
        }
        Object explicit = data.get("itemType");
        if (explicit != null && !explicit.toString().isBlank()) {
            return explicit.toString().trim().toUpperCase(Locale.ROOT);
        }
        String text = (str(data.get("productName")) + " " + str(data.get("title")) + " "
                + str(data.get("description")) + " " + str(data.get("assetType"))).toLowerCase(Locale.ROOT);
        if (containsAny(text, "кроссовк", "обувь", "ботин", "туфл", "кед", "sneaker", "shoe")) return "FOOTWEAR";
        if (containsAny(text, "куртк", "пальто", "футболк", "джинс", "плать", "худи", "рубаш", "одежд", "пуховик")) return "CLOTHING";
        if (containsAny(text, "автомоб", "седан", "внедорож", "toyota", "camry", "машин", " авто")) return "VEHICLE";
        if (containsAny(text, "холодильн", "стиральн", "телевизор", "пылесос", "техник", "кондиционер")) return "APPLIANCE";
        if (containsAny(text, "документ", "билет", "справк", "удостоверен")) return "DOCUMENT";
        return "GENERIC";
    }

    private static String displayNameOf(Map<String, Object> data, String fallback) {
        if (data != null) {
            for (String key : new String[]{"productName", "title", "displayName", "name"}) {
                Object v = data.get(key);
                if (v != null && !v.toString().isBlank()) {
                    return v.toString();
                }
            }
        }
        return fallback;
    }

    private static Map<String, Object> tip(String ico, String title, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ico", ico);
        m.put("title", title);
        m.put("note", note);
        return m;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String joinList(List<?> list) {
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o);
        }
        return sb.toString();
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
