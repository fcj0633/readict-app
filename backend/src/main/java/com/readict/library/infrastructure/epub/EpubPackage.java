package com.readict.library.infrastructure.epub;

import java.util.List;
import java.util.Map;

/** Parsed and validated EPUB package metadata needed by the sanitizer and the writer. */
public record EpubPackage(
        String title,
        String author,
        String language,
        String opfPath,
        String tocSource,
        List<SpineItem> spine,
        List<TocNode> toc,
        Cover cover,
        Map<String, String> mediaTypes) {

    public record SpineItem(String href, String entryName, String mediaType, String title) {}

    public record TocNode(String label, String href, List<TocNode> children) {}

    public record Cover(byte[] bytes, String mime) {}
}
