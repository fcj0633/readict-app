package com.readict.library.infrastructure.epub;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.BreakIterator;
import java.util.*;

/** Builds deterministic per-chapter block and sentence ranges (segment-rules-v1). */
public final class TextIndexer {
    public static final String SCHEMA = "readict-text-index-1";
    public static final String SEGMENT_RULES = "segment-rules-v1";
    private static final List<String> ABBREVIATIONS = List.of(
            "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.", "vs.", "etc.", "e.g.", "i.e.", "no.");

    public record Sentence(int start, int end, String kind) {}

    public record BlockIndex(String blockId, String text, int utf16Length, String textHash, List<Sentence> sentences) {}

    public List<BlockIndex> index(List<XhtmlSanitizer.Block> blocks) {
        List<BlockIndex> result = new ArrayList<>(blocks.size());
        for (XhtmlSanitizer.Block block : blocks) {
            String text = block.text();
            result.add(new BlockIndex(block.blockId(), text, text.length(), hash(text), sentences(text)));
        }
        return result;
    }

    static List<Sentence> sentences(String text) {
        if (text.isEmpty()) return List.of();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);
        List<Sentence> result = new ArrayList<>();
        int start = iterator.first();
        int end;
        while ((end = iterator.next()) != BreakIterator.DONE) {
            if (end < text.length() && endsWithAbbreviation(text, end)) continue;
            if (end <= start) { start = end; continue; }
            result.add(new Sentence(start, end, kind(text, start, end, end == text.length())));
            start = end;
        }
        if (start < text.length()) result.add(new Sentence(start, text.length(), "FRAGMENT"));
        return result;
    }

    private static boolean endsWithAbbreviation(String text, int end) {
        int from = Math.max(0, end - 8);
        String tail = text.substring(from, end).trim().toLowerCase(Locale.ROOT);
        for (String abbreviation : ABBREVIATIONS) {
            if (tail.endsWith(abbreviation)) return true;
        }
        return false;
    }

    private static String kind(String text, int start, int end, boolean last) {
        String sentence = text.substring(start, end).trim();
        if (sentence.isEmpty()) return "FRAGMENT";
        char lastChar = sentence.charAt(sentence.length() - 1);
        boolean terminal = lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == '…'
                || lastChar == '"' || lastChar == '\'' || lastChar == '”' || lastChar == '’'
                || lastChar == ')' || lastChar == ']';
        return terminal ? "SENTENCE" : "FRAGMENT";
    }

    public static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        return out.toString();
    }
}
