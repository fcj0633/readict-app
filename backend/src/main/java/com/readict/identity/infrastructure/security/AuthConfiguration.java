package com.readict.identity.infrastructure.security;

import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.*;

@Configuration
@EnableScheduling
public class AuthConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean public PasswordEncoder passwordEncoder() {
        var encoder=new Pbkdf2PasswordEncoder("",16,600000,Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder("pbkdf2-r1",Map.of("pbkdf2-r1",encoder));
    }
}
