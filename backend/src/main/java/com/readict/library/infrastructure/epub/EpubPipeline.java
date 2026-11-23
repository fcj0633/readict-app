package com.readict.library.infrastructure.epub;

import com.readict.library.domain.EpubLimits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;

/**
 * Deterministic pipeline: validate, sanitize every spine document, close the resource set,
 * rebuild the package, and write reader.epub / index.json / manifest.json / cover into a
 * staging directory. The caller publishes the directory atomically and only then commits READY.
 */
public final class EpubPipeline {
    public static final String PIPELINE_VERSION = "epub-safe-v1";
    private static final Set<String> RASTER = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    public record Chapter(int spineIndex, String entryName, String href, String title, long textLength,
                          long cumulative, List<TextIndexer.BlockIndex> blocks) {}

    public record Built(Path directory, byte[] artifactHash, long totalUtf16, byte[] coverHash,
                        String coverMime, String title, String author, List<Chapter> chapters) {}

    private final EpubLimits limits;
    private final ObjectMapper json;
    private final CssSanitizer css = new CssSanitizer();
    private final TextIndexer indexer = new TextIndexer();
    private final XhtmlSanitizer sanitizer;

    public EpubPipeline(EpubLimits limits, ObjectMapper json) {
        this.limits = limits;
        this.json = json;
        this.sanitizer = new XhtmlSanitizer(limits, css);
    }

