/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import java.nio.charset.StandardCharsets;

/**
 * A single Cast v2 message, matching the {@code CastMessage} protobuf of the protocol:
 *
 * <pre>
 * enum ProtocolVersion { CASTV2_1_0 = 0; }
 * enum PayloadType { STRING = 0; BINARY = 1; }
 * message CastMessage {
 *     ProtocolVersion protocolVersion = 1;
 *     string sourceId = 2;
 *     string destinationId = 3;
 *     string namespace = 4;
 *     PayloadType payloadType = 5;
 *     string payloadUtf8 = 6;
 *     bytes payloadBinary = 7;
 * }
 * </pre>
 */
public final class CastMessage {
    /**
     * The only protocol version defined by Cast v2.
     */
    public static final int PROTOCOL_VERSION_CASTV2_1_0 = 0;

    private final String sourceId;
    private final String destinationId;
    private final String namespace;
    private final String payloadUtf8;
    private final byte[] payloadBinary;

    public CastMessage(String sourceId, String destinationId, String namespace, String payloadUtf8) {
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        this.sourceId = sourceId == null ? "" : sourceId;
        this.destinationId = destinationId == null ? "" : destinationId;
        this.namespace = namespace;
        this.payloadUtf8 = payloadUtf8 == null ? "" : payloadUtf8;
        this.payloadBinary = null;
    }

    public CastMessage(String sourceId, String destinationId, String namespace, byte[] payloadBinary) {
        if (namespace == null) {
            throw new IllegalArgumentException("namespace must not be null");
        }
        this.sourceId = sourceId == null ? "" : sourceId;
        this.destinationId = destinationId == null ? "" : destinationId;
        this.namespace = namespace;
        this.payloadUtf8 = null;
        this.payloadBinary = payloadBinary == null ? new byte[0] : payloadBinary;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getDestinationId() {
        return destinationId;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isBinary() {
        return payloadBinary != null;
    }

    /**
     * @return the UTF-8 payload of this message or {@code null} if this is a binary message.
     */
    public String getPayloadUtf8() {
        return payloadUtf8;
    }

    /**
     * @return the binary payload of this message or {@code null} if this is a string message.
     */
    public byte[] getPayloadBinary() {
        return payloadBinary;
    }

    public byte[] getPayload() {
        return isBinary() ? payloadBinary : payloadUtf8.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "CastMessage{" + sourceId + " -> " + destinationId + " [" + namespace + "] " +
                (isBinary() ? "<binary " + payloadBinary.length + " bytes>" : payloadUtf8) + "}";
    }
}
