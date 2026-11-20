package com.readict.identity.infrastructure.security;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.readict.identity.domain.*;
import com.readict.identity.infrastructure.persistence.IdentityRepository;

@Component
public class IdentityAuthenticationProvider implements AuthenticationProvider {
    public record LoginRequest(String type,String identifier) {}
    private final IdentityRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String dummy;
    private final Semaphore hashing=new Semaphore(4);
    public IdentityAuthenticationProvider(IdentityRepository users,PasswordEncoder passwords,Clock clock) {
        this.users=users;this.passwords=passwords;this.clock=clock;dummy=passwords.encode("unmatchable-"+java.util.UUID.randomUUID());
    }
    @Override public Authentication authenticate(Authentication input) {
        if(!hashing.tryAcquire()) throw new DomainProblem(429,"RATE_LIMITED","操作过于频繁，请稍后重试",1);
        try {
            LoginRequest request=(LoginRequest)input.getPrincipal();
            LoginIdentifier id=LoginIdentifier.parse(request.type(),request.identifier());
            var user=users.find(id);String password=(String)input.getCredentials();
            boolean match=false;
            if(password!=null && password.length()<=256) {
                try { match=passwords.matches(password,user==null?dummy:user.getPasswordHash()); }
                catch(IllegalArgumentException ex) { passwords.matches(password,dummy); }
            } else passwords.matches("invalid-length",dummy);
            if(!match || user==null || !"ACTIVE".equals(user.getStatus())) throw new BadCredentialsException("INVALID_CREDENTIALS");
            return UsernamePasswordAuthenticationToken.authenticated(new ReadictPrincipal(user.getId(),user.getAuthVersion(),clock.instant()),null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        } finally { hashing.release(); }
    }
    @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
}
