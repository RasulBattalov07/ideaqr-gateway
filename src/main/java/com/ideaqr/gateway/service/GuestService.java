package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Decision;
import com.ideaqr.gateway.domain.Event;
import com.ideaqr.gateway.domain.History;
import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.RequestRecord;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.repository.DecisionRepository;
import com.ideaqr.gateway.repository.EventRepository;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implements the guest → merge flow. A guest identity's whole history is re-pointed
 * to a registered identity (the schema stores relationships as flat UUIDs, so this
 * needs no cascades and nothing is lost), then the guest identity is retired and
 * the merge itself is recorded.
 */
@Service
@RequiredArgsConstructor
public class GuestService {

    private final IdentityRepository identityRepository;
    private final HistoryRepository historyRepository;
    private final InteractionRepository interactionRepository;
    private final RequestRepository requestRepository;
    private final DecisionRepository decisionRepository;
    private final EventRepository eventRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final NotificationService notificationService;

    @Transactional
    public int merge(Identity target, UUID guestIdentityUid) {
        if (guestIdentityUid.equals(target.getIdentityUid())) {
            throw new IllegalArgumentException("Нельзя объединить личность с самой собой.");
        }
        Identity guest = identityRepository.findById(guestIdentityUid)
                .orElseThrow(() -> new IllegalArgumentException("Гостевая личность не найдена."));
        if (guest.getIdentityType() != IdentityType.GUEST) {
            throw new IllegalArgumentException("Указанная личность не является гостевой.");
        }

        int movedHistory = 0;
        for (History h : historyRepository.findByIdentityUid(guestIdentityUid)) {
            h.setIdentityUid(target.getIdentityUid());
            historyRepository.save(h);
            movedHistory++;
        }
        for (Interaction i : interactionRepository.findByIdentityUid(guestIdentityUid)) {
            i.setIdentityUid(target.getIdentityUid());
            interactionRepository.save(i);
        }
        // Re-point scans that targeted the guest's QR ("who scanned me").
        for (Interaction i : interactionRepository.findByTargetIdentityUidOrderByCreatedAtDesc(guestIdentityUid)) {
            i.setTargetIdentityUid(target.getIdentityUid());
            interactionRepository.save(i);
        }
        for (RequestRecord r : requestRepository.findByIdentityUid(guestIdentityUid)) {
            r.setIdentityUid(target.getIdentityUid());
            requestRepository.save(r);
        }
        for (Decision d : decisionRepository.findByIdentityUid(guestIdentityUid)) {
            d.setIdentityUid(target.getIdentityUid());
            decisionRepository.save(d);
        }
        for (Event e : eventRepository.findByIdentityUid(guestIdentityUid)) {
            e.setIdentityUid(target.getIdentityUid());
            eventRepository.save(e);
        }

        guest.setStatus(IdentityStatus.SUSPENDED);
        identityRepository.save(guest);

        auditService.record(target.getIdentityUid(), null, HistoryEventType.GUEST_MERGED,
                "История гостевой личности перенесена в основной профиль. Перенесено событий: " + movedHistory + ".");
        eventService.record(EventType.GUEST_MERGED, target.getIdentityUid(),
                "Объединение истории гостя (" + movedHistory + " событий).");
        notificationService.notify(target.getIdentityUid(), "GUEST_MERGE",
                "История гостевого доступа перенесена в ваш профиль.");
        return movedHistory;
    }
}
