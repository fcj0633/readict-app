package com.readict.shared.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import com.readict.shared.web.*;

@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, Problems problems) throws Exception {
        return http
            .addFilterBefore(new RequestIdFilter(), SecurityContextHolderFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/system/status", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .anyRequest().denyAll())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> problems.write(req, res, 401, "UNAUTHORIZED", "请求未获授权"))
                .accessDeniedHandler((req, res, ex) -> problems.write(req, res, 403, "FORBIDDEN", "请求被拒绝")))
            .build();
    }
}
