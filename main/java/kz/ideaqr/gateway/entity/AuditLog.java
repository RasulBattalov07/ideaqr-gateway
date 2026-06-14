package kz.ideaqr.gateway.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Неизменяемая запись каждого обращения через шлюз.
 * Хранит ТОЛЬКО метаданные: кто, к чему, когда, с каким результатом.
 * Содержательные данные (медкарты, финансы, личная информация) НЕ хранятся.
 */
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_subject", columnList = "subjectId"),
    @Index(name = "idx_status",  columnList = "accessStatus"),
    @Index(name = "idx_ts",      columnList = "timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Идентификатор субъекта (пользователь, ключ e-GOV, токен) */
    @Column(nullable = false)
    private String subjectId;

    /** Идентификатор объекта (QR-код, NFC-метка, ID двери / медкарты / товара) */
    @Column(nullable = false)
    private String objectId;

    /** Роль субъекта: DOCTOR | ENGINEER | CITIZEN */
    @Column(nullable = false)
    private String role;

    /** Результат: GRANTED | DENIED */
    @Column(nullable = false)
    private String accessStatus;

    /** Сектор обращения: MEDICAL | INFRASTRUCTURE | RETAIL | FINANCE | GENERAL */
    @Column(nullable = false)
    private String sectorType;

    /** Краткое описание причины решения (для аудита) */
    @Column(length = 512)
    private String details;

    /** Атомарная временная метка события */
    @Column(nullable = false)
    private LocalDateTime timestamp;

    @PrePersist
    protected void prePersist() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
