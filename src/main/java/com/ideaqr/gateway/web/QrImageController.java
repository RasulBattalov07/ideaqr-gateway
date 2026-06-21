package com.ideaqr.gateway.web;

import com.ideaqr.gateway.service.QrService;
import com.ideaqr.gateway.util.Hashing;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Serves the scannable QR PNG for an object as a <b>cacheable image resource</b>
 * (audit 3.4). The old admin list re-encoded a PNG and inlined a base64 data URI for
 * every row on every page load; instead, the list now returns a URL to this endpoint,
 * the PNG is generated once and the response is cached by the browser/CDN (the image
 * is deterministic for a given identifier).
 */
@RestController
@RequiredArgsConstructor
public class QrImageController {

    private final QrService qrService;

    @GetMapping(value = "/api/qr/{objectUid}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> png(@PathVariable String objectUid) {
        byte[] png = qrService.pngBytes(objectUid);
        String etag = "\"" + Hashing.sha256Hex(objectUid).substring(0, 16) + "\"";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic().immutable())
                .eTag(etag)
                .body(png);
    }
}
