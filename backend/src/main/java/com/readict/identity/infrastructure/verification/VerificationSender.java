package com.readict.identity.infrastructure.verification;

import java.util.UUID;

public interface VerificationSender {
    record Message(UUID challengeId,String target,String proof) {
        @Override public String toString() { return "VerificationMessage[redacted]"; }
    }
    enum Delivery { SENT, FAILED, UNKNOWN }
    String mode();
    Delivery send(Message message);
}
