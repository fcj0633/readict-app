package com.readict.operations.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.readict.operations.application.SystemStatusService;
import com.readict.shared.web.RequestIdFilter;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {
    private final SystemStatusService service;
    public SystemStatusController(SystemStatusService service) { this.service = service; }
    @GetMapping("/status")
    public Status status(HttpServletRequest request) {
        service.checkReady();
        return new Status("UP", "readict", RequestIdFilter.id(request));
    }
    public record Status(String status, String service, String requestId) {}
}
