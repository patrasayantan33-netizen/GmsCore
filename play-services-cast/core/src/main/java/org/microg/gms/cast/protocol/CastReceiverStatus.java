/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of the receiver status ({@code RECEIVER_STATUS}) sent by the device.
 */
public final class CastReceiverStatus {
    /**
     * Current device volume.
     */
    public final CastVolume volume;
    /**
     * Applications currently running on the device.
     */
    public final List<Application> applications;
    /**
     * Whether the HDMI input of the device is active.
     */
    public final boolean activeInput;
    /**
     * Whether the device is in standby.
     */
    public final boolean standBy;
    /**
     * The request id this status is the answer to, {@code 0} for unsolicited statuses.
     */
    public final long requestId;

    public CastReceiverStatus(CastVolume volume, List<Application> applications, boolean activeInput, boolean standBy) {
        this(volume, applications, activeInput, standBy, 0L);
    }

    public CastReceiverStatus(CastVolume volume, List<Application> applications, boolean activeInput,
                              boolean standBy, long requestId) {
        this.volume = volume == null ? new CastVolume(1.0, false) : volume;
        this.applications = applications == null ? Collections.<Application>emptyList()
                : Collections.unmodifiableList(new ArrayList<Application>(applications));
        this.activeInput = activeInput;
        this.standBy = standBy;
        this.requestId = requestId;
    }

    /**
     * Parses the {@code status} object of a {@code RECEIVER_STATUS} message.
     */
    public static CastReceiverStatus fromJson(JSONObject json) {
        return fromJson(json, 0L);
    }

    /**
     * Parses the {@code status} object of a {@code RECEIVER_STATUS} message.
     *
     * @param requestId the request id of the enclosing message.
     */
    public static CastReceiverStatus fromJson(JSONObject json, long requestId) {
        if (json == null) {
            return new CastReceiverStatus(null, null, false, false, requestId);
        }
        List<Application> applications = new ArrayList<Application>();
        JSONArray array = json.optJSONArray("applications");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject application = array.optJSONObject(i);
                if (application != null) {
                    applications.add(Application.fromJson(application));
                }
            }
        }
        return new CastReceiverStatus(CastVolume.fromJson(CastJson.optObject(json, "volume")), applications,
                CastJson.optBoolean(json, "isActiveInput", false), CastJson.optBoolean(json, "isStandBy", false),
                requestId);
    }

    /**
     * @return the application with the given id or {@code null} if it is not running.
     */
    public Application findApplication(String applicationId) {
        if (applicationId == null) {
            return null;
        }
        for (Application application : applications) {
            if (applicationId.equals(application.appId)) {
                return application;
            }
        }
        return null;
    }

    /**
     * @return the first running (non idle screen) application or {@code null}.
     */
    public Application getRunningApplication() {
        for (Application application : applications) {
            if (!application.isIdleScreen) {
                return application;
            }
        }
        return null;
    }

    public boolean isApplicationRunning(String applicationId) {
        return findApplication(applicationId) != null;
    }

    @Override
    public String toString() {
        return "CastReceiverStatus{volume=" + volume + ", applications=" + applications +
                ", activeInput=" + activeInput + ", standBy=" + standBy + "}";
    }

    /**
     * A single application running on the receiver.
     */
    public static final class Application {
        public final String appId;
        public final String sessionId;
        public final String transportId;
        public final String displayName;
        public final String statusText;
        public final String appType;
        public final List<String> namespaces;
        public final boolean isIdleScreen;

        public Application(String appId, String sessionId, String transportId, String displayName,
                           String statusText, String appType, List<String> namespaces, boolean isIdleScreen) {
            this.appId = appId;
            this.sessionId = sessionId;
            this.transportId = transportId;
            this.displayName = displayName;
            this.statusText = statusText;
            this.appType = appType;
            this.namespaces = namespaces == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(namespaces));
            this.isIdleScreen = isIdleScreen;
        }

        static Application fromJson(JSONObject json) {
            List<String> namespaces = new ArrayList<String>();
            JSONArray array = json.optJSONArray("namespaces");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject namespace = array.optJSONObject(i);
                    String name = CastJson.optString(namespace, "name");
                    if (name != null) {
                        namespaces.add(name);
                    }
                }
            }
            return new Application(
                    CastJson.optString(json, "appId"),
                    CastJson.optString(json, "sessionId"),
                    CastJson.optString(json, "transportId"),
                    CastJson.optString(json, "displayName"),
                    CastJson.optString(json, "statusText"),
                    CastJson.optString(json, "appType"),
                    namespaces,
                    CastJson.optBoolean(json, "isIdleScreen", false));
        }

        public boolean supportsNamespace(String namespace) {
            return namespaces.contains(namespace);
        }

        @Override
        public String toString() {
            return "Application{appId=" + appId + ", sessionId=" + sessionId + ", transportId=" + transportId + "}";
        }
    }
}
