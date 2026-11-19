package com.readict.operations.application;

public class SystemNotReadyException extends RuntimeException {
    public SystemNotReadyException() { super("SYSTEM_NOT_READY"); }
}
