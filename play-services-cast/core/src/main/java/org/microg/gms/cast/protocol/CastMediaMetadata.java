/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Metadata of a media item, as reported within {@code MEDIA_STATUS} and used inside {@code LOAD}.
 */
public final class CastMediaMetadata {
    public static final String TYPE_GENERIC = "GENERIC";
    public static final String TYPE_MOVIE = "MOVIE";
    public static final String TYPE_TV_SHOW = "TV_SHOW";
    public static final String TYPE_MUSIC_TRACK = "MUSIC_TRACK";
    public static final String TYPE_PHOTO = "PHOTO";

    public final String metadataType;
    public final String title;
    public final String subtitle;
    public final String artist;
    public final String albumName;
    public final String albumArtist;
    public final String composer;
    public final int trackNumber;
    public final int discNumber;
    public final String releaseDate;
    public final String originalAirDate;
    public final String seriesTitle;
    public final String episodeTitle;
    public final int seasonNumber;
    public final int episodeNumber;
    public final List<Image> images;

    private CastMediaMetadata(Builder builder) {
        this.metadataType = builder.metadataType;
        this.title = builder.title;
        this.subtitle = builder.subtitle;
        this.artist = builder.artist;
        this.albumName = builder.albumName;
        this.albumArtist = builder.albumArtist;
        this.composer = builder.composer;
        this.trackNumber = builder.trackNumber;
        this.discNumber = builder.discNumber;
        this.releaseDate = builder.releaseDate;
        this.originalAirDate = builder.originalAirDate;
        this.seriesTitle = builder.seriesTitle;
        this.episodeTitle = builder.episodeTitle;
        this.seasonNumber = builder.seasonNumber;
        this.episodeNumber = builder.episodeNumber;
        this.images = Collections.unmodifiableList(new ArrayList<Image>(builder.images));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String metadataType) {
        return new Builder().setMetadataType(metadataType);
    }

