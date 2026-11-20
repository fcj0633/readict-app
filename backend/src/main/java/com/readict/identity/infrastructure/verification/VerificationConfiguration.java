package com.readict.identity.infrastructure.verification;

import java.time.Clock;
import java.util.Arrays;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class VerificationConfiguration {
    @Bean VerificationSender verificationSender(Environment env,Clock clock) {
        String mode=env.getProperty("readict.verification.sender","unavailable");
        boolean local=env.getProperty("readict.verification.local-enabled",Boolean.class,false);
        if(mode.equals("unavailable")) return new UnavailableSender();
        if(!mode.equals("local") || !local || !Arrays.asList(env.getActiveProfiles()).contains("dev") || !env.getProperty("server.address","127.0.0.1").equals("127.0.0.1")
            || env.getProperty("readict.verification.hmac-key","").length()<32) throw new IllegalStateException("本地验证通道配置无效或非本机开发环境");
        return new LocalOutboxSender(clock);
    }
}
