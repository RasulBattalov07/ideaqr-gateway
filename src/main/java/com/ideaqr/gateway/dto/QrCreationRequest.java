package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Admin panel payload describing the object to mint a governed QR for. Retail is
 * the primary flow (the brief's "Adidas Black T-Shirt" example), but the same
 * form serves medical, eco and general objects.
 */
@Data
public class QrCreationRequest {

    /** RETAIL, MEDICAL, ECO or GENERAL. */
    @NotBlank(message = "Выберите тип объекта")
    private String category;

    @NotBlank(message = "Укажите наименование объекта")
    private String displayName;

    private String description;

    // --- Retail-oriented fields -------------------------------------------
    private String brand;
    private Long price;
    private String currency;
    private List<SizeStock> sizes;
    private String discountCode;
    private String discountNote;
    private List<Alternative> alternatives;

    // --- Eco / infrastructure-oriented fields -----------------------------
    private String location;

    // --- Escape hatch for any extra structured attributes -----------------
    private Map<String, Object> extra;

    /** A clothing size and its live stock count. */
    @Data
    public static class SizeStock {
        private String size;
        private Integer stock;
    }

    /** An alternative place to buy the same item. */
    @Data
    public static class Alternative {
        private String store;
        private Long price;
        private String url;
        private String note;
    }
}
