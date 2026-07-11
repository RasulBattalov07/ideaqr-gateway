package com.ideaqr.gateway.repository;

import com.ideaqr.gateway.domain.Interaction;
import com.ideaqr.gateway.domain.enums.InteractionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Typed interactions of one identity (e.g. household SERVICE_ORDER rows), newest first. */
    List<Interaction> findByIdentityUidAndInteractionTypeOrderByCreatedAtDesc(UUID identityUid, String interactionType);

    /** «Мои наряды» исполнителя: типизированные interaction'ы, адресованные ему как второй стороне. */
    List<Interaction> findByTargetIdentityUidAndInteractionTypeOrderByCreatedAtDesc(UUID targetIdentityUid, String interactionType);

    /**
     * Executor queue: every interaction of a given type platform-wide, newest first.
     * Interaction carries no tenant column, so an operator from a service-company tenant
     * legitimately sees citizens' household orders; the service layer gates by role.
     */
    List<Interaction> findByInteractionTypeOrderByCreatedAtDesc(String interactionType);

    /** Interactions tied to a request (used to surface the SOS message in the admin alert queue). */
    List<Interaction> findByRequestUid(UUID requestUid);

    /**
     * Pending access/consent requests addressed TO an owner. Native on purpose: it bypasses the
     * tenant {@code @Filter} so a request from a specialist in another tenant (e.g. a hospital
     * doctor requesting a public-tenant patient's medical card) is still visible to its target.
     * The target is the authenticated caller themselves, so this is correct, not a cross-tenant leak.
     */
    @Query(value = "select * from interactions where target_identity_uid = :uid and status = 'PENDING' "
            + "order by created_at desc", nativeQuery = true)
    List<Interaction> findPendingRequestsForTarget(@Param("uid") UUID uid);

    long countByIdentityUid(UUID identityUid);

    long countByIdentityUidAndStatus(UUID identityUid, InteractionStatus status);

    long countByInteractionType(String interactionType);

    /** Interactions referencing a given object — feeds the object's Trust Score. */
    long countByObjectUid(String objectUid);
}
