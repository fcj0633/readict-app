package com.readict.library.infrastructure.epub;

import com.readict.library.domain.EpubLimits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;

/**
 * Validates ZIP/OPF structure and reads package metadata with real, bounded decompression.
 * Every limit is enforced against bytes actually produced, never against declared sizes.
 */
public final class EpubReader {
    private static final String EPUB_NS = "http://www.idpf.org/2007/ops";

    private final EpubLimits limits;

    public EpubReader(EpubLimits limits) { this.limits = limits; }

    public EpubPackage read(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            List<ZipEntry> entries = new ArrayList<>(Collections.list(zip.entries()));
            if (entries.isEmpty()) throw new ImportFailure("EPUB_INVALID_ZIP", "不是有效的 EPUB 压缩包");
            if (entries.size() > limits.maxEntries()) {
                throw new ImportFailure("EPUB_ENTRY_LIMIT", "压缩包条目数量超过上限");
            }
            Set<String> names = new HashSet<>();
            for (ZipEntry entry : entries) {
                validateName(entry.getName());
                if (!names.add(entry.getName())) throw new ImportFailure("EPUB_INVALID_ZIP", "压缩包存在重复路径");
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip") || lower.endsWith(".epub")) {
                    throw new ImportFailure("EPUB_INVALID_ZIP", "压缩包内不允许嵌套归档");
                }
                if (lower.equals("meta-inf/encryption.xml")) {
                    throw new ImportFailure("EPUB_ENCRYPTED", "该书包含加密内容，暂不支持导入");
                }
            }
            ZipEntry first = entries.get(0);
            if (!"mimetype".equals(first.getName()) || first.getMethod() != ZipEntry.STORED) {
                throw new ImportFailure("EPUB_INVALID_MIMETYPE", "不是有效的 EPUB 文件");
            }
            byte[] mimetype = readEntry(zip, first, 64, "EPUB_INVALID_MIMETYPE");
            if (!"application/epub+zip".equals(new String(mimetype, StandardCharsets.ISO_8859_1))) {
                throw new ImportFailure("EPUB_INVALID_MIMETYPE", "不是有效的 EPUB 文件");
            }
            long total = 0;
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) continue;
                total = consume(zip, entry, total);
            }
            ZipEntry containerEntry = zip.getEntry("META-INF/container.xml");
            if (containerEntry == null) throw new ImportFailure("EPUB_INVALID_CONTAINER", "缺少容器描述文件");
            String opfPath = containerPath(readEntry(zip, containerEntry, 1024 * 1024, "EPUB_INVALID_CONTAINER"));
            ZipEntry opfEntry = zip.getEntry(opfPath);
            if (opfEntry == null) throw new ImportFailure("EPUB_INVALID_CONTAINER", "找不到内容清单");
            Document opf = XmlSupport.parse(readEntry(zip, opfEntry, limits.maxEntryBytes(), "EPUB_INVALID_OPF"));
            Element packageElement = opf.getDocumentElement();
            if (packageElement == null || !"package".equalsIgnoreCase(XmlSupport.local(packageElement))) {
                throw new ImportFailure("EPUB_INVALID_OPF", "内容清单格式不正确");
            }
            rejectFixedLayout(opf);
            Map<String, Item> manifest = manifest(opf, opfPath);
            List<SpineRef> spineRefs = spine(opf);
            if (spineRefs.isEmpty()) throw new ImportFailure("EPUB_NO_CONTENT", "该书没有可阅读正文");
            if (spineRefs.size() > limits.maxSpineItems()) {
                throw new ImportFailure("EPUB_SPINE_TOO_LARGE", "章节数量超过上限");
            }
            TocResult toc = toc(opf, manifest, spineRefs, zip, opfPath);
            Map<String, String> labels = toc.labels();
            List<EpubPackage.SpineItem> spine = new ArrayList<>();
            for (int i = 0; i < spineRefs.size(); i++) {
                SpineRef ref = spineRefs.get(i);
                Item item = manifest.get(ref.idref());
                if (item == null) throw new ImportFailure("EPUB_INVALID_SPINE", "阅读顺序引用了不存在的资源");
                String entryName = resolve(opfPath, item.href());
                if (entryName == null || !names.contains(entryName)) {
                    throw new ImportFailure("EPUB_INVALID_SPINE", "阅读顺序引用了缺失的资源");
                }
                String title = labels.get(entryName);
                if (title == null || title.isBlank()) title = "Chapter " + (i + 1);
                spine.add(new EpubPackage.SpineItem(item.href(), entryName, item.mediaType(), title));
            }
            EpubPackage.Cover cover = cover(opf, manifest, zip, names, opfPath);
            Map<String, String> mediaTypes = new LinkedHashMap<>();
            for (Item item : manifest.values()) {
                String entryName = resolve(opfPath, item.href());
                if (entryName != null) mediaTypes.put(entryName, item.mediaType());
            }
            return new EpubPackage(title(opf), author(opf), language(opf), opfPath, toc.source(),
                    spine, toc.nodes(), cover, mediaTypes);
        } catch (IOException ex) {
            throw new ImportFailure("EPUB_INVALID_ZIP", "无法读取该 EPUB 文件");
        }
    }

    private record Item(String id, String href, String mediaType, String properties) {}

    private record SpineRef(String idref) {}

    private record TocResult(List<EpubPackage.TocNode> nodes, Map<String, String> labels, String source) {}

    private void validateName(String name) {
        if (name == null || name.isEmpty()) throw new ImportFailure("EPUB_PATH_TRAVERSAL", "压缩包路径不合法");
        if (name.startsWith("/") || name.contains("\\") || name.indexOf(':') >= 0) {
            throw new ImportFailure("EPUB_PATH_TRAVERSAL", "压缩包路径不合法");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > limits.maxPathBytes()) {
            throw new ImportFailure("EPUB_PATH_TOO_LONG", "资源路径过长");
        }
        String[] segments = name.split("/");
        if (segments.length > limits.maxDepth()) throw new ImportFailure("EPUB_TOO_DEEP", "目录层级过深");
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ImportFailure("EPUB_PATH_TRAVERSAL", "压缩包路径不合法");
            }
        }
    }

    private long consume(ZipFile zip, ZipEntry entry, long total) {
        long size = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                size += read;
                total += read;
                if (size > limits.maxEntryBytes()) {
                    throw new ImportFailure("EPUB_ENTRY_TOO_LARGE", "单个资源解压后超过上限");
                }
                if (total > limits.maxTotalBytes()) {
                    throw new ImportFailure("EPUB_TOTAL_TOO_LARGE", "解压总量超过上限");
                }
            }
        } catch (IOException ex) {
            throw new ImportFailure("EPUB_INVALID_ZIP", "压缩包内容损坏");
        }
        return total;
    }

    private byte[] readEntry(ZipFile zip, ZipEntry entry, long limit, String code) {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            long size = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                size += read;
                if (size > limit) throw new ImportFailure(code, "资源超过处理上限");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new ImportFailure("EPUB_INVALID_ZIP", "压缩包内容损坏");
        }
    }

    private String containerPath(byte[] container) {
        Document doc = XmlSupport.parse(container);
        for (Element rootfile : elements(doc.getDocumentElement(), "rootfile")) {
            String path = localAttribute(rootfile, "full-path");
            if (path != null && !path.isBlank()) {
                String resolved = resolve("container.xml", path);
                if (resolved == null) throw new ImportFailure("EPUB_INVALID_CONTAINER", "容器路径不合法");
                return resolved;
            }
        }
        throw new ImportFailure("EPUB_INVALID_CONTAINER", "缺少内容清单路径");
    }

    private void rejectFixedLayout(Document opf) {
        for (Element meta : elements(opf.getDocumentElement(), "meta")) {
            String property = localAttribute(meta, "property");
            String value = localAttribute(meta, "content");
            String text = XmlSupport.text(meta);
            String combined = (property == null ? "" : property) + " " + (value == null ? "" : value) + " " + text;
            if (combined.toLowerCase(Locale.ROOT).contains("rendition:layout")
                    && combined.toLowerCase(Locale.ROOT).contains("pre-paginated")) {
                throw new ImportFailure("EPUB_FIXED_LAYOUT", "暂不支持固定版式 EPUB");
            }
        }
    }

    private Map<String, Item> manifest(Document opf, String opfPath) {
        Map<String, Item> items = new LinkedHashMap<>();
        for (Element element : elements(opf.getDocumentElement(), "item")) {
            String id = localAttribute(element, "id");
            String href = localAttribute(element, "href");
            if (id == null || href == null) continue;
            items.put(id, new Item(id, href, attribute(element, "media-type"), attribute(element, "properties")));
        }
        if (items.isEmpty()) throw new ImportFailure("EPUB_INVALID_OPF", "内容清单为空");
        return items;
    }

    private List<SpineRef> spine(Document opf) {
        List<SpineRef> refs = new ArrayList<>();
        for (Element spine : elements(opf.getDocumentElement(), "spine")) {
            for (Element itemref : elements(spine, "itemref")) {
                String idref = localAttribute(itemref, "idref");
                if (idref != null) refs.add(new SpineRef(idref));
            }
            break;
        }
        return refs;
    }

    private TocResult toc(Document opf, Map<String, Item> manifest, List<SpineRef> spine, ZipFile zip, String opfPath) {
        for (Item item : manifest.values()) {
            if (item.properties() != null && item.properties().toLowerCase(Locale.ROOT).contains("nav")) {
                String entryName = resolve(opfPath, item.href());
                ZipEntry entry = entryName == null ? null : zip.getEntry(entryName);
                if (entry != null) {
                    TocResult result = readNav(readEntry(zip, entry, limits.maxEntryBytes(), "EPUB_INVALID_OPF"), entryName);
                    if (!result.nodes().isEmpty() || result.labels().isEmpty()) {
                        if (!result.nodes().isEmpty()) return result;
                    }
                }
            }
        }
        for (Element spineElement : elements(opf.getDocumentElement(), "spine")) {
            String tocId = localAttribute(spineElement, "toc");
            Item item = tocId == null ? null : manifest.get(tocId);
            if (item != null) {
                String entryName = resolve(opfPath, item.href());
                ZipEntry entry = entryName == null ? null : zip.getEntry(entryName);
                if (entry != null) {
                    TocResult result = readNcx(readEntry(zip, entry, limits.maxEntryBytes(), "EPUB_INVALID_OPF"), entryName);
                    if (!result.nodes().isEmpty()) return result;
                }
            }
            break;
        }
        return new TocResult(List.of(), Map.of(), "SPINE");
    }

    private TocResult readNav(byte[] bytes, String entryName) {
        Document doc = XmlSupport.parse(bytes);
        Element chosen = null;
        for (Element nav : elements(doc.getDocumentElement(), "nav")) {
            String type = nav.getAttributeNS(EPUB_NS, "type");
            if (type == null || type.isBlank()) type = nav.getAttribute("epub:type");
            if (type != null && type.toLowerCase(Locale.ROOT).contains("toc")) { chosen = nav; break; }
            if (chosen == null) chosen = nav;
        }
        if (chosen == null) return new TocResult(List.of(), Map.of(), "NAV");
        List<EpubPackage.TocNode> nodes = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Element anchor : elements(chosen, "a")) {
            String label = XmlSupport.text(anchor);
            String href = localAttribute(anchor, "href");
            if (href == null || label.isEmpty()) continue;
            String target = resolve(entryName, href);
            if (target == null) continue;
            nodes.add(new EpubPackage.TocNode(label, href, List.of()));
            labels.putIfAbsent(target, label);
        }
        return new TocResult(nodes, labels, "NAV");
    }

    private TocResult readNcx(byte[] bytes, String entryName) {
        Document doc = XmlSupport.parse(bytes);
        List<EpubPackage.TocNode> nodes = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Element navPoint : elements(doc.getDocumentElement(), "navPoint")) {
            String label = "";
            for (Element navLabel : elements(navPoint, "navLabel")) {
                label = XmlSupport.text(navLabel);
                break;
            }
            String src = null;
            for (Element content : elements(navPoint, "content")) {
                src = localAttribute(content, "src");
                break;
            }
            if (src == null || label.isEmpty()) continue;
            String target = resolve(entryName, src);
            if (target == null) continue;
            nodes.add(new EpubPackage.TocNode(label, src, List.of()));
            labels.putIfAbsent(target, label);
        }
        return new TocResult(nodes, labels, "NCX");
    }

    private EpubPackage.Cover cover(Document opf, Map<String, Item> manifest, ZipFile zip,
                                    Set<String> names, String opfPath) {
        String coverId = null;
        for (Element meta : elements(opf.getDocumentElement(), "meta")) {
            if ("cover".equalsIgnoreCase(attribute(meta, "name"))) {
                coverId = attribute(meta, "content");
                break;
            }
        }
        Item item = null;
        if (coverId != null) item = manifest.get(coverId);
        if (item == null) {
            for (Item candidate : manifest.values()) {
                if (candidate.properties() != null
                        && candidate.properties().toLowerCase(Locale.ROOT).contains("cover-image")) {
                    item = candidate;
                    break;
                }
            }
        }
        if (item == null) return null;
        String entryName = resolve(opfPath, item.href());
        if (entryName == null || !names.contains(entryName)) return null;
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) return null;
        byte[] bytes;
        try {
            bytes = readEntry(zip, entry, limits.maxCoverBytes(), "EPUB_ENTRY_TOO_LARGE");
        } catch (ImportFailure failure) {
            return null;
        }
        String mime = imageMime(bytes);
        if (mime == null) return null;
        int[] size = imageSize(bytes, mime);
        if (size == null || size[0] > limits.maxCoverPixels() || size[1] > limits.maxCoverPixels()) return null;
        return new EpubPackage.Cover(bytes, mime);
    }

    static String imageMime(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    private static int[] imageSize(byte[] bytes, String mime) {
        try {
            if ("image/png".equals(mime) && bytes.length >= 24) {
                int width = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16) | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
                int height = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16) | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
                return new int[]{width, height};
            }
            if ("image/jpeg".equals(mime)) {
                int index = 2;
                while (index + 9 < bytes.length) {
                    if ((bytes[index] & 0xFF) != 0xFF) { index++; continue; }
                    int marker = bytes[index + 1] & 0xFF;
                    if (marker == 0xD9 || marker == 0xDA) return null;
                    int length = ((bytes[index + 2] & 0xFF) << 8) | (bytes[index + 3] & 0xFF);
                    if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                        int height = ((bytes[index + 5] & 0xFF) << 8) | (bytes[index + 6] & 0xFF);
                        int width = ((bytes[index + 7] & 0xFF) << 8) | (bytes[index + 8] & 0xFF);
                        return new int[]{width, height};
                    }
                    index += 2 + length;
                }
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String title(Document opf) { return metadata(opf, "title", "未命名书籍", 512); }

    private static String author(Document opf) { return metadata(opf, "creator", null, 512); }

    private static String language(Document opf) { return metadata(opf, "language", "en", 16); }

    private static String metadata(Document opf, String name, String fallback, int max) {
        for (Element element : elements(opf.getDocumentElement(), name)) {
            String text = XmlSupport.text(element);
            if (!text.isEmpty()) return text.length() > max ? text.substring(0, max) : text;
        }
        return fallback;
    }

    private static String localAttribute(Element element, String name) {
        Attr direct = element.getAttributeNode(name);
        if (direct != null) return direct.getValue();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);
            String local = attr.getLocalName() != null ? attr.getLocalName() : attr.getName();
            if (local.equalsIgnoreCase(name)) return attr.getValue();
        }
        return null;
    }

    private static String attribute(Element element, String name) {
        String value = localAttribute(element, name);
        return value == null ? "" : value;
    }

    static List<Element> elements(Element root, String localName) {
        List<Element> result = new ArrayList<>();
        collect(root, localName, result);
        return result;
    }

    private static void collect(Node node, String localName, List<Element> result) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (localName.equalsIgnoreCase(XmlSupport.local(child))) result.add((Element) child);
            collect(child, localName, result);
        }
    }

    static String resolve(String basePath, String href) {
        if (href == null) return null;
        String clean = href;
        int hash = clean.indexOf('#');
        if (hash >= 0) clean = clean.substring(0, hash);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        clean = decode(clean).trim();
        if (clean.isEmpty() || clean.startsWith("/")) return null;
        if (clean.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) return null;
        String baseDir = basePath.contains("/") ? basePath.substring(0, basePath.lastIndexOf('/')) : "";
        String combined = baseDir.isEmpty() ? clean : baseDir + "/" + clean;
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : combined.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (stack.isEmpty()) return null;
                stack.removeLast();
            } else {
                stack.addLast(segment);
            }
        }
        return String.join("/", stack);
    }

    private static String decode(String value) {
        if (value.indexOf('%') < 0) return value;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
