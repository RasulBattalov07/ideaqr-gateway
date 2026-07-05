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
 * Модуль «УСЛУГИ И БЫТ» (Phase 2): каталог бытовых услуг, оформление заявки, мои заявки.
 * Заявка привязывается к профилю (адрес — из eGov-досье) и проходит стандартный
 * управляемый конвейер (см. {@link ServiceOrderService}).
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

    @PostMapping("/{orderUid}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable("orderUid") String orderUid,
                                                        Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(serviceOrderService.complete(identity, UUID.fromString(orderUid)));
    }
}
