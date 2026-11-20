package com.readict.identity.infrastructure.verification;

import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;

/** Sensitive development files, never exposed through HTTP or ordinary logs. */
public final class LocalOutboxSender implements VerificationSender {
    private final Path directory;
    private final Clock clock;
    public LocalOutboxSender(Clock clock) { this(clock,localDirectory()); }
    private static Path localDirectory() {
        Path cwd=Path.of("").toAbsolutePath();
        Path app=cwd.getFileName().toString().equals("backend")?cwd.getParent():cwd;
        return app.resolve(".local/verification-outbox").normalize();
    }
    public LocalOutboxSender(Clock clock,Path directory) {
        this.clock=clock;this.directory=directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory) || Files.isSymbolicLink(directory.getParent())) throw new IllegalStateException("本地 outbox 路径不允许符号链接");
            AclFileAttributeView acl=Files.getFileAttributeView(directory,AclFileAttributeView.class);
            if(acl!=null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build()));
            else Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        } catch(java.io.IOException ex) { throw new IllegalStateException("本地验证目录不可用"); }
    }
    @Override public String mode() { return "DEV_LOCAL"; }
    @Override public synchronized Delivery send(Message message) {
        cleanup();
        try {
            String contents="challengeId="+message.challengeId()+"\nproof="+message.proof()+"\nmode=DEV_LOCAL\n";
            Files.writeString(directory.resolve(UUID.randomUUID()+".txt"),contents,StandardOpenOption.CREATE_NEW);
            return Delivery.SENT;
        } catch(java.io.IOException ex) { return Delivery.FAILED; }
    }
    public synchronized void cleanup() {
        try(var stream=Files.list(directory)) {
            var files=stream.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            int surplus=Math.max(0,files.size()-99);
            for(Path p:files) if(surplus-->0 || Files.getLastModifiedTime(p).toInstant().isBefore(clock.instant().minusSeconds(600))) Files.deleteIfExists(p);
        } catch(java.io.IOException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("code=OUTBOX_CLEANUP_RETRY"); }
    }
}
