package com.readict.library.infrastructure.epub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Deterministic ZIP writer: stored mimetype first, sorted entries, fixed timestamps. */
public final class DeterministicZip {
    private static final LocalDateTime FIXED = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    private DeterministicZip() {}

    public static void write(Path target, byte[] mimetype, Map<String, byte[]> entries) {
        TreeMap<String, byte[]> sorted = new TreeMap<>(entries);
        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)), StandardCharsets.UTF_8)) {
            out.setLevel(6);
            put(out, "mimetype", mimetype, ZipEntry.STORED);
            for (Map.Entry<String, byte[]> entry : sorted.entrySet()) {
                if (entry.getKey().equals("mimetype")) continue;
                put(out, entry.getKey(), entry.getValue(), ZipEntry.DEFLATED);
            }
        } catch (IOException ex) {
            throw new ImportFailure("STORAGE_WRITE_FAILED", "阅读产物写入失败");
        }
    }

    private static void put(ZipOutputStream out, String name, byte[] data, int method) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(FIXED);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }
}
