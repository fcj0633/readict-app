package com.readict.library.infrastructure.epub;

import com.readict.library.domain.EpubLimits;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.w3c.dom.*;

/**
 * Element/attribute allow-list sanitizer for XHTML and standalone SVG.
 * It rebuilds the document, injects a no-script CSP, drops every script/event/form/remote
 * construct and extracts the text blocks used by the immutable index.
 */
public final class XhtmlSanitizer {
    public static final String XHTML_NS = "http://www.w3.org/1999/xhtml";
    public static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";
    private static final String CSP = "default-src 'none'; img-src blob: data:; style-src 'unsafe-inline' blob:; "
            + "font-src blob: data:; script-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; "
            + "form-action 'none'; base-uri 'none'";

    private static final Set<String> ELEMENTS = Set.of(
            "html", "head", "title", "body", "div", "section", "article", "aside", "header", "footer",
            "nav", "p", "blockquote", "pre", "hr", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "dl", "dt", "dd", "span", "em", "strong", "b", "i", "u", "s", "small",
            "sub", "sup", "q", "cite", "code", "kbd", "samp", "var", "ruby", "rt", "rp", "a", "table",
            "caption", "colgroup", "col", "thead", "tbody", "tfoot", "tr", "th", "td", "img",
            "figure", "figcaption", "style", "link");
    private static final Set<String> SVG_ELEMENTS = Set.of(
            "svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text",
            "tspan", "defs", "symbol", "use");
    private static final Set<String> ATTRIBUTES = Set.of(
            "id", "class", "lang", "dir", "title", "alt", "src", "href", "rel", "colspan", "rowspan",
            "width", "height", "role", "viewBox", "d", "fill", "stroke", "stroke-width", "x", "y",
            "cx", "cy", "r", "rx", "ry", "x1", "y1", "x2", "y2", "points");
    private static final Set<String> BLOCKS = Set.of(
            "p", "div", "section", "article", "aside", "header", "footer", "nav", "h1", "h2", "h3",
            "h4", "h5", "h6", "blockquote", "pre", "li", "dt", "dd", "td", "th", "caption", "figcaption");

    public interface Resolver {
        /** Registers and allows a package-relative reference; kind is "css" or "image". */
        boolean allow(String href, String kind);
    }

    public record Block(String blockId, String text) {}

    public record Result(byte[] bytes, List<Block> blocks, int removedElements, int removedAttributes) {}

    private final EpubLimits limits;
    private final CssSanitizer css;

    public XhtmlSanitizer(EpubLimits limits, CssSanitizer css) {
        this.limits = limits;
        this.css = css;
    }

    public Result sanitize(byte[] source, String entryName, Resolver resolver) {
        Document input = XmlSupport.parse(source);
        Element sourceRoot = input.getDocumentElement();
        if (sourceRoot == null) throw new ImportFailure("EPUB_NO_CONTENT", "正文为空");
        Document output = XmlSupport.newDocument();
        int[] removed = {0, 0};
        boolean svg = "svg".equalsIgnoreCase(XmlSupport.local(sourceRoot));
        Element root;
        if (svg) {
            root = output.createElementNS(SVG_NS, "svg");
            root.setAttributeNS(XMLNS_NS, "xmlns", SVG_NS);
            output.appendChild(root);
            copyAttributes(sourceRoot, root, resolver, removed, true);
            copyChildren(sourceRoot, root, resolver, removed, true);
        } else {
            root = output.createElementNS(XHTML_NS, "html");
            root.setAttributeNS(XMLNS_NS, "xmlns", XHTML_NS);
            output.appendChild(root);
            Element head = output.createElementNS(XHTML_NS, "head");
            root.appendChild(head);
            String title = firstTitle(sourceRoot);
            if (!title.isEmpty()) {
                Element titleElement = output.createElementNS(XHTML_NS, "title");
                titleElement.setTextContent(title);
                head.appendChild(titleElement);
            }
            Element meta = output.createElementNS(XHTML_NS, "meta");
            meta.setAttribute("http-equiv", "Content-Security-Policy");
            meta.setAttribute("content", CSP);
            head.appendChild(meta);
            Element sourceHead = firstChild(sourceRoot, "head");
            if (sourceHead != null) copyHead(sourceHead, head, resolver, removed);
            Element sourceBody = firstChild(sourceRoot, "body");
            Element body = output.createElementNS(XHTML_NS, "body");
            root.appendChild(body);
            if (sourceBody != null) copyChildren(sourceBody, body, resolver, removed, false);
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + serialize(root);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxChapterBytes()) {
            throw new ImportFailure("EPUB_CHAPTER_TOO_LARGE", "单章内容超过处理上限");
        }
        List<Block> blocks = new ArrayList<>();
        if (!svg) {
            Element body = firstChild(root, "body");
            if (body != null) collectBlocks(body, blocks);
        }
        return new Result(bytes, blocks, removed[0], removed[1]);
    }