    public Built build(Path source, Path outDir, String sourceHashHex, Instant deadline) {
        EpubPackage epub = new EpubReader(limits).read(source);
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            Session session = new Session(zip, epub, sourceHashHex);
            List<Chapter> chapters = new ArrayList<>();
            long cumulative = 0;
            for (int i = 0; i < epub.spine().size(); i++) {
                if (deadline != null && Instant.now().isAfter(deadline)) {
                    throw new ImportFailure("IMPORT_TIMEOUT", "导入时间超出限制");
                }
                EpubPackage.SpineItem item = epub.spine().get(i);
                byte[] raw = session.read(item.entryName());
                session.enter(item.entryName());
                XhtmlSanitizer.Result sanitized = sanitizer.sanitize(raw, item.entryName(), session);
                session.files.put(item.entryName(), sanitized.bytes());
                List<TextIndexer.BlockIndex> blocks = indexer.index(sanitized.blocks());
                long length = blocks.stream().mapToLong(TextIndexer.BlockIndex::utf16Length).sum();
                cumulative += length;
                chapters.add(new Chapter(i, item.entryName(), item.entryName(), item.title(), length, cumulative,
                        List.copyOf(blocks)));
            }
            session.finish(chapters);
            Files.createDirectories(outDir);
            Path epubFile = outDir.resolve("reader.epub");
            DeterministicZip.write(epubFile, "application/epub+zip".getBytes(StandardCharsets.ISO_8859_1),
                    session.files);
            if (Files.size(epubFile) > limits.maxArtifactBytes()) {
                throw new ImportFailure("EPUB_ARTIFACT_TOO_LARGE", "阅读产物超过上限");
            }
            byte[] hash = hashFile(epubFile);
            String artifactHash = TextIndexer.hex(hash);
            long totalUtf16 = chapters.stream().mapToLong(Chapter::textLength).sum();
            writeIndex(outDir.resolve("index.json"), artifactHash, chapters);
            writeManifest(outDir.resolve("manifest.json"), artifactHash, sourceHashHex, epub, chapters);
            byte[] coverHash = null;
            String coverMime = null;
            if (epub.cover() != null) {
                coverMime = epub.cover().mime();
                String name = "image/png".equals(coverMime) ? "cover.png" : "cover.jpg";
                Files.write(outDir.resolve(name), epub.cover().bytes());
                coverHash = sha256().digest(epub.cover().bytes());
            }
            return new Built(outDir, hash, totalUtf16, coverHash, coverMime, epub.title(), epub.author(),
                    List.copyOf(chapters));
        } catch (IOException ex) {
            throw new ImportFailure("STORAGE_WRITE_FAILED", "阅读产物写入失败");
        }
    }

    private void writeIndex(Path target, String artifactHash, List<Chapter> chapters) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", TextIndexer.SCHEMA);
        root.put("pipelineVersion", PIPELINE_VERSION);
        root.put("segmentRules", TextIndexer.SEGMENT_RULES);
        root.put("artifactHash", artifactHash);
        ArrayNode nodes = root.putArray("chapters");
        for (Chapter chapter : chapters) {
            ObjectNode node = nodes.addObject();
            node.put("spineHref", chapter.href());
            node.put("spineIndex", chapter.spineIndex());
            node.put("title", chapter.title());
            node.put("totalUtf16", chapter.textLength());
            ArrayNode blocks = node.putArray("blocks");
            for (TextIndexer.BlockIndex block : chapter.blocks()) {
                ObjectNode blockNode = blocks.addObject();
                blockNode.put("blockId", block.blockId());
                blockNode.put("text", block.text());
                blockNode.put("utf16Length", block.utf16Length());
                blockNode.put("textHash", block.textHash());
                ArrayNode sentences = blockNode.putArray("sentences");
                for (TextIndexer.Sentence sentence : block.sentences()) {
                    ObjectNode sentenceNode = sentences.addObject();
                    sentenceNode.put("start", sentence.start());
                    sentenceNode.put("end", sentence.end());
                    sentenceNode.put("kind", sentence.kind());
                }
            }
        }
        byte[] bytes = json.writeValueAsBytes(root);
        if (bytes.length > limits.maxIndexBytes()) {
            throw new ImportFailure("EPUB_INDEX_TOO_LARGE", "文本索引超过上限");
        }
        Files.write(target, bytes);
    }

    private void writeManifest(Path target, String artifactHash, String sourceHashHex, EpubPackage epub,
                               List<Chapter> chapters) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", "readict-manifest-1");
        root.put("pipelineVersion", PIPELINE_VERSION);
        root.put("segmentRules", TextIndexer.SEGMENT_RULES);
        root.put("sourceHash", sourceHashHex);
        root.put("artifactHash", artifactHash);
        root.put("title", epub.title());
        if (epub.author() != null) root.put("author", epub.author());
        root.put("language", epub.language());
        root.put("tocSource", epub.tocSource());
        ArrayNode spine = root.putArray("spine");
        for (Chapter chapter : chapters) {
            ObjectNode node = spine.addObject();
            node.put("index", chapter.spineIndex());
            node.put("href", chapter.href());
            node.put("title", chapter.title());
            node.put("textLength", chapter.textLength());
        }
        ArrayNode toc = root.putArray("toc");
        for (EpubPackage.TocNode node : epub.toc()) {
            ObjectNode item = toc.addObject();
            item.put("label", node.label());
            item.put("href", node.href());
            ArrayNode children = item.putArray("children");
            for (EpubPackage.TocNode child : node.children()) {
                ObjectNode childNode = children.addObject();
                childNode.put("label", child.label());
                childNode.put("href", child.href());
                childNode.putArray("children");
            }
        }
        ObjectNode counts = root.putObject("counts");
        counts.put("chapters", chapters.size());
        counts.put("blocks", chapters.stream().mapToInt(c -> c.blocks().size()).sum());
        root.put("totalUtf16", chapters.stream().mapToLong(Chapter::textLength).sum());
        Files.write(target, json.writeValueAsBytes(root));
    }

    private final class Session implements XhtmlSanitizer.Resolver {
        private final ZipFile zip;
        private final EpubPackage epub;
        private final String sourceHash;
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final Map<String, byte[]> images = new LinkedHashMap<>();
        private final Map<String, byte[]> styles = new LinkedHashMap<>();
        private String current;

        private Session(ZipFile zip, EpubPackage epub, String sourceHash) {
            this.zip = zip;
            this.epub = epub;
            this.sourceHash = sourceHash;
            this.current = epub.spine().isEmpty() ? epub.opfPath() : epub.spine().get(0).entryName();
        }

        private void enter(String entryName) { this.current = entryName; }

        private byte[] read(String entryName) throws IOException {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) throw new ImportFailure("EPUB_INVALID_SPINE", "正文资源缺失");
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                long size = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    size += read;
                    if (size > limits.maxEntryBytes()) {
                        throw new ImportFailure("EPUB_ENTRY_TOO_LARGE", "单个资源解压后超过上限");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }

        @Override
        public boolean allow(String href, String kind) {
            String target = EpubReader.resolve(current, href);
            if (target == null) return false;
            String mediaType = epub.mediaTypes().get(target);
            if (mediaType == null) return false;
            String lower = mediaType.toLowerCase(Locale.ROOT);
            if ("css".equals(kind)) {
                if (!lower.startsWith("text/css")) return false;
                if (!styles.containsKey(target)) {
                    try { styles.put(target, read(target)); }
                    catch (IOException ex) { return false; }
                }
                return true;
            }
            if (!RASTER.contains(lower)) return false;
            if (!images.containsKey(target)) {
                byte[] bytes;
                try { bytes = read(target); }
                catch (IOException | ImportFailure ex) { return false; }
                String detected = EpubReader.imageMime(bytes);
                if (detected == null || !detected.equals(lower)) return false;
                images.put(target, bytes);
            }
            return true;
        }

        private void finish(List<Chapter> chapters) {
            styles.forEach((name, bytes) ->
                    files.put(name, css.sanitize(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
            images.forEach(files::put);
            files.put("META-INF/container.xml", container().getBytes(StandardCharsets.UTF_8));
            files.put(epub.opfPath(), opf(chapters).getBytes(StandardCharsets.UTF_8));
        }

        private String container() {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"" + escape(epub.opfPath())
                    + "\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>";
        }

        private String opf(List<Chapter> chapters) {
            String opfDir = epub.opfPath().contains("/")
                    ? epub.opfPath().substring(0, epub.opfPath().lastIndexOf('/')) : "";
            List<String> names = new ArrayList<>(new TreeSet<>(files.keySet()));
            StringBuilder out = new StringBuilder();
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">");
            out.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">");
            out.append("<dc:identifier id=\"bookid\">urn:readict:").append(escape(sourceHash)).append("</dc:identifier>");
            out.append("<dc:title>").append(escape(epub.title())).append("</dc:title>");
            if (epub.author() != null) out.append("<dc:creator>").append(escape(epub.author())).append("</dc:creator>");
            out.append("<dc:language>").append(escape(epub.language())).append("</dc:language>");
            out.append("</metadata><manifest>");
            Map<String, String> ids = new LinkedHashMap<>();
            int counter = 1;
            for (String name : names) {
                if ("mimetype".equals(name) || name.startsWith("META-INF/") || name.equals(epub.opfPath())) continue;
                String id = "r" + counter++;
                ids.put(name, id);
                out.append("<item id=\"").append(id).append("\" href=\"").append(escape(relative(opfDir, name)))
                        .append("\" media-type=\"").append(mediaTypeOf(name)).append("\"/>");
            }
            out.append("</manifest><spine>");
            for (Chapter chapter : chapters) {
                String id = ids.get(chapter.entryName());
                if (id != null) out.append("<itemref idref=\"").append(id).append("\"/>");
            }
            out.append("</spine></package>");
            return out.toString();
        }

        private String mediaTypeOf(String name) {
            String type = epub.mediaTypes().get(name);
            if (type != null) return type;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) return "application/xhtml+xml";
            if (lower.endsWith(".css")) return "text/css";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            return "application/octet-stream";
        }
    }

    private static String relative(String baseDir, String target) {
        List<String> base = baseDir.isEmpty() ? List.of() : Arrays.asList(baseDir.split("/"));
        List<String> parts = Arrays.asList(target.split("/"));
        int common = 0;
        while (common < base.size() && common < parts.size() - 1 && base.get(common).equals(parts.get(common))) {
            common++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = common; i < base.size(); i++) out.append("../");
        for (int i = common; i < parts.size(); i++) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '/') out.append('/');
            out.append(encode(parts.get(i)));
        }
        return out.toString();
    }

    private static String encode(String segment) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            switch (ch) {
                case ' ' -> out.append("%20");
                case '%' -> out.append("%25");
                case '#' -> out.append("%23");
                case '?' -> out.append("%3F");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static byte[] hashFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
