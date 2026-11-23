package com.readict.library.infrastructure.epub;

import java.io.*;
import org.w3c.dom.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;

/** Hardened XML helpers: no DTD, no external entities, no network access. */
final class XmlSupport {
    private XmlSupport() {}

    static Document parse(byte[] data) {
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(data));
        } catch (Exception ex) {
            throw new ImportFailure("EPUB_INVALID_XHTML", "内容无法解析");
        }
    }

    static Document newDocument() {
        try {
            return factory().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("XML parser unavailable", ex);
        }
    }

    private static DocumentBuilderFactory factory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory;
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("XML parser unavailable", ex);
        }
    }

    static String local(Node node) {
        String name = node.getLocalName();
        return name != null ? name : node.getNodeName();
    }

    static String text(Element element) {
        String value = element == null ? null : element.getTextContent();
        return value == null ? "" : value.trim();
    }
}
