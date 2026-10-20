/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CastMessageCodecTest {

    @Test
    public void encodesKnownPayloadToTheDocumentedWireFormat() {
        CastMessage message = new CastMessage("sender-0", "receiver-0", CastProtocol.NAMESPACE_HEARTBEAT, "{\"type\":\"PING\"}");
        byte[] encoded = CastMessageCodec.encode(message);

        // field 1 (protocol version, varint 0), field 2 "sender-0", field 3 "receiver-0",
        // field 4 namespace, field 5 payload type 0, field 6 payload.
        assertArrayEquals(new byte[]{
                0x08, 0x00,
                0x12, 0x08, 's', 'e', 'n', 'd', 'e', 'r', '-', '0',
                0x1A, 0x0A, 'r', 'e', 'c', 'e', 'i', 'v', 'e', 'r', '-', '0',
                0x22, (byte) 0x27, 'u', 'r', 'n', ':', 'x', '-', 'c', 'a', 's', 't', ':', 'c', 'o', 'm',
                '.', 'g', 'o', 'o', 'g', 'l', 'e', '.', 'c', 'a', 's', 't', '.', 't', 'p', '.', 'h', 'e',
                'a', 'r', 't', 'b', 'e', 'a', 't',
                0x28, 0x00,
                0x32, 0x0F, '{', '"', 't', 'y', 'p', 'e', '"', ':', '"', 'P', 'I', 'N', 'G', '"', '}'
        }, encoded);
    }

    @Test
    public void roundTripsStringMessages() throws Exception {
        CastMessage message = new CastMessage("sender-1", "receiver-0", CastProtocol.NAMESPACE_RECEIVER,
                "{\"type\":\"GET_STATUS\",\"requestId\":1}");
        CastMessage decoded = CastMessageCodec.read(new ByteArrayInputStream(CastMessageCodec.encode(message)));

        assertEquals("sender-1", decoded.getSourceId());
        assertEquals("receiver-0", decoded.getDestinationId());
        assertEquals(CastProtocol.NAMESPACE_RECEIVER, decoded.getNamespace());
        assertEquals("{\"type\":\"GET_STATUS\",\"requestId\":1}", decoded.getPayloadUtf8());
        assertFalse(decoded.isBinary());
    }

    @Test
    public void roundTripsBinaryMessages() throws Exception {
        byte[] payload = new byte[]{0x00, 0x01, (byte) 0xFF, 0x7F, (byte) 0x80};
        CastMessage message = new CastMessage("sender-1", "transport-1", "urn:x-cast:com.example.binary", payload);
        CastMessage decoded = CastMessageCodec.read(new ByteArrayInputStream(CastMessageCodec.encode(message)));

        assertTrue(decoded.isBinary());
        assertArrayEquals(payload, decoded.getPayloadBinary());
        assertEquals("urn:x-cast:com.example.binary", decoded.getNamespace());
    }

    @Test
    public void readsMultipleMessagesFromOneStream() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastMessage first = new CastMessage("sender-1", "receiver-0", CastProtocol.NAMESPACE_HEARTBEAT, "{\"type\":\"PING\"}");
        CastMessage second = new CastMessage("sender-1", "receiver-0", CastProtocol.NAMESPACE_HEARTBEAT, "{\"type\":\"PONG\"}");
        CastMessageCodec.write(out, first);
        CastMessageCodec.write(out, second);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertEquals("{\"type\":\"PING\"}", CastMessageCodec.read(in).getPayloadUtf8());
        assertEquals("{\"type\":\"PONG\"}", CastMessageCodec.read(in).getPayloadUtf8());
    }

    @Test
    public void readsUtf8PayloadsOfAnyLength() throws Exception {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            builder.append("\u00e4");
        }
        CastMessage message = new CastMessage("sender-1", "receiver-0", CastProtocol.NAMESPACE_RECEIVER, builder.toString());
        CastMessage decoded = CastMessageCodec.read(new ByteArrayInputStream(CastMessageCodec.encode(message)));

        assertEquals(builder.toString(), decoded.getPayloadUtf8());
        assertTrue(decoded.getPayload().length > 5000);
    }

    @Test
    public void skipsUnknownFields() throws Exception {
        byte[] base = CastMessageCodec.encode(new CastMessage("sender-1", "receiver-0",
                CastProtocol.NAMESPACE_HEARTBEAT, "{\"type\":\"PING\"}"));
        // Append an unknown field with number 9 and wire type 0.
        byte[] extended = new byte[base.length + 2];
        System.arraycopy(base, 0, extended, 0, base.length);
        extended[base.length] = 0x48;
        extended[base.length + 1] = 0x2A;

        CastMessage decoded = CastMessageCodec.read(new ByteArrayInputStream(extended));
        assertEquals("{\"type\":\"PING\"}", decoded.getPayloadUtf8());
        assertEquals(CastProtocol.NAMESPACE_HEARTBEAT, decoded.getNamespace());
    }

    @Test
    public void reportsEndOfStream() throws Exception {
        try {
            CastMessageCodec.read(new ByteArrayInputStream(new byte[0]));
            fail("Expected EOFException");
        } catch (EOFException expected) {
            // Expected.
        }
    }

    @Test
    public void payloadOfTheMessageIsUtf8Encoded() {
        CastMessage message = new CastMessage("sender-1", "receiver-0", CastProtocol.NAMESPACE_RECEIVER, "\u00e4");
        assertArrayEquals("\u00e4".getBytes(StandardCharsets.UTF_8), message.getPayload());
    }
}
