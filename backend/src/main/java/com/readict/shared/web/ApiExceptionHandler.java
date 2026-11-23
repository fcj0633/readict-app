package com.readict.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import jakarta.validation.ConstraintViolationException;
import com.readict.operations.application.SystemNotReadyException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.readict.identity.domain.DomainProblem.class)
    public ResponseEntity<ProblemDetail> domain(com.readict.identity.domain.DomainProblem problem,HttpServletRequest request) {
        return problem(request,problem.status(),problem.code(),problem.getMessage(),problem.retryAfter());
    }

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<ProblemDetail> api(ApiProblem problem,HttpServletRequest request) {
        return problem(request,problem.status(),problem.code(),problem.getMessage(),problem.retryAfter());
    }

    private ResponseEntity<ProblemDetail> problem(HttpServletRequest request,int status,String code,String title,int retryAfter) {
        var builder=ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if(retryAfter>0) builder.header("Retry-After",Integer.toString(retryAfter));
        return builder.body(Problems.create(request,status,code,title,status>=500 || status==429));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> uploadTooLarge(Exception ex,HttpServletRequest request) {
        return response(request,413,"UPLOAD_TOO_LARGE","文件超过 25 MB 上限",false);
    }

    @ExceptionHandler({SystemNotReadyException.class, DataAccessException.class, TransactionException.class})
    public ResponseEntity<ProblemDetail> notReady(Exception ex, HttpServletRequest request) {
        boolean authentication=request.getRequestURI().startsWith("/api/v1/auth/") || request.getRequestURI().startsWith("/api/v1/me/");
        return response(request,503,authentication?"AUTH_DEPENDENCY_UNAVAILABLE":"SYSTEM_NOT_READY","服务暂未就绪",true);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupported(Exception ex,HttpServletRequest request) {
        return response(request,415,"UNSUPPORTED_MEDIA_TYPE","不支持该内容类型",false);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> multipart(Exception ex,HttpServletRequest request) {
        return response(request,400,"INVALID_REQUEST","上传请求格式有误",false);
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
