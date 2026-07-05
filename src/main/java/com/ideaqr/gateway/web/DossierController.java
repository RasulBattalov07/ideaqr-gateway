package com.ideaqr.gateway.web;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.User;
import com.ideaqr.gateway.service.CitizenDossierService;
import com.ideaqr.gateway.service.MedicalService;
import com.ideaqr.gateway.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «Мой цифровой пакет» (Phase 2): идентификаторы объектов досье текущего гражданина
 * (медкарта / правовой статус / визитка), eGov-снимок и живой счётчик активных рецептов —
 * то, из чего дашборд собирает модули «Медицина», «Услуги» и «Бизнес». Досье лениво
 * доусоздаётся, поэтому эндпоинт самодостаточен и для аккаунтов, созданных до Phase 2.
 */
@RestController
@RequestMapping("/api/v2/dossier")
@RequiredArgsConstructor
public class DossierController {

    private final CitizenDossierService citizenDossierService;
    private final MedicalService medicalService;
    private final UserService userService;
    private final AuthSupport authSupport;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        User user = authSupport.requireUser(authentication);
        Identity identity = userService.identityOf(user);
        Map<String, Object> res = new LinkedHashMap<>();

        CitizenDossierService.Dossier dossier = citizenDossierService.ensureFor(user, identity, null);
        if (dossier == null) { // гостевая личность — досье не положено
            res.put("available", false);
            return ResponseEntity.ok(res);
        }
        res.put("available", true);
        res.put("medicalObjectUid", dossier.medical().getObjectUid());
        res.put("legalObjectUid", dossier.legal().getObjectUid());
        res.put("vcardObjectUid", dossier.vcard().getObjectUid());

        // eGov-снимок для шапки дашборда (маскированный ИИН + адрес) — из правового досье.
        Map<String, Object> legal = citizenDossierService.payload(dossier.legal());
        Map<String, Object> egov = new LinkedHashMap<>();
        Object iin = legal.get("iin");
        egov.put("iinMasked", iin == null || iin.toString().length() < 12
                ? "••••••••••••"
                : iin.toString().charAt(0) + "•••••••••" + iin.toString().substring(10));
        egov.put("address", legal.get("address"));
        egov.put("birthDate", legal.get("birthDate"));
        res.put("egov", egov);

        List<Map<String, Object>> rx = medicalService.listForObject(dossier.medical().getObjectUid());
        res.put("prescriptionsTotal", rx.size());
        res.put("prescriptionsActive", rx.stream().filter(p -> "PRESCRIBED".equals(p.get("status"))).count());
        return ResponseEntity.ok(res);
    }
}
