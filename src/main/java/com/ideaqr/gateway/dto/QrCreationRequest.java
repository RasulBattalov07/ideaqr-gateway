package com.ideaqr.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Admin panel payload for minting a governed object + QR. {@code category} and
 * {@code displayName} are always required; the remaining fields are category
 * specific (retail commerce fields, or a location for eco / infrastructure) and
 * are folded into the object's stored JSON card payload.
 */
@Data
public class QrCreationRequest {

    @NotBlank(message = "Укажите тип объекта")
    private String category;

    @NotBlank(message = "Укажите наименование объекта")
    private String displayName;

    private String description;

    // --- Retail-specific ---------------------------------------------------
    private String brand;
    private Integer price;
    private String currency;
    private String discountCode;
    private String discountNote;
    private List<SizeDto> sizes;
    private List<AlternativeDto> alternatives;

    // --- Eco / infrastructure ---------------------------------------------
    private String location;

    /** A retail size and its current stock. */
    @Data
    public static class SizeDto {
        private String size;
        private Integer stock;
    }

    /** A cheaper alternative offer for a retail product. */
    @Data
    public static class AlternativeDto {
        private String store;
        private Integer price;
        private String url;
        private String note;
    }
}
