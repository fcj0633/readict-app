package com.readict.settings.api;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import com.readict.settings.application.SettingsService;
import com.readict.identity.api.AuthController;

@RestController
@RequestMapping("/api/v1/me/settings")
public class SettingsController {
    public record Update(Preferences preferences,long expectedVersion,UUID requestId) {}
    private final SettingsService service;
    public SettingsController(SettingsService service) { this.service=service; }
    @GetMapping public SettingsService.View get(Authentication auth) { return service.get(AuthController.principal(auth)); }
    @PutMapping public SettingsService.View save(Authentication auth,@RequestBody Update input) { return service.save(AuthController.principal(auth),input.preferences(),input.expectedVersion(),input.requestId()); }
}
