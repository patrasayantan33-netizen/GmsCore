/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

/**
 * Constants of the Google Cast v2 protocol as spoken between a sender (this library) and a
 * Cast receiver (Chromecast, Android TV, Google Home, Cast-enabled speakers, ...).
 * <p>
 * References: the public Cast v2 protocol documentation and the payload structures used by the
 * official Cast SDK. All strings are protocol values and must not be translated.
 */
public final class CastProtocol {
    private CastProtocol() {
    }

    /**
     * Default TCP port of the Cast v2 TLS channel.
     */
    public static final int DEFAULT_PORT = 8009;

    /**
     * Maximum size of a single Cast message. The receiver drops bigger messages.
     */
    public static final int MAX_MESSAGE_SIZE = 64 * 1024;

    /**
     * Timeout used while establishing the TCP connection to a Cast device.
     */
    public static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * Interval in which {@code PING} messages are sent to the device.
     */
    public static final long HEARTBEAT_INTERVAL_MS = 5_000;

    /**
     * The virtual connection id of the receiver itself.
     */
    public static final String RECEIVER_ID = "receiver-0";

    /**
     * Namespace used for virtual connection management.
     */
    public static final String NAMESPACE_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";

    /**
     * Namespace used for the heartbeat.
     */
    public static final String NAMESPACE_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";

    /**
     * Namespace of the receiver (application lifecycle and device volume).
     */
    public static final String NAMESPACE_RECEIVER = "urn:x-cast:com.google.cast.receiver";

    /**
     * Namespace of the media player.
     */
    public static final String NAMESPACE_MEDIA = "urn:x-cast:com.google.cast.media";

    /**
     * Namespace used for device level connection management.
     */
    public static final String NAMESPACE_DEVICE_CONNECTION = "urn:x-cast:com.google.cast.tp.deviceconnection";

    /**
     * Media player state: nothing is loaded.
     */
    public static final String PLAYER_STATE_IDLE = "IDLE";
    /**
     * Media player state: playback is paused.
     */
    public static final String PLAYER_STATE_PAUSED = "PAUSED";
    /**
     * Media player state: playback is running.
     */
    public static final String PLAYER_STATE_PLAYING = "PLAYING";
    /**
     * Media player state: the player is buffering.
     */
    public static final String PLAYER_STATE_BUFFERING = "BUFFERING";

    /**
     * Reason reported by the receiver when the currently loaded media finished playing.
     */
    public static final String IDLE_REASON_FINISHED = "FINISHED";
    /**
     * Reason reported by the receiver when playback was cancelled.
     */
    public static final String IDLE_REASON_CANCELLED = "CANCELLED";
    /**
     * Reason reported by the receiver when a playback error occurred.
     */
    public static final String IDLE_REASON_ERROR = "ERROR";
    /**
     * Reason reported by the receiver when the application interrupted playback.
     */
    public static final String IDLE_REASON_INTERRUPTED = "INTERRUPTED";

    /**
     * Stream type for bounded (on demand) media.
     */
    public static final String STREAM_TYPE_BUFFERED = "BUFFERED";
    /**
     * Stream type for live media.
     */
    public static final String STREAM_TYPE_LIVE = "LIVE";

    /**
     * Content type used for generic media without a hint about its container.
     */
    public static final String CONTENT_TYPE_GENERIC = "application/octet-stream";

    /**
     * Supported media command: pause.
     */
    public static final int MEDIA_COMMAND_PAUSE = 1;
    /**
     * Supported media command: seek.
     */
    public static final int MEDIA_COMMAND_SEEK = 2;
    /**
     * Supported media command: change the stream volume.
     */
    public static final int MEDIA_COMMAND_STREAM_VOLUME = 1 << 2;
    /**
     * Supported media command: mute the stream.
     */
    public static final int MEDIA_COMMAND_STREAM_MUTE = 1 << 3;
    /**
     * Supported media command: skip forward.
     */
    public static final int MEDIA_COMMAND_SKIP_FORWARD = 1 << 4;
    /**
     * Supported media command: skip backward.
     */
    public static final int MEDIA_COMMAND_SKIP_BACKWARD = 1 << 5;
    /**
     * Supported media command: play or pause the queue.
     */
    public static final int MEDIA_COMMAND_QUEUE_NEXT = 1 << 6;
    /**
     * Supported media command: play the previous queue item.
     */
    public static final int MEDIA_COMMAND_QUEUE_PREV = 1 << 7;
    /**
     * Supported media command: shuffle the queue.
     */
    public static final int MEDIA_COMMAND_QUEUE_SHUFFLE = 1 << 8;
    /**
     * Supported media command: repeat the queue.
     */
    public static final int MEDIA_COMMAND_QUEUE_REPEAT = 1 << 9;
    /**
     * Supported media command: repeat all queue items.
     */
    public static final int MEDIA_COMMAND_QUEUE_REPEAT_ALL = 1 << 10;
    /**
     * Supported media command: repeat one queue item.
     */
    public static final int MEDIA_COMMAND_QUEUE_REPEAT_ONE = 1 << 11;
    /**
     * Supported media command: reorder the queue.
     */
    public static final int MEDIA_COMMAND_QUEUE_REORDER = 1 << 12;
    /**
     * Supported media command: toggle subtitles.
     */
    public static final int MEDIA_COMMAND_TOGGLE_SUBTITLES = 1 << 17;
    /**
     * Supported media command: edit the used tracks.
     */
    public static final int MEDIA_COMMAND_EDIT_TRACKS = 1 << 18;
    /**
     * Supported media command: skip the currently playing advertisement.
     */
    public static final int MEDIA_COMMAND_SKIP_AD = 1 << 19;
    /**
     * Supported media command: change the playback rate.
     */
    public static final int MEDIA_COMMAND_PLAYBACK_RATE = 1 << 20;
    /**
     * Supported media command: like the current media.
     */
    public static final int MEDIA_COMMAND_LIKE = 1 << 13;
    /**
     * Supported media command: dislike the current media.
     */
    public static final int MEDIA_COMMAND_DISLIKE = 1 << 14;
    /**
     * Supported media command: follow the current media.
     */
    public static final int MEDIA_COMMAND_FOLLOW = 1 << 15;
    /**
     * Supported media command: unfollow the current media.
     */
    public static final int MEDIA_COMMAND_UNFOLLOW = 1 << 16;
}
