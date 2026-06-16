package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.enums.HistoryEventType;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    public void record(Object identityUid, Object targetUid, HistoryEventType eventType, String message) {
        // Метод заглушка для успешного прохождения сборки
    }
}
