package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.domain.enums.EventType;
import com.ideaqr.gateway.domain.enums.IdentityType;
import com.ideaqr.gateway.repository.ComplaintRepository;
import com.ideaqr.gateway.repository.EventRepository;
import com.ideaqr.gateway.repository.IdentityRepository;
import com.ideaqr.gateway.repository.InteractionRepository;
import com.ideaqr.gateway.repository.QrRepository;
import com.ideaqr.gateway.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregates the platform-wide statistics and analytics shown only in the
 * administrator panel (the user side sees just its own history). All figures are
 * derived live from the append-only stores, so they are always consistent with
 * the journal.
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final UserRepository userRepository;
    private final IdentityRepository identityRepository;
    private final QrRepository qrRepository;
    private final InteractionRepository interactionRepository;
    private final ComplaintRepository complaintRepository;
    private final EventRepository eventRepository;

    /** STATISTICS: users, guests, QR codes, scans, interactions, complaints. */
    public Map<String, Object> statistics() {
        long scans = interactionRepository.countByInteractionType("SCAN")
                + interactionRepository.countByInteractionType("PROFILE_SCAN");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", identityRepository.countByIdentityType(IdentityType.PRIMARY));
        m.put("guests", identityRepository.countByIdentityType(IdentityType.GUEST));
        m.put("qrCodes", qrRepository.count());
        m.put("scans", scans);
        m.put("interactions", interactionRepository.count());
        m.put("complaints", complaintRepository.count());
        return m;
    }

    /** ANALYTICS: growth, registrations, popular profiles, views, interactions, guest conversion. */
    public Map<String, Object> analytics() {
        long registered = identityRepository.countByIdentityType(IdentityType.PRIMARY);
        long guests = identityRepository.countByIdentityType(IdentityType.GUEST);
        long conversions = eventRepository.countByEventType(EventType.GUEST_MERGED);
        long conversionBase = guests + conversions;
        int conversionRate = conversionBase == 0 ? 0 : (int) Math.round(100.0 * conversions / conversionBase);

        // Popular profiles — distribution of professions across registered accounts.
        Map<String, Long> byProfession = new TreeMap<>();
        for (User u : userRepository.findAll()) {
            byProfession.merge(u.getProfession() != null ? u.getProfession() : "CITIZEN", 1L, Long::sum);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registeredUsers", registered);
        m.put("guestIdentities", guests);
        m.put("guestConversions", conversions);
        m.put("guestConversionRate", conversionRate);
        m.put("profileViews", eventRepository.countByEventType(EventType.QR_VIEWED)
                + eventRepository.countByEventType(EventType.PROFILE_OPENED));
        m.put("accessRequests", eventRepository.countByEventType(EventType.ACCESS_REQUESTED));
        m.put("accessConfirmed", eventRepository.countByEventType(EventType.ACCESS_CONFIRMED));
        m.put("totalInteractions", interactionRepository.count());
        m.put("professionDistribution", byProfession);
        return m;
    }
}
