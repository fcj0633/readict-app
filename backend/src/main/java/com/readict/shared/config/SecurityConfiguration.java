package com.readict.shared.config;

import java.time.Clock;
import java.util.List;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;
import org.springframework.session.web.http.SessionRepositoryFilter;
import com.readict.shared.web.*;
import com.readict.identity.infrastructure.security.*;
import com.readict.identity.infrastructure.persistence.IdentityRepository;

@Configuration
public class SecurityConfiguration {
    @Bean SecurityContextRepository securityContextRepository() { return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),new HttpSessionSecurityContextRepository()); }
    @Bean CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),new CsrfAuthenticationStrategy(csrf)));
    }
    @Bean AuthenticationManager authenticationManager(IdentityAuthenticationProvider provider) { return new ProviderManager(provider); }
    @Bean FilterRegistrationBean<SessionDependencyFilter> sessionDependencyFilter(Problems problems) {
        var registration=new FilterRegistrationBean<>(new SessionDependencyFilter(problems));registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER-1);return registration;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, Problems problems,IdentityRepository users,Clock clock,SecurityContextRepository contexts,CsrfTokenRepository csrf) throws Exception {
        return http
            .addFilterBefore(new RequestIdFilter(),SecurityContextHolderFilter.class)
            .addFilterAfter(new AuthVersionFilter(users,clock,problems),SecurityContextHolderFilter.class)
            .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/system/status","/actuator/health/liveness","/actuator/health/readiness","/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/password-reset/challenges","/api/v1/auth/password-reset/verify","/api/v1/auth/password-reset/complete").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/v1/auth/me","/api/v1/me/settings").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/dictionary/audio/*").authenticated()
                .requestMatchers(HttpMethod.HEAD,"/api/v1/dictionary/audio/*").authenticated()
                .requestMatchers(HttpMethod.GET,"/api/v1/books","/api/v1/books/imports/*","/api/v1/books/*",
                        "/api/v1/books/*/cover","/api/v1/books/*/reader-manifest",
                        "/api/v1/books/*/artifacts/*/epub","/api/v1/books/*/artifacts/*/text-index").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/books/imports").authenticated()
                .requestMatchers(HttpMethod.DELETE,"/api/v1/books/*").authenticated()
                .requestMatchers(HttpMethod.PUT,"/api/v1/auth/password","/api/v1/me/settings").authenticated()
                .requestMatchers(HttpMethod.POST,"/api/v1/auth/verification/challenges","/api/v1/auth/verification/verify").authenticated()
                .anyRequest().denyAll())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).requestCache(cache -> cache.disable())
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("readict-dev-session")
                .logoutSuccessHandler((req,res,auth) -> { AuthVersionFilter.clearCookie(res);res.setStatus(204); }))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req,res,ex) -> problems.write(req,res,401,"UNAUTHORIZED","请先登录"))
                .accessDeniedHandler((req,res,ex) -> {
                    String code=ex instanceof MissingCsrfTokenException?"CSRF_MISSING":ex instanceof InvalidCsrfTokenException?"CSRF_INVALID":"FORBIDDEN";
                    problems.write(req,res,403,code,"请求未获授权，请刷新后重试");
                }))
            .build();
    }
}
