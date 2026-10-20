/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encoder and decoder for the Cast v2 wire format.
 * <p>
 * The Cast channel is not length prefixed and does not use a full protobuf library: every message
 * is a {@code CastMessage} protobuf encoded with the standard protobuf wire format, written
 * directly onto the TLS stream. Decoding therefore has to consume exactly the fields that belong to
 * the current message which is only possible by parsing the field tags - exactly what this class
 * does.
 */
public final class CastMessageCodec {
    private static final int FIELD_PROTOCOL_VERSION = 1;
    private static final int FIELD_SOURCE_ID = 2;
    private static final int FIELD_DESTINATION_ID = 3;
    private static final int FIELD_NAMESPACE = 4;
    private static final int FIELD_PAYLOAD_TYPE = 5;
    private static final int FIELD_PAYLOAD_UTF8 = 6;
    private static final int FIELD_PAYLOAD_BINARY = 7;

    private static final int WIRE_TYPE_VARINT = 0;
    private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

    private static final int PAYLOAD_TYPE_STRING = 0;
    private static final int PAYLOAD_TYPE_BINARY = 1;

    private static final int VARINT_MAX_BYTES = 10;

    private CastMessageCodec() {
    }

    /**
     * Serializes the given message.
     *
     * @return the encoded message, ready to be written to the socket.
     */
    public static byte[] encode(CastMessage message) {
        byte[] sourceId = message.getSourceId().getBytes(StandardCharsets.UTF_8);
        byte[] destinationId = message.getDestinationId().getBytes(StandardCharsets.UTF_8);
        byte[] namespace = message.getNamespace().getBytes(StandardCharsets.UTF_8);
        byte[] payload = message.getPayload();
        int payloadType = message.isBinary() ? PAYLOAD_TYPE_BINARY : PAYLOAD_TYPE_STRING;

        int size = 1 + sizeOfVarint(CastMessage.PROTOCOL_VERSION_CASTV2_1_0);
        size += 1 + sizeOfVarint(sourceId.length) + sourceId.length;
        size += 1 + sizeOfVarint(destinationId.length) + destinationId.length;
        size += 1 + sizeOfVarint(namespace.length) + namespace.length;
        size += 1 + sizeOfVarint(payloadType);
        size += 1 + sizeOfVarint(payload.length) + payload.length;

        byte[] out = new byte[size];
        int pos = 0;
        pos = writeTag(out, pos, FIELD_PROTOCOL_VERSION, WIRE_TYPE_VARINT);
        pos = writeVarint(out, pos, CastMessage.PROTOCOL_VERSION_CASTV2_1_0);
        pos = writeTag(out, pos, FIELD_SOURCE_ID, WIRE_TYPE_LENGTH_DELIMITED);
        pos = writeVarint(out, pos, sourceId.length);
        System.arraycopy(sourceId, 0, out, pos, sourceId.length);
        pos += sourceId.length;
        pos = writeTag(out, pos, FIELD_DESTINATION_ID, WIRE_TYPE_LENGTH_DELIMITED);
        pos = writeVarint(out, pos, destinationId.length);
        System.arraycopy(destinationId, 0, out, pos, destinationId.length);
        pos += destinationId.length;
        pos = writeTag(out, pos, FIELD_NAMESPACE, WIRE_TYPE_LENGTH_DELIMITED);
        pos = writeVarint(out, pos, namespace.length);
        System.arraycopy(namespace, 0, out, pos, namespace.length);
        pos += namespace.length;
        pos = writeTag(out, pos, FIELD_PAYLOAD_TYPE, WIRE_TYPE_VARINT);
        pos = writeVarint(out, pos, payloadType);
        pos = writeTag(out, pos, message.isBinary() ? FIELD_PAYLOAD_BINARY : FIELD_PAYLOAD_UTF8, WIRE_TYPE_LENGTH_DELIMITED);
        pos = writeVarint(out, pos, payload.length);
        System.arraycopy(payload, 0, out, pos, payload.length);
        return out;
    }

    /**
     * Writes the given message to the stream. The stream is flushed, but not closed.
     */
    public static void write(OutputStream outputStream, CastMessage message) throws IOException {
        byte[] data = encode(message);
        outputStream.write(data);
        outputStream.flush();
    }

