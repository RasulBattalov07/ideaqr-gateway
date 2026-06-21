package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.HistoryEventType;
import com.ideaqr.gateway.domain.enums.IdentityStatus;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.repository.HistoryRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.util.Hashing;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implements the guest → merge flow as an <b>append-only alias</b> (audit 4.5 / 4.6).
 *
 * <p>The old design rewrote the guest's journal rows (a direct violation of the
 * immutability guarantee) and accepted any guest UID (an IDOR — anyone who learned a
 * guest's UUID could steal its history). The new design:</p>
 * <ul>
 *   <li>requires the one-time <b>merge token</b> issued to the guest's browser at
 *       creation, proving the caller owns that guest session;</li>
 *   <li>records the guest UID as a soft alias on the target identity instead of
 *       mutating any history — read paths union over the alias.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GuestService {

    private final IdentityRepository identityRepository;
    private final HistoryRepository historyRepository;
    private final AuditService auditService;
    private final EventService eventService;
    private final NotificationService notificationService;

    @Transactional
    public int merge(Identity target, UUID guestIdentityUid, String mergeToken) {
        if (guestIdentityUid.equals(target.getIdentityUid())) {
            throw new IllegalArgumentException("Нельзя объединить личность с самой собой.");
        }
        Identity guest = identityRepository.findById(guestIdentityUid)
                .orElseThrow(() -> new IllegalArgumentException("Гостевая личность не найдена."));
        if (guest.getIdentityType() != IdentityType.GUEST) {
            throw new IllegalArgumentException("Указанная личность не является гостевой.");
        }

        // Ownership proof (audit 4.6): the caller must present the one-time token issued
        // to this guest's browser. Knowing the guest UID is not sufficient.
        String expected = guest.getMergeTokenHash();
        if (mergeToken == null || expected == null
                || !Hashing.constantTimeEquals(expected, Hashing.sha256Hex(mergeToken))) {
            throw new AccessDeniedException("Недостаточно прав для объединения этой гостевой личности.");
        }

        // Append-only alias instead of rewriting the guest's journal (audit 4.5).
        long movedHistory = historyRepository.findByIdentityUid(guestIdentityUid).size();
        target.getLinkedGuestUids().add(guestIdentityUid);
        identityRepository.save(target);

        // Retire the guest identity and burn the token (single use).
        guest.setStatus(IdentityStatus.SUSPENDED);
        guest.setMergeTokenHash(null);
        identityRepository.save(guest);

        auditService.record(target.getIdentityUid(), null, HistoryEventType.GUEST_MERGED,
                "История гостевой личности связана с основным профилем. Событий перенесено: " + movedHistory + ".");
        eventService.record(EventType.GUEST_MERGED, target.getIdentityUid(),
                "Объединение истории гостя (" + movedHistory + " событий).");
        notificationService.notify(target.getIdentityUid(), "GUEST_MERGE",
                "История гостевого доступа перенесена в ваш профиль.");
        return (int) movedHistory;
    }
}
