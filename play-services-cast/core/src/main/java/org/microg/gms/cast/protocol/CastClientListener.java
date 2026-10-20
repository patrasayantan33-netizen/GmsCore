/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

/**
 * Callbacks of a {@link CastClient}.
 * <p>
 * All callbacks are invoked on the reader thread of the client, i.e. never on a thread that issued
 * a command. Implementations that update UI have to post to the main thread themselves.
 */
public interface CastClientListener {
    /**
     * The TLS channel is established and the receiver namespace is connected.
     */
    void onConnected();

    /**
     * The channel was closed, either because the device closed it, the network failed or
     * {@link CastClient#disconnect()} was called.
     *
     * @param reason one of the {@code DISCONNECT_REASON_*} constants of {@link CastClient}.
     */
    void onDisconnected(int reason);

    /**
     * A new receiver status was received, containing the device volume and the running applications.
     */
    void onReceiverStatus(CastReceiverStatus status);

    /**
     * A new media status was received.
     *
     * @param status the status or {@code null} if the receiver has no media session any more.
     */
    void onMediaStatus(CastMediaStatus status);

    /**
     * The status text of the running application changed (e.g. "Ready To Cast").
     */
    void onApplicationStatus(String statusText);

    /**
     * The receiver rejected a request.
     *
     * @param requestId the request id returned when the request was sent, {@code 0} if unknown.
     * @param reason    the reason reported by the receiver, e.g. {@code INVALID_PLAYER_STATE}.
     */
    void onRequestFailed(long requestId, String reason);

    /**
     * A payload of a namespace that is not handled by {@link CastClient} itself was received.
     */
    void onMessageReceived(String namespace, String payload);

    /**
     * A binary payload was received.
     */
    void onBinaryMessageReceived(String namespace, byte[] payload);
}
