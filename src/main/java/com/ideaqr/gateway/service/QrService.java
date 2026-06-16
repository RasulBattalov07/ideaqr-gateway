package com.ideaqr.gateway.service;

import com.ideaqr.gateway.domain.Identity;
import com.ideaqr.gateway.domain.Qr;
import com.ideaqr.gateway.domain.RegistryObject;
import com.ideaqr.gateway.dto.QrCreationRequest;
import com.ideaqr.gateway.dto.QrCreationResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class QrService {

    public QrCreationResponse createGovernedObject(Identity admin, QrCreationRequest request) {
        return null;
    }

    public List<RegistryObject> listObjectsForAdmin(Object identityUid) {
        return new ArrayList<>(); 
    }

    public String regenerateImageFor(Object objectUid) {
        return "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
    }

    // Тот самый новый метод, который нужен для UserService при регистрации
    public Qr createPrimaryQr(Identity identity) {
        return null;
    }
}
