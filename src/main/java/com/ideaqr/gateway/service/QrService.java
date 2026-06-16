package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Qr;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.dto.QrCreationRequest;
import com.ideaqr.gateway.dto.QrCreationResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QrService {

    public QrCreationResponse createGovernedObject(Identity admin, QrCreationRequest request) {
        QrCreationResponse response = new QrCreationResponse();
        // Возвращаем фейковый ID для админ-панели
        response.setObjectUid(UUID.randomUUID().toString());
        response.setQrImageDataUri("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");
        return response;
    }

    public List<RegistryObject> listObjectsForAdmin(Object identityUid) {
        return new ArrayList<>(); 
    }

    public String regenerateImageFor(Object objectUid) {
        return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
    }

    public Qr createPrimaryQr(Identity identity) {
        Qr qr = new Qr();
        // Генерируем уникальный ID QR-кода для нового пользователя
        qr.setQrUid(UUID.randomUUID());
        return qr;
    }
}
