package com.readict.library.infrastructure.epub;

/** A format or safety failure inside the import pipeline; carries the task error code. */
public class ImportFailure extends RuntimeException {
    private final String code;

    public ImportFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
