package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.service.RetailCheckoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * СЦЕНАРИЙ «БИЗНЕС И МАГАЗИНЫ» — розничный чек-аут (см. {@link RetailCheckoutService}).
 * Покупательская сторона: корзина / оплата на месте / мои покупки. Кассовая сторона
 * (роль CASHIER, рабочий режим): чек клиента по его личному QR, приём оплаты, выдача
 * с передачей владения (QR товара не меняется).
 */
@RestController
@RequestMapping("/api/v2/retail")
@RequiredArgsConstructor
public class RetailCheckoutController {

    private final RetailCheckoutService retailCheckoutService;
    private final AuthSupport authSupport;

    /** Мои покупки: корзина + «оплачено, ждёт выдачи» + недавно полученное, с итогами. */
    @GetMapping("/mine")
    public ResponseEntity<Map<String, Object>> mine(Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.mine(identity));
    }

    /** «Добавить в корзину» со скана товара. */
    @PostMapping("/cart")
    public ResponseEntity<Map<String, Object>> addToCart(@RequestBody Map<String, String> body,
                                                         Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.addToCart(identity,
                body != null ? body.get("objectUid") : null));
    }

    /** «Оплатить на месте» со скана товара (демо-платёж): оплачен, но не выдан. */
    @PostMapping("/buy")
    public ResponseEntity<Map<String, Object>> buyNow(@RequestBody Map<String, String> body,
                                                      Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.buyNow(identity,
                body != null ? body.get("objectUid") : null));
    }

    /** Покупатель убирает неоплаченную позицию из корзины (в т.ч. стоя у кассы). */
    @PostMapping("/{lineUid}/remove")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable("lineUid") String lineUid,
                                                      Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.removeFromCart(identity, UUID.fromString(lineUid)));
    }

    /** Чек клиента для кассира — живое обновление панели после скана личного QR. */
    @GetMapping("/checkout/{buyerUid}")
    public ResponseEntity<Map<String, Object>> checkout(@PathVariable("buyerUid") String buyerUid,
                                                        Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.checkoutView(identity, UUID.fromString(buyerUid)));
    }

    /** Кассир принимает оплату корзины клиента (все линии CART → PAID, демо-платёж). */
    @PostMapping("/checkout/{buyerUid}/collect")
    public ResponseEntity<Map<String, Object>> collect(@PathVariable("buyerUid") String buyerUid,
                                                       Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.collectPayment(identity, UUID.fromString(buyerUid)));
    }

    /** Кассир выдаёт оплаченную позицию: владение переходит покупателю, QR не меняется. */
    @PostMapping("/{lineUid}/issue")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable("lineUid") String lineUid,
                                                     Authentication authentication) {
        Identity identity = authSupport.requireIdentity(authentication);
        return ResponseEntity.ok(retailCheckoutService.issue(identity, UUID.fromString(lineUid)));
    }
}