    /**
     * Reads a single message from the stream.
     *
     * @throws EOFException if the stream ended before a full message was read.
     */
    public static CastMessage read(InputStream inputStream) throws IOException {
        String sourceId = "";
        String destinationId = "";
        String namespace = null;
        String payloadUtf8 = null;
        byte[] payloadBinary = null;
        int payloadType = PAYLOAD_TYPE_STRING;

        while (true) {
            int tag = readVarintInt(inputStream, true);
            if (tag < 0) {
                // EOF before any field was read, the caller decides what to do with it.
                throw new EOFException("Stream ended while waiting for a Cast message");
            }
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;
            switch (fieldNumber) {
                case FIELD_PROTOCOL_VERSION:
                    readVarintLong(inputStream, false);
                    break;
                case FIELD_SOURCE_ID:
                    skipLengthDelimited(inputStream, wireType, fieldNumber);
                    sourceId = readString(inputStream);
                    break;
                case FIELD_DESTINATION_ID:
                    skipLengthDelimited(inputStream, wireType, fieldNumber);
                    destinationId = readString(inputStream);
                    break;
                case FIELD_NAMESPACE:
                    skipLengthDelimited(inputStream, wireType, fieldNumber);
                    namespace = readString(inputStream);
                    break;
                case FIELD_PAYLOAD_TYPE:
                    payloadType = (int) readVarintLong(inputStream, false);
                    break;
                case FIELD_PAYLOAD_UTF8:
                    skipLengthDelimited(inputStream, wireType, fieldNumber);
                    payloadUtf8 = readString(inputStream);
                    payloadBinary = null;
                    break;
                case FIELD_PAYLOAD_BINARY:
                    skipLengthDelimited(inputStream, wireType, fieldNumber);
                    payloadBinary = readBytes(inputStream, CastProtocol.MAX_MESSAGE_SIZE);
                    payloadUtf8 = null;
                    break;
                default:
                    skipField(inputStream, wireType);
                    break;
            }

            if (namespace != null && (payloadUtf8 != null || payloadBinary != null)) {
                if (payloadType == PAYLOAD_TYPE_BINARY) {
                    if (payloadBinary != null) {
                        return new CastMessage(sourceId, destinationId, namespace, payloadBinary);
                    }
                } else if (payloadUtf8 != null) {
                    return new CastMessage(sourceId, destinationId, namespace, payloadUtf8);
                } else {
                    // Binary payload announced as string payload: treat as empty string.
                    return new CastMessage(sourceId, destinationId, namespace, "");
                }
            }
        }
    }

    private static void skipLengthDelimited(InputStream inputStream, int wireType, int fieldNumber) throws IOException {
        if (wireType != WIRE_TYPE_LENGTH_DELIMITED) {
            throw new IOException("Unexpected wire type " + wireType + " for field " + fieldNumber);
        }
    }

    private static void skipField(InputStream inputStream, int wireType) throws IOException {
        switch (wireType) {
            case WIRE_TYPE_VARINT:
                readVarintLong(inputStream, false);
                break;
            case 1: // 64 bit
                skipFully(inputStream, 8);
                break;
            case 2: // length delimited
                skipFully(inputStream, readVarintLong(inputStream, false));
                break;
            case 5: // 32 bit
                skipFully(inputStream, 4);
                break;
            default:
                throw new IOException("Unsupported wire type " + wireType);
        }
    }

    private static void skipFully(InputStream inputStream, long length) throws IOException {
        if (length < 0 || length > CastProtocol.MAX_MESSAGE_SIZE) {
            throw new IOException("Invalid field length " + length);
        }
        long remaining = length;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() < 0) {
                    throw new EOFException("Stream ended while skipping " + length + " bytes");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static String readString(InputStream inputStream) throws IOException {
        return new String(readBytes(inputStream, CastProtocol.MAX_MESSAGE_SIZE), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream inputStream, int maxLength) throws IOException {
        int length = (int) readVarintLong(inputStream, false);
        if (length < 0 || length > maxLength) {
            throw new IOException("Invalid payload length " + length);
        }
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int count = inputStream.read(data, read, length - read);
            if (count < 0) {
                throw new EOFException("Stream ended while reading " + length + " bytes payload");
            }
            read += count;
        }
        return data;
    }

    private static int readVarintInt(InputStream inputStream, boolean eofAllowed) throws IOException {
        long value = readVarintLong(inputStream, eofAllowed);
        return (int) value;
    }

    private static long readVarintLong(InputStream inputStream, boolean eofAllowed) throws IOException {
        long result = 0;
        int shift = 0;
        for (int i = 0; i < VARINT_MAX_BYTES; i++) {
            int read = inputStream.read();
            if (read < 0) {
                if (i == 0 && eofAllowed) {
                    return -1;
                }
                throw new EOFException("Stream ended within a varint");
            }
            result |= (long) (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("Malformed varint");
    }

    private static int writeTag(byte[] out, int pos, int fieldNumber, int wireType) {
        return writeVarint(out, pos, (fieldNumber << 3) | wireType);
    }

    private static int writeVarint(byte[] out, int pos, long value) {
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            out[pos++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out[pos++] = (byte) remaining;
        return pos;
    }

    private static int sizeOfVarint(long value) {
        int size = 1;
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            size++;
            remaining >>>= 7;
        }
        return size;
    }
}
