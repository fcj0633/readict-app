package com.readict.identity.application;

import java.time.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import com.readict.identity.domain.*;
import com.readict.identity.api.UserView;
import com.readict.identity.infrastructure.persistence.IdentityRepository;
import com.readict.identity.infrastructure.persistence.entity.UserEntity;
import com.readict.settings.infrastructure.persistence.SettingsRepository;
import com.readict.settings.infrastructure.persistence.entity.SettingsEntity;

@Service
public class RegistrationService {
    private final IdentityRepository users;
    private final SettingsRepository settings;
    private final PasswordEncoder passwords;
    private final RateService rates;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public RegistrationService(IdentityRepository users,SettingsRepository settings,PasswordEncoder passwords,RateService rates,Clock clock,PlatformTransactionManager manager) {
        this.users=users;this.settings=settings;this.passwords=passwords;this.rates=rates;this.clock=clock;transaction=new TransactionTemplate(manager);
    }
    public UserView register(String type,String identifier,String password,String ip) {
        LoginIdentifier id=LoginIdentifier.parse(type,identifier);PasswordPolicy.validate(password);rates.registration(ip);
        String hash=passwords.encode(password);
        try { return transaction.execute(s -> {
            Instant now=clock.instant();UserEntity u=new UserEntity();u.setId(UUID.randomUUID());
            u.setEmail(id.type().equals("email")?id.displayValue():null);u.setEmailKey(id.type().equals("email")?id.canonicalKey():null);
            u.setPhoneE164(id.type().equals("phone")?id.canonicalKey():null);u.setPasswordHash(hash);u.setStatus("ACTIVE");u.setCreatedAt(now);u.setUpdatedAt(now);
            users.insertUser(u);
            SettingsEntity p=new SettingsEntity();p.setUserId(u.getId());p.setFont("Georgia");p.setFontSize(20);p.setLineHeight(new BigDecimal("1.8"));p.setContentWidth(660);p.setTheme("warm");p.setPronunciation("en-GB");p.setVersion(1);p.setUpdatedAt(now);settings.insert(p);
            return UserView.from(u);
        }); } catch(DuplicateKeyException ex) {
            String message=ex.getMostSpecificCause().getMessage();
            if(message!=null && (message.contains("uk_user_email_key") || message.contains("uk_user_phone"))) throw new DomainProblem(409,"IDENTIFIER_UNAVAILABLE","该账号无法注册");
            throw ex;
        }
    }
}
