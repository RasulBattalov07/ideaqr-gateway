package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.dto.ApiResponse;
import com.ideaqr.gateway.dto.GatewayResponse;
import com.ideaqr.gateway.service.GatewayService;
import com.ideaqr.gateway.service.ObjectLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * УНИВЕРСАЛЬНОЕ ПРАВИЛО ОБЪЕКТОВ — самообслуживание владения и P2P-согласие.
 *
 * <ul>
 *   <li><b>owner-request</b> — кнопка «Профиль владельца» на карточке вещи: создаёт
 *       Request в REVIEW, владелец решает, сканирующий поллит
 *       {@code /api/v2/access/{interactionUid}/result};</li>
 *   <li><b>claim</b> — pre-ownership → покупка/привязка вещи к Identity (QR не меняется);</li>
 *   <li><b>transfer</b> — передача права владения текущим владельцем (продажа/дарение).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v2/objects")
@RequiredArgsConstructor
public class ObjectOwnershipController {

    private final GatewayService gatewayService;
    private final ObjectLifecycleService objectLifecycleService;
    private final AuthSupport authSupport;

    /** BLOCK 2.2 — запрос доступа к профилю владельца объекта (Owner-Approval). */
    @PostMapping("/{objectUid}/owner-request")
    public ResponseEntity<GatewayResponse> ownerRequest(@PathVariable("objectUid") String objectUid,
                                                        Authentication authentication) {
        Identity requester = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(gatewayService.requestOwnerProfile(requester, objectUid));
    }

    /** BLOCK 1 — оформление владения бесхозной вещью (демо-покупка). */
    @PostMapping("/{objectUid}/claim")
    public ResponseEntity<ApiResponse> claim(@PathVariable("objectUid") String objectUid,
                                             Authentication authentication) {
        Identity actor = authSupport.requireIdentity(authentication);
        RegistryObject object = objectLifecycleService.claim(actor, objectUid);
        return ResponseEntity.ok(ApiResponse.ok(
                        "Право владения оформлено: «" + object.getDisplayName()
                                + "». QR объекта не изменился — изменился только владелец.")
                .with("objectUid", object.getObjectUid()));
    }

    /** BLOCK 1 — передача права владения текущим владельцем. */
    @PostMapping("/{objectUid}/transfer")
    public ResponseEntity<ApiResponse> transfer(@PathVariable("objectUid") String objectUid,
                                                @RequestBody Map<String, String> body,
                                                Authentication authentication) {
        Identity actor = authSupport.requireIdentity(authentication);
        RegistryObject object = objectLifecycleService.transferByOwner(actor, objectUid,
                body != null ? body.get("newOwner") : null,
                body != null ? body.get("note") : null);
        return ResponseEntity.ok(ApiResponse.ok(
                        "Право владения на «" + object.getDisplayName()
                                + "» передано. История объекта полностью сохранена.")
                .with("objectUid", object.getObjectUid()));
    }
}
