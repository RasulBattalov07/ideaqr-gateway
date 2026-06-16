package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.dto.ScanRequest;
import com.ideaqr.gateway.dto.ReportRequest;
import com.ideaqr.gateway.dto.GatewayResponse;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class GatewayService {

    public GatewayResponse scan(Identity identity, ScanRequest request) {
        GatewayResponse response = new GatewayResponse();
        String uid = request.getObjectUid() != null ? request.getObjectUid().toUpperCase() : "UNKNOWN";

        // Базовый успешный вердикт
        response.setOutcome("APPROVED");
        response.setReason("Доступ разрешен: роль и контекст подтверждены");
        response.setRiskLevel("LOW");
        response.setObjectUid(uid);

        // Генерация ID для красивой анимации шагов на фронтенде
        response.setIdentityUid(identity != null && identity.getIdentityUid() != null ? identity.getIdentityUid() : UUID.randomUUID());
        response.setRequestUid(UUID.randomUUID());
        response.setDecisionUid(UUID.randomUUID());
        response.setInteractionUid(UUID.randomUUID());
        response.setHistoryUid(UUID.randomUUID());

        // Подготовка данных для карточек
        Map<String, Object> data = new HashMap<>();

        if (uid.contains("PATIENT")) {
            response.setCategory("MEDICAL");
            data.put("patientName", "Алия Нурланова");
            data.put("patientId", uid);
            data.put("age", 34);
            data.put("gender", "Женский");
            data.put("bloodType", "II (A) Rh+");
            data.put("iinMasked", "8901••••••23");
            data.put("allergies", Arrays.asList("Пенициллин", "Пыльца берёзы"));
            data.put("chronicConditions", Arrays.asList("Бронхиальная астма", "Гипотиреоз"));
            
            data.put("medications", Arrays.asList(
                Map.of("name", "Левотироксин", "dose", "50 мкг", "schedule", "1 раз в день"),
                Map.of("name", "Сальбутамол", "dose", "100 мкг", "schedule", "По потребности")
            ));
            
            Map<String, Object> vitals = new HashMap<>();
            vitals.put("series", Arrays.asList(
                Map.of("label", "Янв", "systolic", 122, "diastolic", 80, "pulse", 74),
                Map.of("label", "Фев", "systolic", 126, "diastolic", 82, "pulse", 78)
            ));
            data.put("vitals", vitals);
            
            data.put("recentVisits", Arrays.asList(
                Map.of("date", "2026-05-21", "clinic", "Городская поликлиника №4", "reason", "Осмотр", "doctor", "Доктор С. Ким")
            ));
            data.put("immunizations", Arrays.asList("Грипп (2025)", "COVID-19"));
            data.put("aiNotes", "Уровень ТТГ стабилизировался.");

        } else if (uid.contains("ADIDAS")) {
            response.setCategory("RETAIL");
            data.put("productName", "Adidas Originals Trefoil — чёрная футболка");
            data.put("brand", "Adidas");
            data.put("sku", uid);
            data.put("price", 25000);
            data.put("currency", "₸");
            data.put("rating", 4.7);
            data.put("reviews", 318);
            data.put("description", "Классическая хлопковая футболка Adidas Originals с логотипом Trefoil.");
            data.put("sizes", Arrays.asList(
                    Map.of("size", "S", "stock", 5),
                    Map.of("size", "M", "stock", 12),
                    Map.of("size", "L", "stock", 0)
            ));
            data.put("colors", Arrays.asList("Чёрный", "Белый"));
            data.put("alternatives", Arrays.asList(
                Map.of("store", "Kaspi Магазин", "price", 22990, "url", "https://kaspi.kz", "note", "Дешевле, доставка 1-2 дня")
            ));
            data.put("loyalty", Map.of("code", "IDEAQR-ADIDAS-10", "discount", "10%", "note", "Эксклюзивный промокод"));

        } else if (uid.contains("ECO") || uid.contains("BIN")) {
            response.setCategory("ECO");
            data.put("title", "Умный контейнер №102");
            data.put("binId", uid);
            data.put("fillLevel", 82);
            data.put("status", "Требует вывоза");
            data.put("location", "г. Астана, ул. Кабанбай батыра, 53");
            data.put("wasteTypes", Arrays.asList("Смешанные отходы", "Пластик"));
            data.put("pickupSchedule", Arrays.asList(
                Map.of("day", "Понедельник", "time", "07:00")
            ));
            data.put("operator", "ТОО «Astana Tazalyk»");
            data.put("actions", Arrays.asList("report"));

        } else if (uid.contains("INFRA")) {
            response.setCategory("INFRASTRUCTURE");
            data.put("title", "Трансформаторная подстанция №07");
            data.put("assetId", uid);
            data.put("status", "В эксплуатации");
            data.put("voltage", "10 кВ");
            data.put("location", "г. Астана, ул. Сыганак, 18");
            data.put("operator", "АО «Астана-РЭК»");
            data.put("lastInspection", "2026-05-28");
            data.put("technicalNotes", "Обнаружен нагрев фазы B. Требуется проверка.");
            data.put("actions", Arrays.asList("report"));
            
        } else {
            response.setCategory("GENERAL");
            data.put("title", "Объект: " + uid);
            data.put("description", "Данные отсутствуют в реестре.");
        }

        response.setData(data);
        return response;
    }

    public GatewayResponse report(Identity identity, ReportRequest request) {
        GatewayResponse response = new GatewayResponse();
        response.setOutcome("APPROVED");
        response.setReason("Обращение успешно зафиксировано");
        response.setRiskLevel("LOW");
        response.setDecisionUid(UUID.randomUUID());
        response.setHistoryUid(UUID.randomUUID());
        return response;
    }
}
