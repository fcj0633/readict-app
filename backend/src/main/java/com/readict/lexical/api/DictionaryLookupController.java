package com.readict.lexical.api;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.readict.identity.api.AuthController;
import com.readict.lexical.application.LookupService;
import com.readict.shared.web.ApiProblem;

@RestController
@RequestMapping("/api/v1/dictionary")
public class DictionaryLookupController {
    private final LookupService lookups;

    public DictionaryLookupController(LookupService lookups) { this.lookups = lookups; }

    @PostMapping("/lookups")
    public ResponseEntity<DictionaryLookupDtos.LookupResponse> lookup(Authentication auth,
                                                                       @RequestBody(required = false) DictionaryLookupDtos.LookupRequest request) {
        if (request == null) throw new ApiProblem(400, "INVALID_REQUEST", "查词请求格式有误");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(lookups.lookup(AuthController.principal(auth), request));
    }
}
