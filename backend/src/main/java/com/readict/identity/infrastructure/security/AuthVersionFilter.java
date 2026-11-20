package com.readict.identity.infrastructure.security;

import java.io.IOException;
import java.time.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import com.readict.identity.domain.ReadictPrincipal;
import com.readict.identity.infrastructure.persistence.IdentityRepository;
import com.readict.shared.web.Problems;

public class AuthVersionFilter extends OncePerRequestFilter {
    private final IdentityRepository users;private final Clock clock;private final Problems problems;
    public AuthVersionFilter(IdentityRepository users,Clock clock,Problems problems) { this.users=users;this.clock=clock;this.problems=problems; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return request.getRequestURI().equals("/api/v1/auth/logout") || request.getRequestURI().equals("/api/v1/auth/csrf") || request.getRequestURI().startsWith("/actuator/") || request.getRequestURI().equals("/api/v1/system/status"); }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication!=null && authentication.getPrincipal() instanceof ReadictPrincipal principal) {
            try {
                var user=users.user(principal.userId());
                if(user==null || !"ACTIVE".equals(user.getStatus()) || user.getAuthVersion()!=principal.authVersion() || !clock.instant().isBefore(principal.authenticatedAt().plus(Duration.ofDays(7)))) {
                    SecurityContextHolder.clearContext();if(req.getSession(false)!=null) req.getSession(false).invalidate();clearCookie(res);
                    problems.write(req,res,401,"SESSION_EXPIRED","会话已过期，请重新登录");return;
                }
            } catch(org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException ex) {
                problems.write(req,res,503,"AUTH_DEPENDENCY_UNAVAILABLE","认证服务暂不可用");return;
            }
        }
        chain.doFilter(req,res);
    }
    public static void clearCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie","readict-dev-session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }
}
