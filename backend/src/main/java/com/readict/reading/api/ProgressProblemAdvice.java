package com.readict.reading.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.readict.reading.application.ProgressConflictException;
import com.readict.shared.web.Problems;

/** Progress conflicts carry the server version and anchor so the client can choose a side. */
@RestControllerAdvice(assignableTypes = ProgressController.class)
public class ProgressProblemAdvice {
    @ExceptionHandler(ProgressConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(ProgressConflictException error, HttpServletRequest request) {
        ProblemDetail detail = Problems.create(request, 409, "PROGRESS_CONFLICT", "阅读进度已被另一窗口更新", false);
        detail.setProperty("currentVersion", error.currentVersion());
        if (error.serverProgress() != null) detail.setProperty("serverProgress", error.serverProgress());
        return ResponseEntity.status(409).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }
}