    private void collectBlocks(Element body, List<Block> blocks) {
        StringBuilder current = new StringBuilder();
        walk(body, blocks, current);
        flush(blocks, current);
    }

    private void walk(Node node, List<Block> blocks, StringBuilder current) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                current.append(child.getNodeValue());
                continue;
            }
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = XmlSupport.local(child);
            if (BLOCKS.contains(name)) {
                flush(blocks, current);
                walk(child, blocks, current);
                flush(blocks, current);
            } else if ("br".equals(name)) {
                current.append('\n');
            } else {
                walk(child, blocks, current);
            }
        }
    }

    private static void flush(List<Block> blocks, StringBuilder current) {
        if (current.length() == 0) return;
        blocks.add(new Block("b-" + (blocks.size() + 1), current.toString()));
        current.setLength(0);
    }

    /** Copies only stylesheet links and safe inline style blocks from the original head. */
    private void copyHead(Element sourceHead, Element head, Resolver resolver, int[] removed) {
        NodeList children = sourceHead.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) child;
            String name = XmlSupport.local(element);
            if ("link".equals(name)) {
                String rel = element.getAttribute("rel");
                String href = element.getAttribute("href");
                if (rel == null || !rel.toLowerCase(Locale.ROOT).contains("stylesheet")
                        || !resolver.allow(href, "css")) {
                    removed[0]++;
                    continue;
                }
                Element link = head.getOwnerDocument().createElementNS(XHTML_NS, "link");
                link.setAttribute("rel", "stylesheet");
                link.setAttribute("href", clean(href));
                head.appendChild(link);
            } else if ("style".equals(name)) {
                String sanitized = css.sanitize(element.getTextContent());
                if (sanitized.isEmpty()) { removed[0]++; continue; }
                Element style = head.getOwnerDocument().createElementNS(XHTML_NS, "style");
                style.setTextContent(sanitized);
                head.appendChild(style);
            }
        }
    }

    private void copyChildren(Element source, Element target, Resolver resolver, int[] removed, boolean svg) {
        NodeList children = source.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getNodeValue();
                if (text != null && !text.isEmpty()) target.appendChild(target.getOwnerDocument().createTextNode(text));
                continue;
            }
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) child;
            if (hidden(element)) { removed[0]++; continue; }
            String name = XmlSupport.local(element);
            boolean isSvg = svg || "svg".equals(name);
            if (!ELEMENTS.contains(name) && !(isSvg && SVG_ELEMENTS.contains(name))) { removed[0]++; continue; }
            if ("style".equals(name)) {
                String sanitized = css.sanitize(element.getTextContent());
                if (sanitized.isEmpty()) { removed[0]++; continue; }
                Element style = target.getOwnerDocument().createElementNS(target.getNamespaceURI(), "style");
                style.setTextContent(sanitized);
                target.appendChild(style);
                continue;
            }
            if ("link".equals(name)) {
                String rel = element.getAttribute("rel");
                String href = element.getAttribute("href");
                if (rel == null || !rel.toLowerCase(Locale.ROOT).contains("stylesheet") || !resolver.allow(href, "css")) {
                    removed[0]++;
                    continue;
                }
                Element link = target.getOwnerDocument().createElementNS(XHTML_NS, "link");
                link.setAttribute("rel", "stylesheet");
                link.setAttribute("href", clean(href));
                target.appendChild(link);
                continue;
            }
            Element copy = target.getOwnerDocument().createElementNS(
                    isSvg ? SVG_NS : XHTML_NS, name);
            copyAttributes(element, copy, resolver, removed, isSvg);
            target.appendChild(copy);
            copyChildren(element, copy, resolver, removed, isSvg);
        }
    }

    private void copyAttributes(Element source, Element target, Resolver resolver, int[] removed, boolean svg) {
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            String name = attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
            String namespace = attr.getNamespaceURI();
            if (XMLNS_NS.equals(namespace) || attr.getName().startsWith("xmlns")) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("on")) { removed[1]++; continue; }
            boolean link = "href".equals(lower) || "src".equals(lower);
            if (link) {
                String value = attr.getValue() == null ? "" : attr.getValue().trim();
                if (value.isEmpty() || value.startsWith("//") || hasScheme(value) || value.indexOf('\\') >= 0) {
                    removed[1]++;
                    continue;
                }
                if ("href".equals(lower) && value.startsWith("#")) {
                    target.setAttribute("href", clean(value));
                    continue;
                }
                boolean isImage = "src".equals(lower) || (svg && "href".equals(lower));
                if (!resolver.allow(value, isImage ? "image" : "css")) { removed[1]++; continue; }
                if (XLINK_NS.equals(namespace)) {
                    target.setAttributeNS(XLINK_NS, "xlink:href", clean(value));
                    target.setAttributeNS(XMLNS_NS, "xmlns:xlink", XLINK_NS);
                } else {
                    target.setAttribute(lower, clean(value));
                }
                continue;
            }
            if (!ATTRIBUTES.contains(name)) { removed[1]++; continue; }
            String value = attr.getValue();
            if (value == null) continue;
            if ("style".equals(lower)) {
                String inline = css.sanitizeInline(value);
                if (inline == null) { removed[1]++; continue; }
                target.setAttribute("style", inline);
                continue;
            }
            value = clean(value);
            if (value.length() > 4096) { removed[1]++; continue; }
            target.setAttribute(name, value);
        }
    }

    private static String clean(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\t' || ch == '\n' || ch == '\r' || ch >= 0x20) out.append(ch);
        }
        return out.toString().trim();
    }

    private static boolean hasScheme(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0) return false;
        for (int i = 0; i < colon; i++) {
            char ch = value.charAt(i);
            boolean ok = Character.isLetterOrDigit(ch) || ch == '+' || ch == '-' || ch == '.';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean hidden(Element element) {
        if (element.hasAttribute("hidden")) return true;
        String style = element.getAttribute("style");
        if (style == null) return false;
        String compact = style.replace(" ", "").toLowerCase(Locale.ROOT);
        return compact.contains("display:none") || compact.contains("visibility:hidden");
    }

    private static String firstTitle(Element root) {
        NodeList titles = root.getElementsByTagName("title");
        if (titles.getLength() == 0) return "";
        String title = XmlSupport.text((Element) titles.item(0));
        return title.length() > 512 ? title.substring(0, 512) : title;
    }

    private static Element firstChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equalsIgnoreCase(XmlSupport.local(child))) {
                return (Element) child;
            }
        }
        return null;
    }

    static String serialize(Element root) {
        StringBuilder out = new StringBuilder();
        writeNode(root, out);
        return out.toString();
    }

    private static void writeNode(Node node, StringBuilder out) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            escapeText(node.getNodeValue(), out);
            return;
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) return;
        Element element = (Element) node;
        String name = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
        out.append('<').append(name);
        NamedNodeMap attributes = element.getAttributes();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (XMLNS_NS.equals(attr.getNamespaceURI()) || attr.getName().startsWith("xmlns")) {
                names.add(attr.getName());
            }
        }
        List<String> sorted = new ArrayList<>();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (XMLNS_NS.equals(attr.getNamespaceURI()) || attr.getName().startsWith("xmlns")) continue;
            sorted.add(attr.getName());
        }
        Collections.sort(sorted);
        names.addAll(sorted);
        for (String attributeName : names) {
            Attr attr = namespaceAttribute(element, attributeName);
            if (attr == null) continue;
            out.append(' ').append(attr.getName()).append("=\"");
            escapeAttribute(attr.getValue() == null ? "" : attr.getValue(), out);
            out.append('"');
        }
        NodeList children = element.getChildNodes();
        if (children.getLength() == 0 && isVoid(name)) {
            out.append("/>");
            return;
        }
        if (children.getLength() == 0) {
            out.append("></").append(name).append('>');
            return;
        }
        out.append('>');
        for (int i = 0; i < children.getLength(); i++) writeNode(children.item(i), out);
        out.append("</").append(name).append('>');
    }

    private static Attr namespaceAttribute(Element element, String name) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            if (attr.getName().equals(name)) return attr;
        }
        return null;
    }

    private static boolean isVoid(String name) {
        return "br".equals(name) || "hr".equals(name) || "img".equals(name) || "col".equals(name)
                || "meta".equals(name) || "link".equals(name);
    }

    private static void escapeText(String value, StringBuilder out) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(ch);
            }
        }
    }

    private static void escapeAttribute(String value, StringBuilder out) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\n' -> out.append("&#10;");
                case '\r' -> out.append("&#13;");
                case '\t' -> out.append("&#9;");
                default -> out.append(ch);
            }
        }
    }
}