    public static CastMediaMetadata fromJson(JSONObject json) {
        Builder builder = builder(CastJson.optString(json, "metadataType", TYPE_GENERIC));
        builder.setTitle(CastJson.optString(json, "title"));
        builder.setSubtitle(CastJson.optString(json, "subtitle"));
        builder.setArtist(CastJson.optString(json, "artist"));
        builder.setAlbumName(CastJson.optString(json, "albumName"));
        builder.setAlbumArtist(CastJson.optString(json, "albumArtist"));
        builder.setComposer(CastJson.optString(json, "composer"));
        builder.setTrackNumber(CastJson.optInt(json, "trackNumber", 0));
        builder.setDiscNumber(CastJson.optInt(json, "discNumber", 0));
        builder.setReleaseDate(CastJson.optString(json, "releaseDate"));
        builder.setOriginalAirDate(CastJson.optString(json, "originalAirDate"));
        builder.setSeriesTitle(CastJson.optString(json, "seriesTitle"));
        builder.setEpisodeTitle(CastJson.optString(json, "episodeTitle"));
        builder.setSeasonNumber(CastJson.optInt(json, "season", 0));
        builder.setEpisodeNumber(CastJson.optInt(json, "episode", 0));
        JSONArray images = json.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image != null) {
                    builder.addImage(new Image(CastJson.optString(image, "url"),
                            CastJson.optInt(image, "width", 0), CastJson.optInt(image, "height", 0)));
                }
            }
        }
        return builder.build();
    }

    /**
     * @return a {@code JSONObject} representation usable inside a {@code LOAD} message.
     */
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (metadataType != null) json.put("metadataType", metadataType);
        putIfNotNull(json, "title", title);
        putIfNotNull(json, "subtitle", subtitle);
        putIfNotNull(json, "artist", artist);
        putIfNotNull(json, "albumName", albumName);
        putIfNotNull(json, "albumArtist", albumArtist);
        putIfNotNull(json, "composer", composer);
        if (trackNumber > 0) json.put("trackNumber", trackNumber);
        if (discNumber > 0) json.put("discNumber", discNumber);
        putIfNotNull(json, "releaseDate", releaseDate);
        putIfNotNull(json, "originalAirDate", originalAirDate);
        putIfNotNull(json, "seriesTitle", seriesTitle);
        putIfNotNull(json, "episodeTitle", episodeTitle);
        if (seasonNumber > 0) json.put("season", seasonNumber);
        if (episodeNumber > 0) json.put("episode", episodeNumber);
        if (!images.isEmpty()) {
            JSONArray array = new JSONArray();
            for (Image image : images) {
                JSONObject entry = new JSONObject();
                entry.put("url", image.url);
                if (image.width > 0) entry.put("width", image.width);
                if (image.height > 0) entry.put("height", image.height);
                array.put(entry);
            }
            json.put("images", array);
        }
        return json;
    }

    private static void putIfNotNull(JSONObject json, String key, String value) throws JSONException {
        if (value != null && value.length() > 0) {
            json.put(key, value);
        }
    }

    @Override
    public String toString() {
        return "CastMediaMetadata{type=" + metadataType + ", title=" + title + ", artist=" + artist + "}";
    }

    /**
     * A cover art image.
     */
    public static final class Image {
        public final String url;
        public final int width;
        public final int height;

        public Image(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() {
            return "Image{url=" + url + ", width=" + width + ", height=" + height + "}";
        }
    }

    public static final class Builder {
        private String metadataType = TYPE_GENERIC;
        private String title;
        private String subtitle;
        private String artist;
        private String albumName;
        private String albumArtist;
        private String composer;
        private int trackNumber;
        private int discNumber;
        private String releaseDate;
        private String originalAirDate;
        private String seriesTitle;
        private String episodeTitle;
        private int seasonNumber;
        private int episodeNumber;
        private final List<Image> images = new ArrayList<Image>();

        public Builder setMetadataType(String metadataType) {
            this.metadataType = metadataType;
            return this;
        }

        public Builder setTitle(String title) {
            this.title = title;
            return this;
        }

        public Builder setSubtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder setArtist(String artist) {
            this.artist = artist;
            return this;
        }

        public Builder setAlbumName(String albumName) {
            this.albumName = albumName;
            return this;
        }

        public Builder setAlbumArtist(String albumArtist) {
            this.albumArtist = albumArtist;
            return this;
        }

        public Builder setComposer(String composer) {
            this.composer = composer;
            return this;
        }

        public Builder setTrackNumber(int trackNumber) {
            this.trackNumber = trackNumber;
            return this;
        }

        public Builder setDiscNumber(int discNumber) {
            this.discNumber = discNumber;
            return this;
        }

        public Builder setReleaseDate(String releaseDate) {
            this.releaseDate = releaseDate;
            return this;
        }

        public Builder setOriginalAirDate(String originalAirDate) {
            this.originalAirDate = originalAirDate;
            return this;
        }

        public Builder setSeriesTitle(String seriesTitle) {
            this.seriesTitle = seriesTitle;
            return this;
        }

        public Builder setEpisodeTitle(String episodeTitle) {
            this.episodeTitle = episodeTitle;
            return this;
        }

        public Builder setSeasonNumber(int seasonNumber) {
            this.seasonNumber = seasonNumber;
            return this;
        }

        public Builder setEpisodeNumber(int episodeNumber) {
            this.episodeNumber = episodeNumber;
            return this;
        }

        public Builder addImage(String url, int width, int height) {
            if (url != null && url.length() > 0) {
                this.images.add(new Image(url, width, height));
            }
            return this;
        }

        public Builder addImage(Image image) {
            if (image != null && image.url != null) {
                this.images.add(image);
            }
            return this;
        }

        public CastMediaMetadata build() {
            return new CastMediaMetadata(this);
        }
    }
}
