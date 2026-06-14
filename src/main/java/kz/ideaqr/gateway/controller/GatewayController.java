package kz.ideaqr.gateway.controller;

import kz.ideaqr.gateway.dto.GatewayResponse;
import kz.ideaqr.gateway.dto.ScanRequest;
import kz.ideaqr.gateway.entity.AuditLog;
import kz.ideaqr.gateway.service.RoutingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST-контроллер шлюза IDEAQR.
 *
 * POST /api/gateway/scan   — обработать запрос сканирования
 * GET  /api/gateway/audit  — получить полный журнал аудита
 * GET  /api/gateway/health — статус системы
 */
@RestController
@RequestMapping("/api/gateway")
@CrossOrigin(origins = "*")   // для локальной разработки; в продакшен — ограничить
@RequiredArgsConstructor
public class GatewayController {

    private final RoutingService routingService;

    /**
     * Основной эндпоинт: принять запрос сканирования, провалидировать, маршрутизировать.
     * Возвращает 200 OK при GRANTED, 403 Forbidden при DENIED.
     * Запись в аудит происходит в обоих случаях атомарно.
     */
    @PostMapping("/scan")
    public ResponseEntity<GatewayResponse> scan(@RequestBody ScanRequest request) {
        GatewayResponse response = routingService.processRequest(request);
        int httpStatus = "GRANTED".equals(response.getStatus()) ? 200 : 403;
        return ResponseEntity.status(httpStatus).body(response);
    }

    /**
     * Журнал аудита — все события в порядке убывания времени.
     * Только метаданные: кто, к чему, когда, результат.
     */
    @GetMapping("/audit")
    public ResponseEntity<List<AuditLog>> getAuditLog() {
        return ResponseEntity.ok(routingService.getFullAuditLog());
    }

    /**
     * Health-check для мониторинга (Render, UptimeRobot и т.д.)
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status",  "UP",
            "service", "IDEAQR Gateway MVP",
            "version", "0.1.0"
        ));
    }
}
