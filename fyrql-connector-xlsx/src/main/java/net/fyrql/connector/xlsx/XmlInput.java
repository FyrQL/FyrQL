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
package net.fyrql.connector.xlsx;

import java.io.IOException;
import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A reader of the XML parts of a workbook that never resolves anything
 * outside the part: no document type declaration, no external entity, no
 * external DTD. A part with a document type declaration fails, since no
 * writer of workbooks puts one in.
 * <p>
 * Elements and attributes are matched by their local names, so that the
 * namespaces of Strict Open XML read like the transitional ones.
 */
final class XmlInput implements AutoCloseable {

    private final XMLStreamReader reader;
    private final String part;

    private XmlInput(XMLStreamReader reader, String part) {
        this.reader = reader;
        this.part = part;
    }

    /**
     * Starts to read a part.
     *
     * @param part names the part in messages, such as {@code xl/workbook.xml of x.xlsx}
     */
    static XmlInput open(InputStream in, String part) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        try {
            return new XmlInput(factory.createXMLStreamReader(in), part);
        } catch (XMLStreamException e) {
            throw failure(part, e);
        }
    }

    /**
     * Moves to the next start or end of an element, past text, comments and
     * processing instructions.
     *
     * @return {@link XMLStreamConstants#START_ELEMENT},
     *         {@link XMLStreamConstants#END_ELEMENT} or
     *         {@link XMLStreamConstants#END_DOCUMENT}
     */
    int nextTag() throws IOException {
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT:
                        return event;
                    case XMLStreamConstants.DTD:
                        throw new IOException(part + " has a document type declaration, which is not allowed");
                    default:
                        break;
                }
            }
            return XMLStreamConstants.END_DOCUMENT;
        } catch (XMLStreamException e) {
            throw failure(part, e);
        }
    }

    /** Returns the local name of the current element. */
    String name() {
        return reader.getLocalName();
    }

    /** Returns the value of an attribute of the current element by its local name in any namespace, or null. */
    String attribute(String localName) {
        for (int i = 0, n = reader.getAttributeCount(); i < n; i++) {
            if (reader.getAttributeLocalName(i).equals(localName)) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /**
     * Returns the value of an attribute by its local name in a namespace,
     * of any namespace but none if {@code qualified}, such as {@code r:id}.
     */
    String attribute(String localName, boolean qualified) {
        for (int i = 0, n = reader.getAttributeCount(); i < n; i++) {
            String ns = reader.getAttributeNamespace(i);
            boolean hasNamespace = ns != null && !ns.isEmpty();
            if (hasNamespace == qualified && reader.getAttributeLocalName(i).equals(localName)) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /**
     * Reads the text of the current element, which has no child elements,
     * and moves to its end.
     */
    String text() throws IOException {
        try {
            StringBuilder b = null;
            String only = null;
            while (true) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE:
                        if (only == null && b == null) {
                            only = reader.getText();
                        } else {
                            if (b == null) {
                                b = new StringBuilder(only);
                            }
                            b.append(reader.getText());
                        }
                        break;
                    case XMLStreamConstants.END_ELEMENT:
                        return b != null ? b.toString() : only != null ? only : "";
                    case XMLStreamConstants.START_ELEMENT:
                        skip();
                        break;
                    default:
                        break;
                }
            }
        } catch (XMLStreamException e) {
            throw failure(part, e);
        }
    }

    /** Skips the rest of the current element, its children included, and moves to its end. */
    void skip() throws IOException {
        int depth = 1;
        while (depth > 0) {
            int event = nextTag();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else {
                throw new IOException(part + " ends inside an element");
            }
        }
    }

    /** Returns the name of the part for messages. */
    String part() {
        return part;
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (XMLStreamException e) {
            // The part has been read or has failed already.
        }
    }

    private static IOException failure(String part, XMLStreamException e) {
        Throwable cause = e.getNestedException() != null ? e.getNestedException() : e.getCause();
        if (cause instanceof IOException io) {
            return io;
        }
        return new IOException(part + " is not well-formed XML: "
                + String.valueOf(e.getMessage()).replaceAll("\\s+", " ").strip(), e);
    }
}
