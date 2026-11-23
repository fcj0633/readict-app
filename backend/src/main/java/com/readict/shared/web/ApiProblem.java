package com.readict.shared.web;

/** Shared business error carrying HTTP status and problem code; usable by every module. */
public class ApiProblem extends RuntimeException {
    private final int status;
    private final String code;
    private final int retryAfter;

    public ApiProblem(int status, String code, String title) { this(status, code, title, 0); }

    public ApiProblem(int status, String code, String title, int retryAfter) {
        super(title);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public int status() { return status; }
    public String code() { return code; }
    public int retryAfter() { return retryAfter; }
}
