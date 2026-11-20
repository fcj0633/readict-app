package com.readict.shared.web;

import java.io.IOException;
import java.net.URI;
import jakarta.servlet.http.*;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class Problems {
    private final ObjectMapper objectMapper;
    public Problems(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    public static ProblemDetail create(HttpServletRequest request, int status, String code, String title, boolean retryable) {
        request.setAttribute("errorCode", code);
        var detail = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status), retryable ? "请稍后重试。" : "请检查请求后重试。");
        detail.setType(URI.create("urn:readict:error:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        detail.setTitle(title);
        detail.setProperty("code", code);
        detail.setProperty("requestId", RequestIdFilter.id(request));
        detail.setProperty("retryable", retryable);
        return detail;
    }
    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String title) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), create(request, status, code, title, status >= 500));
    }
}
