package com.readict.operations.application;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("metadata")
public class MetadataHealthIndicator implements HealthIndicator {
    private final SystemStatusService service;
    public MetadataHealthIndicator(SystemStatusService service) { this.service = service; }
    @Override public Health health() {
        try { service.checkReady(); return Health.up().build(); }
        catch (RuntimeException ex) { return Health.down().build(); }
    }
}
