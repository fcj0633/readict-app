package com.readict.library.infrastructure.epub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.*;

/** Self-authored EPUB fixtures; no real book is ever read or committed. */
public final class EpubFixtures {
    public static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private EpubFixtures() {}

    public static Path epub3(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf3()));
        files.put("OEBPS/nav.xhtml", utf8(nav()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        files.put("OEBPS/Styles/style.css", utf8(css()));
        files.put("OEBPS/Images/cover.png", PNG);
        return write(target, "application/epub+zip", files);
    }

    public static Path epub2(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf2()));
        files.put("OEBPS/toc.ncx", utf8(ncx()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        return write(target, "application/epub+zip", files);
    }

    public static Path hostile(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf3()));
        files.put("OEBPS/nav.xhtml", utf8(nav()));
        files.put("OEBPS/Text/ch1.xhtml", utf8("""
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Hostile</title>
                <script>alert(1)</script></head>
                <body onload="alert(2)">
                  <p onclick="alert(3)" style="color:red;background:url(http://evil.example/x.png)">Safe text.</p>
                  <iframe src="http://evil.example/frame"></iframe>
                  <img src="http://evil.example/track.png" alt="tracker"/>
                  <a href="javascript:alert(4)">bad link</a>
                  <form action="https://evil.example"><input name="x"/></form>
                  <p hidden="hidden">invisible text</p>
                </body></html>
                """));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        files.put("OEBPS/Styles/style.css", utf8(css()));
        return write(target, "application/epub+zip", files);
    }

    public static Path fixedLayout(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf3().replace("</metadata>",
                "<meta property=\"rendition:layout\">pre-paginated</meta></metadata>")));
        files.put("OEBPS/nav.xhtml", utf8(nav()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        return write(target, "application/epub+zip", files);
    }

    public static Path encrypted(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("META-INF/encryption.xml", utf8("<encryption/>"));
        files.put("OEBPS/content.opf", utf8(opf3()));
        files.put("OEBPS/nav.xhtml", utf8(nav()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        return write(target, "application/epub+zip", files);
    }

    public static Path traversal(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf3()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        files.put("../../escape.xhtml", utf8("<html/>"));
        return write(target, "application/epub+zip", files);
    }

    public static Path badMimetype(Path target) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf3()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        return write(target, "text/plain", files);
    }

    public static Path zipBomb(Path target, int entryBytes) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("META-INF/container.xml", utf8(container("OEBPS/content.opf")));
        files.put("OEBPS/content.opf", utf8(opf3()));
        files.put("OEBPS/nav.xhtml", utf8(nav()));
        files.put("OEBPS/Text/ch1.xhtml", utf8(chapter1()));
        files.put("OEBPS/Text/ch2.xhtml", utf8(chapter2()));
        files.put("OEBPS/Images/bomb.bin", new byte[entryBytes]);
        return write(target, "application/epub+zip", files);
    }

    public static Path write(Path target, String mimetype, Map<String, byte[]> files) {
        try {
            Files.createDirectories(target.getParent());
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
                put(out, "mimetype", mimetype.getBytes(StandardCharsets.ISO_8859_1), ZipEntry.STORED);
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    put(out, entry.getKey(), entry.getValue(), ZipEntry.DEFLATED);
                }
            }
            return target;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void put(ZipOutputStream out, String name, byte[] data, int method) throws IOException {
        ZipEntry entry = new ZipEntry(name);
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

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static String container(String opf) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"" + opf
                + "\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>";
    }

    static String opf3() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:identifier id="bookid">urn:test:readict</dc:identifier>
                  <dc:title>Test Book</dc:title>
                  <dc:creator>Test Author</dc:creator>
                  <dc:language>en</dc:language>
                  <meta name="cover" content="cover-image"/>
                 </metadata>
                 <manifest>
                  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="css" href="Styles/style.css" media-type="text/css"/>
                  <item id="cover-image" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
                 </manifest>
                 <spine>
                  <itemref idref="ch1"/>
                  <itemref idref="ch2"/>
                 </spine>
                </package>
                """;
    }

    static String opf2() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
                 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:identifier id="bookid">urn:test:readict2</dc:identifier>
                  <dc:title>Test Book Two</dc:title>
                  <dc:creator>Test Author</dc:creator>
                  <dc:language>en</dc:language>
                 </metadata>
                 <manifest>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                 </manifest>
                 <spine toc="ncx">
                  <itemref idref="ch1"/>
                  <itemref idref="ch2"/>
                 </spine>
                </package>
                """;
    }

    static String nav() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Contents</title></head>
                <body><nav epub:type="toc"><ol>
                  <li><a href="Text/ch1.xhtml">Chapter One</a></li>
                  <li><a href="Text/ch2.xhtml">Chapter Two</a></li>
                </ol></nav></body></html>
                """;
    }

    static String ncx() {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                 <navMap>
                  <navPoint id="n1" playOrder="1"><navLabel><text>Chapter One</text></navLabel>
                   <content src="Text/ch1.xhtml"/></navPoint>
                  <navPoint id="n2" playOrder="2"><navLabel><text>Chapter Two</text></navLabel>
                   <content src="Text/ch2.xhtml"/></navPoint>
                 </navMap>
                </ncx>
                """;
    }

    static String chapter1() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 1</title>
                  <link rel="stylesheet" type="text/css" href="../Styles/style.css"/>
                  <script>alert('x')</script>
                </head>
                <body>
                  <h1>Chapter 1</h1>
                  <p>Call me Ishmael.</p>
                  <p>Dr. Smith arrived. The children are <em>running</em> toward the old house.</p>
                  <p><img src="../Images/cover.png" alt="cover"/></p>
                  <p><img src="http://evil.example/track.png" alt="tracker"/></p>
                  <p>Path &amp; entity &lt;test&gt;.</p>
                </body></html>
                """;
    }

    static String chapter2() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head><title>Chapter 2</title></head>
                <body><p>It was the best of times, it was the worst of times. It was the age of wisdom.</p>
                <p>Line one<br/>line two</p></body></html>
                """;
    }

    static String css() {
        return """
                @import url("http://evil.example/x.css");
                body { color: #302E2B; font-family: Georgia, serif; position: fixed; background: url(http://evil.example/bg.png); }
                p { line-height: 1.8; }
                .bad { display: expression(alert(1)); }
                @media screen { h1 { font-size: 22px; } }
                """;
    }

    public static Path tempFile(Path directory, String name) {
        try {
            Files.createDirectories(directory);
            return Files.createFile(directory.resolve(name));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
