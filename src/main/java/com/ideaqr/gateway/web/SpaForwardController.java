package com.ideaqr.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Native-scan deep link (Point 1). A QR now encodes {@code <origin>/s/<identifier>}, so a
 * phone's stock camera opens this path. We forward it to the SPA shell; the front-end reads
 * the identifier off the URL and either runs the scan (if signed in) or sends the visitor to
 * the login / guest panel first, then runs it. Kept as a server-side forward so a deep link
 * works on a cold load / refresh without a separate HTML file.
 */
@Controller
public class SpaForwardController {

    @GetMapping("/s/**")
    public String scanDeepLink() {
        return "forward:/index.html";
    }
}
