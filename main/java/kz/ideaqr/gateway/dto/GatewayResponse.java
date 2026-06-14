package kz.ideaqr.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * DTO ответа шлюза.
 * mockData содержит моковые данные из «внешней» системы (только при GRANTED).
 * Реальные чувствительные данные сюда НИКОГДА не попадают — платформа их не хранит.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayResponse {

    /** GRANTED | DENIED */
    private String status;

    /** Сообщение для пользователя */
    private String message;

    /** Роль субъекта (для отображения) */
    private String role;

    /** ID субъекта */
    private String subjectId;

    /** ID объекта */
    private String objectId;

    /** Сектор */
    private String sectorType;

    /** Моковые данные из внешней системы (только при GRANTED) */
    private Map<String, Object> mockData;

    /** ID записи в журнале аудита */
    private Long auditId;

    /** Атомарная временная метка завершённого события */
    private LocalDateTime timestamp;
}
