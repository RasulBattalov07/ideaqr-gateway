package com.ideaqr.gateway.service;

import com.ideaqr.gateway.enums.RequestType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock client simulating integration with external Kazakhstan state registries.
 * In production this would proxy to real government APIs; here it returns deterministic
 * mock payloads keyed by the object's sub-network and the request type.
 */
@Service
@Slf4j
public class RegistryClient {

    /**
     * Fetch mock registry data for an approved access.
     *
     * @param objectUid   the target object
     * @param requestType the approved request type
     * @return a structured mock payload
     */
    public Map<String, Object> fetchMockData(String objectUid, RequestType requestType) {
        String registry = resolveRegistryName(objectUid);
        log.info("Routing approved access to mock registry '{}' for object {}", registry, objectUid);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("registry", registry);
        payload.put("objectUid", objectUid);
        payload.put("requestType", requestType.name());
        payload.put("source", "MOCK_KZ_GOV_API");
        payload.put("fetchedAt", LocalDateTime.now().toString());
        payload.put("record", buildRecord(registry, objectUid));
        return payload;
    }

    private Map<String, Object> buildRecord(String registry, String objectUid) {
        Map<String, Object> record = new LinkedHashMap<>();
        switch (registry) {
            case "MED_REGISTER":
                record.put("facility", objectUid);
                record.put("clearanceLevel", "MEDICAL_STAFF");
                record.put("ehrLinked", true);
                break;
            case "INFRA_REGISTER":
                record.put("site", objectUid);
                record.put("zone", "RESTRICTED");
                record.put("maintenanceWindowOpen", true);
                break;
            case "FIN_REGISTER":
                record.put("account", objectUid);
                record.put("ledger", "TREASURY");
                record.put("auditFlag", false);
                break;
            case "CIV_REGISTER":
                record.put("service", objectUid);
                record.put("citizenPortal", true);
                break;
            default:
                record.put("object", objectUid);
                record.put("classification", "GENERIC");
                break;
        }
        return record;
    }

    private String resolveRegistryName(String objectUid) {
        if (objectUid == null) {
            return "GENERIC_REGISTER";
        }
        if (objectUid.startsWith("MED_")) {
            return "MED_REGISTER";
        }
        if (objectUid.startsWith("INFRA_")) {
            return "INFRA_REGISTER";
        }
        if (objectUid.startsWith("FIN_")) {
            return "FIN_REGISTER";
        }
        if (objectUid.startsWith("CIV_")) {
            return "CIV_REGISTER";
        }
        if (objectUid.startsWith("ADM_")) {
            return "ADM_REGISTER";
        }
        return "GENERIC_REGISTER";
    }
}
