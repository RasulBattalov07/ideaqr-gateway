package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Complaint;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.enums.ComplaintStatus;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.repository.ComplaintRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Handles complaints ("жалобы"). Every complaint must reference an existing
 * {@link Interaction} (the brief's requirement) and goes through the platform's
 * journal + event log like any other action.
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final InteractionRepository interactionRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final NotificationService notificationService;

    @Transactional
    public Complaint create(Identity author, UUID interactionUid, String subject, String category, String description) {
        if (interactionUid == null) {
            throw new IllegalArgumentException("Жалоба должна быть привязана к взаимодействию.");
        }
        Interaction interaction = interactionRepository.findById(interactionUid)
                .orElseThrow(() -> new IllegalArgumentException("Взаимодействие для жалобы не найдено."));
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Укажите тему жалобы.");
        }

        Complaint complaint = complaintRepository.save(Complaint.builder()
                .identityUid(author.getIdentityUid())
                .interactionUid(interaction.getInteractionUid())
                .subject(subject.trim())
                .category(category != null && !category.isBlank() ? category.trim() : "ОБЩАЯ")
                .description(description != null ? description.trim() : null)
                .status(ComplaintStatus.NEW)
                .build());

        auditService.record(author.getIdentityUid(), interaction.getObjectUid(),
                HistoryEventType.COMPLAINT_CREATED, "Подана жалоба: " + complaint.getSubject());
        eventService.record(EventType.COMPLAINT_CREATED, author.getIdentityUid(),
                interaction.getObjectUid(), interaction.getInteractionUid(),
                "Жалоба «" + complaint.getSubject() + "»");
        notificationService.notify(author.getIdentityUid(), "COMPLAINT",
                "Жалоба зарегистрирована: " + complaint.getSubject());
        return complaint;
    }

    public List<Complaint> mine(UUID identityUid) {
        return complaintRepository.findByIdentityUidOrderByCreatedAtDesc(identityUid);
    }

    public List<Complaint> all() {
        return complaintRepository.findAllByOrderByCreatedAtDesc();
    }

    /** Administrator updates the status of a complaint. */
    @Transactional
    public Complaint updateStatus(UUID complaintUid, ComplaintStatus status) {
        Complaint complaint = complaintRepository.findById(complaintUid)
                .orElseThrow(() -> new IllegalArgumentException("Жалоба не найдена."));
        complaint.setStatus(status);
        complaint = complaintRepository.save(complaint);
        notificationService.notify(complaint.getIdentityUid(), "COMPLAINT",
                "Статус вашей жалобы обновлён: " + status.name());
        return complaint;
    }
}
