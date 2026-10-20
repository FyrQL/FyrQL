/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.connector.s3;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The small XML documents S3 answers with, read into elements by their
 * local names, without document type declarations and external entities.
 */
final class Xml {

    private Xml() {
    }

    /** An element: its local name, its text and its child elements. */
    record Element(String name, String text, List<Element> children) {

        /** Returns the first child of a name, or {@code null}. */
        Element child(String childName) {
            for (Element e : children) {
                if (e.name.equals(childName)) {
                    return e;
                }
            }
            return null;
        }

        /** Returns the text of the first child of a name, or {@code null}. */
        String text(String childName) {
            Element e = child(childName);
            return e == null ? null : e.text;
        }

        /** Returns the children of a name. */
        List<Element> all(String childName) {
            return children.stream().filter(e -> e.name.equals(childName)).toList();
        }
    }

    /**
     * Reads a document.
     *
     * @throws IOException if it is not well-formed XML or has a document type declaration
     */
    static Element parse(byte[] document) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(document));
            Deque<String> names = new ArrayDeque<>();
            Deque<StringBuilder> texts = new ArrayDeque<>();
            Deque<List<Element>> children = new ArrayDeque<>();
            Element root = null;
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.DTD -> throw new IOException("An answer of S3 has a document type"
                            + " declaration");
                    case XMLStreamConstants.START_ELEMENT -> {
                        names.push(reader.getLocalName());
                        texts.push(new StringBuilder());
                        children.push(new ArrayList<>());
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!texts.isEmpty()) {
                            texts.peek().append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        Element e = new Element(names.pop(), texts.pop().toString(), List.copyOf(children.pop()));
                        if (children.isEmpty()) {
                            root = e;
                        } else {
                            children.peek().add(e);
                        }
                    }
                    default -> {
                    }
                }
            }
            if (root == null) {
                throw new IOException("An answer of S3 has no element");
            }
            return root;
        } catch (XMLStreamException e) {
            throw new IOException("An answer of S3 is not well-formed XML: "
                    + e.getMessage().replaceAll("\\s+", " ").strip(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException e) {
                    // The document has been read or has failed already.
                }
            }
        }
    }

    /** Escapes a text for the content of an element. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
