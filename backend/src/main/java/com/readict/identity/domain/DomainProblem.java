package com.readict.identity.domain;

public class DomainProblem extends RuntimeException {
    private final int status;
    private final String code;
    private final int retryAfter;
    public DomainProblem(int status, String code, String title) { this(status,code,title,0); }
    public DomainProblem(int status, String code, String title, int retryAfter) {
        super(title); this.status=status; this.code=code; this.retryAfter=retryAfter;
    }
    public int status() { return status; }
    public String code() { return code; }
    public int retryAfter() { return retryAfter; }
}
