package com.readict.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import jakarta.validation.ConstraintViolationException;
import com.readict.operations.application.SystemNotReadyException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.readict.identity.domain.DomainProblem.class)
    public ResponseEntity<ProblemDetail> domain(com.readict.identity.domain.DomainProblem problem,HttpServletRequest request) {
        var builder=ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if(problem.retryAfter()>0) builder.header("Retry-After",Integer.toString(problem.retryAfter()));
        return builder.body(Problems.create(request,problem.status(),problem.code(),problem.getMessage(),problem.status()>=500 || problem.status()==429));
    }

    @ExceptionHandler({SystemNotReadyException.class, DataAccessException.class, TransactionException.class})
    public ResponseEntity<ProblemDetail> notReady(Exception ex, HttpServletRequest request) {
        boolean authentication=request.getRequestURI().startsWith("/api/v1/auth/") || request.getRequestURI().startsWith("/api/v1/me/");
        return response(request,503,authentication?"AUTH_DEPENDENCY_UNAVAILABLE":"SYSTEM_NOT_READY","服务暂未就绪",true);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, ConstraintViolationException.class, ServletRequestBindingException.class})
    public ResponseEntity<ProblemDetail> badRequest(Exception ex, HttpServletRequest request) {
        return response(request, 400, "INVALID_REQUEST", "请求参数有误", false);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unknown(Exception ex, HttpServletRequest request) {
        return response(request, 500, "INTERNAL_ERROR", "服务暂时不可用", true);
    }
    private ResponseEntity<ProblemDetail> response(HttpServletRequest request, int status, String code, String title, boolean retryable) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Problems.create(request, status, code, title, retryable));
    }
}
