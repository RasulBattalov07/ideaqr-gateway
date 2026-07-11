package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.service.ServiceOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Модуль «УСЛУГИ И БЫТ» — трёхсторонний флоу (заказчик × оператор × исполнитель):
 * каталог, оформление заявки, диспетчерская оператора (назначение исполнителя),
 * наряды исполнителя и подтверждения заказчика (приход / выполнено-и-оплачено),
 * инициируемые сканом личного QR исполнителя (см. {@link ServiceOrderService}).
 */
@RestController
@RequestMapping("/api/v2/services")
@RequiredArgsConstructor
public class HouseholdServiceController {

    private final ServiceOrderService serviceOrderService;
    private final AuthSupport authSupport;

    @GetMapping("/catalog")
    public ResponseEntity<List<Map<String, Object>>> catalog(Authentication authentication) {
        authSupport.requireIdentity(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServiceOrderService.CatalogItem c : ServiceOrderService.CATALOG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key());
            m.put("icon", c.icon());
            m.put("label", c.label());
            m.put("description", c.description());
            m.put("operator", c.operator());
            m.put("price", c.price());
            m.put("eta", c.eta());
            rows.add(m);
        }
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> order(@RequestBody Map<String, String> body,
                                                     Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.order(identity,
                body != null ? body.get("service") : null,
                body != null ? body.get("note") : null));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<Map<String, Object>>> mine(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.mine(identity));
    }

    /** Диспетчерская оператора (роль SERVICE_OPERATOR): все заявки платформы, новые сверху. */
    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> queue(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.queue(identity));
    }

    /** «Мои наряды» исполнителя (роль EXECUTOR): назначенные на него заявки. */
    @GetMapping("/assigned")
    public ResponseEntity<List<Map<String, Object>>> assigned(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.assigned(identity));
    }

    /** Исполнители организации оператора — для выпадающего списка «Назначить исполнителя». */
    @GetMapping("/executors")
    public ResponseEntity<List<Map<String, Object>>> executors(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.executors(identity));
    }

    /** Оператор назначает исполнителя (username или UID личности в {@code executor}). */
    @PostMapping("/{orderUid}/assign")
    public ResponseEntity<Map<String, Object>> assign(@PathVariable("orderUid") String orderUid,
                                                      @RequestBody Map<String, String> body,
                                                      Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.assign(identity, UUID.fromString(orderUid),
                body != null ? body.get("executor") : null));
    }

    /** Заказчик подтверждает приход исполнителя (после сверки личности сканом его QR). */
    @PostMapping("/{orderUid}/arrival")
    public ResponseEntity<Map<String, Object>> arrival(@PathVariable("orderUid") String orderUid,
                                                       @RequestBody(required = false) Map<String, String> body,
                                                       Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.confirmArrival(identity, UUID.fromString(orderUid),
                parseUid(body != null ? body.get("executorUid") : null)));
    }

    /** Заказчик вторым сканом подтверждает выполнение и оплачивает (демо-платёж) — финал. */
    @PostMapping("/{orderUid}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable("orderUid") String orderUid,
                                                        @RequestBody(required = false) Map<String, String> body,
                                                        Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.completeAndPay(identity, UUID.fromString(orderUid),
                parseUid(body != null ? body.get("executorUid") : null)));
    }

    private UUID parseUid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
