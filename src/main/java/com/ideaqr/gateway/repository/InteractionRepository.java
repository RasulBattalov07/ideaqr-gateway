package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InteractionRepository extends JpaRepository<Interaction, UUID> {

    /** Used by the guest-merge flow to re-point a guest identity's interactions. */
    List<Interaction> findByIdentityUid(UUID identityUid);

    /** "Я сканировал" — interactions initiated by this identity, newest first. */
    List<Interaction> findByIdentityUidOrderByCreatedAtDesc(UUID identityUid);

    /** "Кто сканировал меня" — interactions where this identity's QR was the target. */
    List<Interaction> findByTargetIdentityUidOrderByCreatedAtDesc(UUID targetIdentityUid);

    /** Alias-aware "я сканировал": this identity plus any merged guest identities. */
    List<Interaction> findByIdentityUidInOrderByCreatedAtDesc(java.util.Collection<UUID> identityUids);

    /** Alias-aware "кто сканировал меня". */
    List<Interaction> findByTargetIdentityUidInOrderByCreatedAtDesc(java.util.Collection<UUID> targetIdentityUids);

    /** Prescriptions (and other typed interactions) attached to an object, newest first. */
    List<Interaction> findByObjectUidAndInteractionTypeOrderByCreatedAtDesc(String objectUid, String interactionType);

    long countByIdentityUid(UUID identityUid);

    long countByIdentityUidAndStatus(UUID identityUid, InteractionStatus status);

    long countByInteractionType(String interactionType);

    /** Interactions referencing a given object — feeds the object's Trust Score. */
    long countByObjectUid(String objectUid);
}
