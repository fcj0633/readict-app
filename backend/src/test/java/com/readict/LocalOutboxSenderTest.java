package com.readict;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.readict.identity.infrastructure.verification.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalOutboxSenderTest {
    @TempDir Path directory;
    @Test void outboxIsRestrictedBoundedAndExpiresWithoutExposingTarget() throws Exception {
        Clock clock=mock(Clock.class);Instant now=Instant.now();when(clock.instant()).thenReturn(now);
        var sender=new LocalOutboxSender(clock,directory.resolve("outbox"));UUID id=UUID.randomUUID();
        for(int i=0;i<105;i++) assertThat(sender.send(new VerificationSender.Message(id,"private@example.invalid","000001"))).isEqualTo(VerificationSender.Delivery.SENT);
        try(var files=Files.list(directory.resolve("outbox"))) {
            var all=files.toList();assertThat(all).hasSize(100);
            assertThat(all).allSatisfy(file -> {assertThat(file.getFileName().toString()).doesNotContain("private","000001");try {assertThat(Files.readString(file)).contains("proof=000001","mode=DEV_LOCAL").doesNotContain("private@example.invalid");}catch(java.io.IOException e){throw new AssertionError(e);}});
        }
        var acl=Files.getFileAttributeView(directory.resolve("outbox"),AclFileAttributeView.class);
        if(acl!=null)assertThat(acl.getAcl()).allSatisfy(entry -> assertThat(entry.principal()).isEqualTo(aclOwner(acl)));
        when(clock.instant()).thenReturn(now.plusSeconds(700));sender.cleanup();
        try(var files=Files.list(directory.resolve("outbox"))) {assertThat(files.count()).isZero();}
        assertThat(new VerificationSender.Message(id,"private@example.invalid","000001").toString()).doesNotContain("private","000001");
    }
    private UserPrincipal aclOwner(AclFileAttributeView acl) {try{return acl.getOwner();}catch(java.io.IOException ex){throw new AssertionError(ex);}}
}
