/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONObject;

/**
 * Status of the media player of a running receiver application ({@code MEDIA_STATUS}).
 */
public final class CastMediaStatus {
    /**
     * Session id of the media item inside the receiver application.
     */
    public final long mediaSessionId;
    /**
     * Current playback rate, e.g. {@code 1.0} or {@code 0.0} when paused.
     */
    public final double playbackRate;
    /**
     * One of {@link CastProtocol#PLAYER_STATE_IDLE}, {@link CastProtocol#PLAYER_STATE_PAUSED},
     * {@link CastProtocol#PLAYER_STATE_PLAYING} or {@link CastProtocol#PLAYER_STATE_BUFFERING}.
     */
    public final String playerState;
    /**
     * Current playback position in seconds.
     */
    public final double currentTime;
    /**
     * Bitmask of {@link CastProtocol#MEDIA_COMMAND_PAUSE} and friends.
     */
    public final long supportedMediaCommands;
    /**
     * Volume of the media stream, may be {@code null} if the receiver does not report it.
     */
    public final CastVolume volume;
    /**
     * Information about the loaded media, may be {@code null} in {@code IDLE} state.
     */
    public final CastMediaInfo media;
    /**
     * Reason for the {@code IDLE} state, {@code null} while playing.
     */
    public final String idleReason;
    /**
     * The request id this status belongs to, {@code 0} if not a direct answer.
     */
    public final long requestId;

    public CastMediaStatus(long mediaSessionId, double playbackRate, String playerState, double currentTime,
                           long supportedMediaCommands, CastVolume volume, CastMediaInfo media,
                           String idleReason, long requestId) {
        this.mediaSessionId = mediaSessionId;
        this.playbackRate = playbackRate;
        this.playerState = playerState == null ? CastProtocol.PLAYER_STATE_IDLE : playerState;
        this.currentTime = currentTime;
        this.supportedMediaCommands = supportedMediaCommands;
        this.volume = volume;
        this.media = media;
        this.idleReason = idleReason;
        this.requestId = requestId;
    }

    public static CastMediaStatus fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        JSONObject volume = CastJson.optObject(json, "volume");
        return new CastMediaStatus(
                CastJson.optLong(json, "mediaSessionId", 0L),
                CastJson.optDouble(json, "playbackRate", 0.0),
                CastJson.optString(json, "playerState", CastProtocol.PLAYER_STATE_IDLE),
                CastJson.optDouble(json, "currentTime", 0.0),
                CastJson.optLong(json, "supportedMediaCommands", 0L),
                volume == null ? null : CastVolume.fromJson(volume),
                CastMediaInfo.fromJson(CastJson.optObject(json, "media")),
                CastJson.optString(json, "idleReason"),
                CastJson.optLong(json, "requestId", 0L));
    }

    public boolean isPlaying() {
        return CastProtocol.PLAYER_STATE_PLAYING.equals(playerState)
                || CastProtocol.PLAYER_STATE_BUFFERING.equals(playerState);
    }

    public boolean isPaused() {
        return CastProtocol.PLAYER_STATE_PAUSED.equals(playerState);
    }

    public boolean isIdle() {
        return CastProtocol.PLAYER_STATE_IDLE.equals(playerState);
    }

    public boolean isMediaLoaded() {
        return media != null && !isIdle();
    }

    public boolean isLive() {
        return media != null && CastProtocol.STREAM_TYPE_LIVE.equals(media.streamType);
    }

    public boolean supportsCommand(int command) {
        return (supportedMediaCommands & command) == command;
    }

    /**
     * @return duration in milliseconds or {@code -1} if unknown.
     */
    public long getDurationMillis() {
        if (media == null || media.duration < 0) {
            return -1;
        }
        return (long) Math.round(media.duration * 1000.0);
    }

    /**
     * @return the current position in milliseconds.
     */
    public long getPositionMillis() {
        return (long) Math.round(currentTime * 1000.0);
    }

    @Override
    public String toString() {
        return "CastMediaStatus{mediaSessionId=" + mediaSessionId + ", playerState=" + playerState +
                ", currentTime=" + currentTime + ", media=" + media + ", idleReason=" + idleReason + "}";
    }
}
