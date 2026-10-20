/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import java.io.IOException;

/**
 * Thrown when a Cast device cannot be reached, the channel breaks down or the device answered with
 * a message that violates the protocol.
 */
public class CastProtocolException extends IOException {
    public CastProtocolException(String message) {
        super(message);
    }

    public CastProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
