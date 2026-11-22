package com.example.library.common.proxy;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/admin/settings/proxy")
public class ProxySettingsController {
    private final ProxySettingsService service;
    ProxySettingsController(ProxySettingsService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ProxySnapshot> get() { return response(service.snapshot()); }

    @PutMapping
    public ResponseEntity<ProxySnapshot> put(@RequestBody(required = false) JsonNode body, Principal principal) {
        return response(service.replace(body, principal.getName()));
    }

    private static ResponseEntity<ProxySnapshot> response(ProxySnapshot settings) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(settings);
    }
}
