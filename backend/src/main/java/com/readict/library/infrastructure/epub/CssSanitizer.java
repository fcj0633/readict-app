package com.readict.library.infrastructure.epub;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.helger.css.ECSSVersion;
import com.helger.css.decl.*;
import com.helger.css.reader.CSSReader;
import com.helger.css.writer.CSSWriterSettings;

/**
 * CSS allow-list rewriting. The stylesheet is parsed into a syntax tree; only whitelisted
 * properties survive and any URL, import, font, animation or positioning construct is dropped.
 * Output is canonical so identical input always produces identical bytes.
 */
public final class CssSanitizer {
    private static final Set<String> PROPERTIES = Set.of(
            "color", "background-color", "font-family", "font-size", "font-style", "font-weight",
            "font-variant", "line-height", "letter-spacing", "word-spacing", "text-align",
            "text-indent", "text-decoration", "text-transform", "white-space", "vertical-align",
            "list-style-type", "list-style-position", "margin", "margin-top", "margin-right",
            "margin-bottom", "margin-left", "padding", "padding-top", "padding-right",
            "padding-bottom", "padding-left", "border", "border-top", "border-right",
            "border-bottom", "border-left", "border-color", "border-style", "border-width",
            "border-radius", "width", "max-width", "height", "max-height", "display", "float",
            "clear", "page-break-before", "page-break-after", "page-break-inside", "break-before",
            "break-after", "break-inside");
    private static final Set<String> FONTS = Set.of(
            "georgia", "palatino", "palatino linotype", "arial", "serif", "sans-serif", "monospace",
            "times", "times new roman", "helvetica", "verdana", "garamond", "book antiqua",
            "inherit", "initial");
    private static final Set<String> DISPLAY = Set.of(
            "block", "inline", "inline-block", "none", "list-item", "table", "table-row",
            "table-cell", "table-header-group", "table-footer-group", "table-row-group",
            "table-column", "table-column-group", "inline-table", "inherit");
    private static final List<String> FORBIDDEN = List.of("url(", "expression", "var(", "calc(", "\\", "javascript:");
    private final CSSWriterSettings writer = new CSSWriterSettings(ECSSVersion.CSS30, false);

    public String sanitize(String css) {
        if (css == null || css.isBlank()) return "";
        CascadingStyleSheet sheet;
        try {
            sheet = CSSReader.readFromString(css, StandardCharsets.UTF_8, ECSSVersion.CSS30);
        } catch (RuntimeException ex) {
            return "";
        }
        if (sheet == null) return "";
        StringBuilder out = new StringBuilder();
        for (ICSSTopLevelRule rule : sheet.getAllRules()) {
            if (rule instanceof CSSStyleRule style) writeStyle(style, out, 0);
            else if (rule instanceof CSSMediaRule media) writeMedia(media, out);
        }
        return out.toString();
    }

    private void writeMedia(CSSMediaRule media, StringBuilder out) {
        List<String> queries = new ArrayList<>();
        for (CSSMediaQuery query : media.getAllMediaQueries()) {
            String text = query.getAsCSSString(writer, 0);
            if (text != null && !text.isBlank()) queries.add(text.trim());
        }
        if (queries.isEmpty()) return;
        String joined = String.join(",", queries);
        String lower = joined.toLowerCase(Locale.ROOT);
        if (!(lower.contains("screen") || lower.contains("all") || lower.startsWith("("))) return;
        StringBuilder inner = new StringBuilder();
        for (ICSSTopLevelRule rule : media.getAllRules()) {
            if (rule instanceof CSSStyleRule style) writeStyle(style, inner, 1);
        }
        if (inner.length() == 0) return;
        out.append("@media ").append(joined).append('{').append(inner).append('}');
    }

    private void writeStyle(CSSStyleRule rule, StringBuilder out, int depth) {
        List<String> selectors = new ArrayList<>();
        for (CSSSelector selector : rule.getAllSelectors()) {
            String text = selector.getAsCSSString(writer, depth);
            if (text == null) continue;
            text = text.trim();
            if (text.isEmpty()) continue;
            if (text.indexOf('{') >= 0 || text.indexOf('}') >= 0 || text.indexOf('<') >= 0) continue;
            selectors.add(text);
        }
        if (selectors.isEmpty()) return;
        StringBuilder declarations = new StringBuilder();
        for (CSSDeclaration declaration : rule.getAllDeclarations()) {
            String property = declaration.getProperty();
            if (property == null) continue;
            property = property.toLowerCase(Locale.ROOT).trim();
            if (!PROPERTIES.contains(property)) continue;
            String value = declaration.getExpressionAsCSSString();
            if (value == null) continue;
            value = value.trim();
            if (value.isEmpty() || !allowedValue(property, value)) continue;
            declarations.append(property).append(':').append(value);
            if (declaration.isImportant()) declarations.append("!important");
            declarations.append(';');
        }
        if (declarations.length() == 0) return;
        out.append(String.join(",", selectors)).append('{').append(declarations).append('}');
    }

    private static boolean allowedValue(String property, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN) {
            if (lower.contains(token)) return false;
        }
        if (property.equals("display")) return DISPLAY.contains(lower);
        if (property.equals("float")) return lower.equals("left") || lower.equals("right") || lower.equals("none");
        if (property.equals("clear")) return lower.equals("left") || lower.equals("right") || lower.equals("both") || lower.equals("none");
        if (property.equals("font-family")) {
            for (String part : lower.split(",")) {
                String family = part.trim().replace("'", "").replace("\"", "");
                if (family.isEmpty() || !FONTS.contains(family)) return false;
            }
        }
        return value.matches("[A-Za-z0-9#.,%()\\-\\s\"'/!]+");
    }

    /** Sanitizes an inline {@code style} attribute; returns null when nothing safe remains. */
    public String sanitizeInline(String style) {
        if (style == null || style.isBlank()) return null;
        StringBuilder out = new StringBuilder();
        for (String raw : style.split(";")) {
            int colon = raw.indexOf(':');
            if (colon <= 0) continue;
            String property = raw.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = raw.substring(colon + 1).trim();
            if (!PROPERTIES.contains(property) || value.isEmpty() || !allowedValue(property, value)) continue;
            out.append(property).append(':').append(value).append(';');
        }
        return out.length() == 0 ? null : out.toString();
    }
}
