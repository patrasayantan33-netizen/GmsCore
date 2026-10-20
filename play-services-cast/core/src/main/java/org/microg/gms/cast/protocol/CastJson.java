/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONObject;

/**
 * Null safe accessors for the JSON payloads of the Cast protocol.
 * <p>
 * {@code org.json} has the unfortunate habit of turning an explicit JSON {@code null} into the
 * string {@code "null"} when {@code optString} is used. Cast receivers happily send {@code null}
 * values (e.g. {@code "idleReason": null}), so all parsing of protocol payloads goes through this
 * class.
 */
public final class CastJson {
    private CastJson() {
    }

    /**
     * @return the string value of the given key or {@code null} if the key is missing or JSON null.
     */
    public static String optString(JSONObject json, String key) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return null;
        }
        return json.optString(key, null);
    }

    public static String optString(JSONObject json, String key, String fallback) {
        String value = optString(json, key);
        return value == null ? fallback : value;
    }

    public static double optDouble(JSONObject json, String key, double fallback) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return fallback;
        }
        return json.optDouble(key, fallback);
    }

    public static long optLong(JSONObject json, String key, long fallback) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return fallback;
        }
        return json.optLong(key, fallback);
    }

    public static int optInt(JSONObject json, String key, int fallback) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return fallback;
        }
        return json.optInt(key, fallback);
    }

    public static boolean optBoolean(JSONObject json, String key, boolean fallback) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return fallback;
        }
        return json.optBoolean(key, fallback);
    }

    public static JSONObject optObject(JSONObject json, String key) {
        if (json == null || !json.has(key) || json.isNull(key)) {
            return null;
        }
        return json.optJSONObject(key);
    }

    /**
     * @return the message type of a Cast payload or {@code ""} if it has none.
     */
    public static String type(JSONObject json) {
        return optString(json, "type", "");
    }
}
