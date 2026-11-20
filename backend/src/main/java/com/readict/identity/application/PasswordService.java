package com.readict.identity.application;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.readict.identity.domain.*;
import com.readict.identity.infrastructure.persistence.IdentityRepository;

@Service
public class PasswordService {
    private final IdentityRepository users;private final PasswordEncoder passwords;private final Clock clock;private final SessionCleaner cleaner;private final TransactionTemplate transaction;
    public PasswordService(IdentityRepository users,PasswordEncoder passwords,Clock clock,SessionCleaner cleaner,PlatformTransactionManager manager) { this.users=users;this.passwords=passwords;this.clock=clock;this.cleaner=cleaner;transaction=new TransactionTemplate(manager); }
    public void change(ReadictPrincipal principal,String current,String replacement) {
        PasswordPolicy.validate(replacement);var snapshot=users.user(principal.userId());
        if(snapshot==null || current==null || current.length()>256 || !passwords.matches(current,snapshot.getPasswordHash())) throw new DomainProblem(422,"CURRENT_PASSWORD_INVALID","当前密码不正确");
        String hash=passwords.encode(replacement);
        transaction.executeWithoutResult(s -> {
            var locked=users.lockUser(principal.userId());
            if(locked==null || !"ACTIVE".equals(locked.getStatus()) || locked.getAuthVersion()!=principal.authVersion() || !locked.getPasswordHash().equals(snapshot.getPasswordHash())) throw new DomainProblem(409,"AUTH_CHANGED","认证信息已变化，请重新登录");
            if(users.changePassword(locked.getId(),hash,principal.authVersion(),clock.instant())!=1) throw new DomainProblem(409,"AUTH_CHANGED","认证信息已变化，请重新登录");
            users.revoke(locked.getId(),clock.instant());
        });
        cleaner.stale(principal.userId());
    }
}
