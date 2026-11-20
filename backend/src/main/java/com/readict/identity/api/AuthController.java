package com.readict.identity.api;

import java.util.Map;
import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import com.readict.identity.application.*;
import com.readict.identity.domain.*;
import com.readict.identity.infrastructure.persistence.IdentityRepository;
import com.readict.identity.infrastructure.security.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public static class Credentials {
        private String identifierType,identifier,password;
        public String getIdentifierType(){return identifierType;} public void setIdentifierType(String v){identifierType=v;}
        public String getIdentifier(){return identifier;} public void setIdentifier(String v){identifier=v;}
        public String getPassword(){return password;} public void setPassword(String v){password=v;}
    }
    public static class PasswordChange {
        private String currentPassword,newPassword;
        public String getCurrentPassword(){return currentPassword;} public void setCurrentPassword(String v){currentPassword=v;}
        public String getNewPassword(){return newPassword;} public void setNewPassword(String v){newPassword=v;}
    }
    private final RegistrationService registration;private final AuthenticationManager authenticationManager;private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contexts;private final IdentityRepository users;private final RateService rates;private final PasswordService passwords;private final SessionCleaner cleaner;
    public AuthController(RegistrationService registration,AuthenticationManager manager,SessionAuthenticationStrategy strategy,SecurityContextRepository contexts,IdentityRepository users,RateService rates,PasswordService passwords,SessionCleaner cleaner) {
        this.registration=registration;authenticationManager=manager;sessionStrategy=strategy;this.contexts=contexts;this.users=users;this.rates=rates;this.passwords=passwords;this.cleaner=cleaner;
    }
    @GetMapping("/csrf") public ResponseEntity<Map<String,String>> csrf(CsrfToken token) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("token",token.getToken(),"headerName",token.getHeaderName())); }
    @PostMapping("/register") public ResponseEntity<Map<String,UserView>> register(@RequestBody Credentials input,HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(Map.of("user",registration.register(input.identifierType,input.identifier,input.password,request.getRemoteAddr())));
    }
    @PostMapping("/login") public Map<String,UserView> login(@RequestBody Credentials input,HttpServletRequest request,HttpServletResponse response,Authentication existing) {
        if(existing!=null && existing.getPrincipal() instanceof ReadictPrincipal) throw new DomainProblem(409,"ALREADY_AUTHENTICATED","请先退出当前账号");
        var id=LoginIdentifier.parse(input.identifierType,input.identifier);var budget=rates.login(id.canonicalKey(),request.getRemoteAddr());
        Authentication auth;
        try { auth=authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(new IdentityAuthenticationProvider.LoginRequest(id.type(),id.canonicalKey()),input.password)); }
        catch(org.springframework.security.core.AuthenticationException ex) { throw new DomainProblem(401,"INVALID_CREDENTIALS","登录信息不正确"); }
        var principal=(ReadictPrincipal)auth.getPrincipal();var user=users.user(principal.userId());
        if(user==null || user.getAuthVersion()!=principal.authVersion() || !"ACTIVE".equals(user.getStatus())) throw new DomainProblem(409,"AUTH_CHANGED","认证信息已变化，请重新登录");
        cleaner.stale(principal.userId());
        sessionStrategy.onAuthentication(auth,request,response);
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(auth);SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
        rates.successfulLogin(budget);response.setHeader("Cache-Control","no-store");return Map.of("user",UserView.from(user));
    }
    @GetMapping("/me") public UserView me(Authentication auth,HttpServletResponse response) { response.setHeader("Cache-Control","no-store");return UserView.from(users.user(principal(auth).userId())); }
    @PutMapping("/password") public ResponseEntity<Void> password(@RequestBody PasswordChange input,Authentication auth,HttpServletRequest request,HttpServletResponse response) {
        passwords.change(principal(auth),input.currentPassword,input.newPassword);
        SecurityContextHolder.clearContext();if(request.getSession(false)!=null)request.getSession(false).invalidate();AuthVersionFilter.clearCookie(response);
        return ResponseEntity.noContent().build();
    }
    public static ReadictPrincipal principal(Authentication auth) {
        if(auth==null || !(auth.getPrincipal() instanceof ReadictPrincipal p)) throw new DomainProblem(401,"UNAUTHORIZED","请先登录");return p;
    }
}
