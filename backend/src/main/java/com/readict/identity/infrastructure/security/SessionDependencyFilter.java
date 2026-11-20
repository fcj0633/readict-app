package com.readict.identity.infrastructure.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
import com.readict.shared.web.*;

/** Wraps JDBC Session loading/saving, which happens outside the Security filter chain. */
public class SessionDependencyFilter extends OncePerRequestFilter {
    private final Problems problems;
    public SessionDependencyFilter(Problems problems) { this.problems=problems; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        try { chain.doFilter(request,response); }
        catch(RuntimeException ex) {
            boolean database=false;for(Throwable cause=ex;cause!=null;cause=cause.getCause()) if(cause instanceof org.springframework.dao.DataAccessException || cause instanceof org.springframework.transaction.TransactionException || cause instanceof org.springframework.core.serializer.support.SerializationFailedException) database=true;
            if(!database || response.isCommitted()) throw ex;
            response.resetBuffer();
            if(RequestIdFilter.id(request)==null) request.setAttribute(RequestIdFilter.ATTRIBUTE,RequestIdFilter.normalize(null));
            response.setHeader("X-Request-Id",RequestIdFilter.id(request));
            problems.write(request,response,503,"AUTH_DEPENDENCY_UNAVAILABLE","认证服务暂不可用");
        }
    }
}
