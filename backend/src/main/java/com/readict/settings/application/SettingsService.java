package com.readict.settings.application;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.readict.identity.domain.*;
import com.readict.identity.infrastructure.persistence.IdentityRepository;
import com.readict.identity.infrastructure.security.AuthSecrets;
import com.readict.settings.api.Preferences;
import com.readict.settings.infrastructure.persistence.SettingsRepository;

@Service
public class SettingsService {
    public record View(Preferences preferences,long version) {}
    private final SettingsRepository settings;private final IdentityRepository users;private final Clock clock;
    public SettingsService(SettingsRepository settings,IdentityRepository users,Clock clock) { this.settings=settings;this.users=users;this.clock=clock; }
    public View get(ReadictPrincipal principal) { var value=settings.find(principal.userId());if(value==null) throw new DomainProblem(503,"SETTINGS_UNAVAILABLE","设置暂不可用");return new View(Preferences.from(value),value.getVersion()); }
    @Transactional public View save(ReadictPrincipal principal,Preferences preferences,long expected,UUID requestId) {
        if(preferences==null || requestId==null || expected<1) throw new DomainProblem(422,"SETTINGS_INVALID","设置请求格式有误");
        try { preferences.validate(); } catch(ArithmeticException ex) { throw new DomainProblem(422,"SETTINGS_INVALID","设置请求格式有误"); }
        var user=users.lockUser(principal.userId());
        if(user==null || user.getAuthVersion()!=principal.authVersion() || !"ACTIVE".equals(user.getStatus())) throw new DomainProblem(409,"AUTH_CHANGED","认证信息已变化，请重新登录");
        var value=settings.lock(principal.userId());byte[] hash=AuthSecrets.sha256(preferences.canonical());
        if(requestId.equals(value.getLastRequestId())) {
            if(!java.security.MessageDigest.isEqual(hash,value.getLastRequestHash())) throw new DomainProblem(409,"IDEMPOTENCY_KEY_REUSED","请求编号已用于其他设置");
            return new View(Preferences.from(value),value.getVersion());
        }
        if(value.getVersion()!=expected) throw new DomainProblem(409,"SETTINGS_CONFLICT","设置已在其他页面更新");
        value.setFont(preferences.font());value.setFontSize(preferences.fontSize());value.setLineHeight(preferences.lineHeight());value.setContentWidth(preferences.width());value.setTheme(preferences.theme());value.setPronunciation(preferences.pronunciation());value.setLastRequestId(requestId);value.setLastRequestHash(hash);value.setUpdatedAt(clock.instant());
        if(settings.save(value,expected)!=1) throw new DomainProblem(409,"SETTINGS_CONFLICT","设置已在其他页面更新");
        return new View(preferences,expected+1);
    }
}
