/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable snapshot of the device volume as reported by the receiver namespace.
 */
public final class CastVolume {
    /**
     * Volume level between 0.0 (muted) and 1.0 (maximum).
     */
    public final double level;
    /**
     * Whether the device is muted. Note that the receiver keeps the level while muted.
     */
    public final boolean muted;

    public CastVolume(double level, boolean muted) {
        this.level = clamp(level);
        this.muted = muted;
    }

    /**
     * Parses {@code {"level":0.5,"muted":false}}.
     */
    public static CastVolume fromJson(JSONObject json) {
        if (json == null) {
            return new CastVolume(1.0, false);
        }
        return new CastVolume(CastJson.optDouble(json, "level", 1.0), CastJson.optBoolean(json, "muted", false));
    }

    /**
     * @return the volume level in percent, as used by the media router.
     */
    public int getLevelPercent() {
        return (int) Math.round(level * 100.0);
    }

    public CastVolume withLevel(double newLevel) {
        return new CastVolume(newLevel, muted);
    }

    public CastVolume withMuted(boolean newMuted) {
        return new CastVolume(level, newMuted);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CastVolume)) return false;
        CastVolume volume = (CastVolume) other;
        return Double.compare(volume.level, level) == 0 && volume.muted == muted;
    }

    @Override
    public int hashCode() {
        return (int) (level * 1000) * 31 + (muted ? 1 : 0);
    }

    @Override
    public String toString() {
        return "CastVolume{level=" + level + ", muted=" + muted + "}";
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    static JSONObject toJson(CastVolume volume, boolean includeMuted) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("level", volume.level);
        if (includeMuted) {
            json.put("muted", volume.muted);
        }
        return json;
    }
}
