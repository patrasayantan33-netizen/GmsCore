/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Description of a media item which can be loaded onto a receiver.
 */
public final class CastMediaInfo {
    /**
     * The URL of the media.
     */
    public final String contentId;
    /**
     * The MIME type of the media, e.g. {@code video/mp4}.
     */
    public final String contentType;
    /**
     * Either {@link CastProtocol#STREAM_TYPE_BUFFERED} or {@link CastProtocol#STREAM_TYPE_LIVE}.
     */
    public final String streamType;
    /**
     * Duration of the media in seconds, {@code -1} if unknown.
     */
    public final double duration;
    /**
     * Optional metadata, may be {@code null}.
     */
    public final CastMediaMetadata metadata;
    /**
     * Optional application specific custom data, may be {@code null}.
     */
    public final JSONObject customData;

    private CastMediaInfo(Builder builder) {
        this.contentId = builder.contentId;
        this.contentType = builder.contentType;
        this.streamType = builder.streamType;
        this.duration = builder.duration;
        this.metadata = builder.metadata;
        this.customData = builder.customData;
    }

    public static Builder builder(String contentId, String contentType) {
        return new Builder(contentId, contentType);
    }

    public static CastMediaInfo fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        String contentId = CastJson.optString(json, "contentId");
        if (contentId == null) {
            return null;
        }
        Builder builder = builder(contentId, CastJson.optString(json, "contentType", CastProtocol.CONTENT_TYPE_GENERIC));
        builder.setStreamType(CastJson.optString(json, "streamType", CastProtocol.STREAM_TYPE_BUFFERED));
        builder.setDuration(CastJson.optDouble(json, "duration", -1.0));
        JSONObject metadata = CastJson.optObject(json, "metadata");
        if (metadata != null) {
            builder.setMetadata(CastMediaMetadata.fromJson(metadata));
        }
        JSONObject customData = CastJson.optObject(json, "customData");
        if (customData != null) {
            builder.setCustomData(customData);
        }
        return builder.build();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("contentId", contentId);
        json.put("contentType", contentType);
        json.put("streamType", streamType);
        if (duration >= 0) {
            json.put("duration", duration);
        }
        if (metadata != null) {
            json.put("metadata", metadata.toJson());
        }
        if (customData != null) {
            json.put("customData", customData);
        }
        return json;
    }

    @Override
    public String toString() {
        return "CastMediaInfo{contentId=" + contentId + ", contentType=" + contentType + ", streamType=" + streamType + "}";
    }

    public static final class Builder {
        private final String contentId;
        private final String contentType;
        private String streamType = CastProtocol.STREAM_TYPE_BUFFERED;
        private double duration = -1.0;
        private CastMediaMetadata metadata;
        private JSONObject customData;

        private Builder(String contentId, String contentType) {
            if (contentId == null) {
                throw new IllegalArgumentException("contentId must not be null");
            }
            this.contentId = contentId;
            this.contentType = contentType == null || contentType.length() == 0
                    ? CastProtocol.CONTENT_TYPE_GENERIC : contentType;
        }

        public Builder setStreamType(String streamType) {
            this.streamType = CastProtocol.STREAM_TYPE_LIVE.equals(streamType)
                    ? CastProtocol.STREAM_TYPE_LIVE : CastProtocol.STREAM_TYPE_BUFFERED;
            return this;
        }

        /**
         * @param duration duration in seconds, negative values mean "unknown".
         */
        public Builder setDuration(double duration) {
            this.duration = duration;
            return this;
        }

        public Builder setMetadata(CastMediaMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder setCustomData(JSONObject customData) {
            this.customData = customData;
            return this;
        }

        public CastMediaInfo build() {
            return new CastMediaInfo(this);
        }
    }
}
