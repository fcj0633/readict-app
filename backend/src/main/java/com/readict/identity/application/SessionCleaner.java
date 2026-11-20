package com.readict.identity.application;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.*;
import org.springframework.security.core.context.SecurityContext;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.identity.infrastructure.persistence.IdentityRepository;

@Service
public class SessionCleaner {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final IdentityRepository users;
    private final Set<UUID> pending=ConcurrentHashMap.newKeySet();
    public SessionCleaner(FindByIndexNameSessionRepository<? extends Session> sessions,IdentityRepository users) { this.sessions=sessions;this.users=users; }
    public void stale(UUID user) {
        try {
            var current=users.user(user);
            for(var entry:sessions.findByPrincipalName(user.toString()).entrySet()) {
                Object value=entry.getValue().getAttribute("SPRING_SECURITY_CONTEXT");
                if(value instanceof SecurityContext context && context.getAuthentication()!=null && context.getAuthentication().getPrincipal() instanceof ReadictPrincipal p
                    && (current==null || current.getAuthVersion()!=p.authVersion() || !"ACTIVE".equals(current.getStatus()))) sessions.deleteById(entry.getKey());
            }
            pending.remove(user);
        } catch(RuntimeException ex) {
            if(pending.size()<1000) pending.add(user);
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("code=SESSION_CLEANUP_RETRY");
        }
    }
    @Scheduled(fixedDelay=60000) public void retry() { pending.stream().limit(100).toList().forEach(this::stale); }
}
