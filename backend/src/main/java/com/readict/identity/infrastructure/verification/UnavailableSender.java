package com.readict.identity.infrastructure.verification;

public final class UnavailableSender implements VerificationSender {
    @Override public String mode() { return "UNAVAILABLE"; }
    @Override public Delivery send(Message message) { return Delivery.FAILED; }
}
