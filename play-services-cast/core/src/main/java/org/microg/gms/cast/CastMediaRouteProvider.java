/*
 * Copyright (C) 2013-2025 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.gms.cast;

import android.content.Context;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaRouteDescriptor;
import androidx.mediarouter.media.MediaRouteDiscoveryRequest;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouteProviderDescriptor;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;

import org.microg.gms.cast.protocol.CastMediaStatus;
import org.microg.gms.cast.protocol.CastProtocol;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes Google Cast devices found via DNS-SD as routes of the media router.
 * <p>
 * Discovery happens through {@code _googlecast._tcp} as described by the Cast specification. The
 * published routes contain everything an application needs to open a Cast session: the
 * {@link CastDevice} is embedded in the route extras
 * ({@link #EXTRA_CAST_DEVICE}), while the playback state of the running receiver application is
 * mirrored into the route so that the media router can render a "now playing" entry.
 */
public class CastMediaRouteProvider extends MediaRouteProvider {
    private static final String TAG = "GmsCastMediaRouteProvider";

    /**
     * Service type of Cast devices announced via mDNS.
     */
    private static final String CAST_SERVICE_TYPE = "_googlecast._tcp.";

    /**
     * Maximum value of the volume scale used by the media router. Cast devices use a volume between
     * 0.0 and 1.0, this constant is the factor between both representations.
     */
    public static final int VOLUME_MAX = 100;

    /**
     * Route extra containing the {@link CastDevice}. This is the key used by
     * {@link CastDevice#putInBundle(Bundle)} and {@link CastDevice#getFromBundle(Bundle)}.
     */
    public static final String EXTRA_CAST_DEVICE = "com.google.android.gms.cast.EXTRA_CAST_DEVICE";
    /**
     * Route extra containing the application id of the running receiver application.
     */
    public static final String EXTRA_CAST_APPLICATION_ID = "com.google.android.gms.cast.EXTRA_CAST_APPLICATION_ID";
    /**
     * Route extra containing the session id of the running receiver application.
     */
    public static final String EXTRA_CAST_SESSION_ID = "com.google.android.gms.cast.EXTRA_CAST_SESSION_ID";
    /**
     * Route extra containing the player state, one of the
     * {@link CastProtocol#PLAYER_STATE_IDLE} constants.
     */
    public static final String EXTRA_CAST_PLAYER_STATE = "com.google.android.gms.cast.EXTRA_CAST_PLAYER_STATE";
    /**
     * Route extra containing the title of the currently playing media item.
     */
    public static final String EXTRA_CAST_MEDIA_TITLE = "com.google.android.gms.cast.EXTRA_CAST_MEDIA_TITLE";
    /**
     * Route extra containing the artist of the currently playing media item.
     */
    public static final String EXTRA_CAST_MEDIA_ARTIST = "com.google.android.gms.cast.EXTRA_CAST_MEDIA_ARTIST";
    /**
     * Route extra containing the content id of the currently playing media item.
     */
    public static final String EXTRA_CAST_MEDIA_CONTENT_ID = "com.google.android.gms.cast.EXTRA_CAST_MEDIA_CONTENT_ID";
    /**
     * Route extra containing the current playback position in milliseconds.
     */
    public static final String EXTRA_CAST_MEDIA_POSITION_MS = "com.google.android.gms.cast.EXTRA_CAST_MEDIA_POSITION_MS";
    /**
     * Route extra containing the duration of the current media item in milliseconds.
     */
    public static final String EXTRA_CAST_MEDIA_DURATION_MS = "com.google.android.gms.cast.EXTRA_CAST_MEDIA_DURATION_MS";

    private static final List<IntentFilter> BASE_CONTROL_FILTERS = new ArrayList<IntentFilter>();
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    static {
        BASE_CONTROL_FILTERS.add(filterOf(CastMediaControlIntent.CATEGORY_CAST));

        IntentFilter playback = new IntentFilter();
        playback.addCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK);
        playback.addAction(MediaControlIntent.ACTION_PLAY);
        playback.addAction(MediaControlIntent.ACTION_ENQUEUE);
        playback.addAction(MediaControlIntent.ACTION_PAUSE);
        playback.addAction(MediaControlIntent.ACTION_RESUME);
        playback.addAction(MediaControlIntent.ACTION_STOP);
        playback.addAction(MediaControlIntent.ACTION_SEEK);
        playback.addAction(MediaControlIntent.ACTION_GET_STATUS);
        playback.addAction(MediaControlIntent.ACTION_START_SESSION);
        playback.addAction(MediaControlIntent.ACTION_GET_SESSION_STATUS);
        playback.addAction(MediaControlIntent.ACTION_END_SESSION);
        playback.addAction(MediaControlIntent.ACTION_SEND_MESSAGE);
        playback.addDataScheme("http");
        playback.addDataScheme("https");
        String[] types = {
                "image/jpeg", "image/pjpeg", "image/jpg", "image/webp", "image/png", "image/gif",
                "image/bmp", "image/vnd.microsoft.icon", "image/x-icon", "image/x-xbitmap",
                "audio/wav", "audio/x-wav", "audio/mp3", "audio/x-mp3", "audio/x-m4a", "audio/mpeg",
                "audio/webm", "audio/ogg", "audio/x-matroska",
                "video/mp4", "video/x-m4v", "video/mp2t", "video/webm", "video/ogg",
                "video/x-matroska", "application/x-mpegurl", "application/vnd.apple.mpegurl",
                "application/dash+xml", "application/vnd.ms-sstr+xml",
        };
        for (String type : types) {
            try {
                playback.addDataType(type);
            } catch (IntentFilter.MalformedMimeTypeException e) {
                Log.w(TAG, "Ignoring malformed mime type " + type);
            }
        }
        BASE_CONTROL_FILTERS.add(playback);

        BASE_CONTROL_FILTERS.add(filterOf(CastMediaControlIntent.CATEGORY_CAST_REMOTE_PLAYBACK));
    }

    private static IntentFilter filterOf(String category) {
        IntentFilter filter = new IntentFilter();
        filter.addCategory(category);
        return filter;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, RouteEntry> routes = new LinkedHashMap<String, RouteEntry>();
    private final Map<String, String> serviceNameByDeviceId = new LinkedHashMap<String, String>();
    private final List<String> customCategories = new ArrayList<String>();

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering;

    public CastMediaRouteProvider(Context context) {
        super(context);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            Log.i(TAG, "Cast discovery disabled: Android 4.1 or newer is required");
            return;
        }
        nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            Log.w(TAG, "Cast discovery disabled: no NSD service available");
            return;
        }
        discoveryListener = new DiscoveryListener();
    }

    // region Discovery

    private final class DiscoveryListener implements NsdManager.DiscoveryListener {
        @Override
        public void onDiscoveryStarted(String serviceType) {
            Log.d(TAG, "Cast discovery started");
            discovering = true;
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            if (serviceInfo.getServiceType() != null && !serviceInfo.getServiceType().startsWith("_googlecast")) {
                return;
            }
            try {
                nsdManager.resolveService(serviceInfo, new ResolveListener());
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Could not resolve " + serviceInfo.getServiceName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onServiceLost(final NsdServiceInfo serviceInfo) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onDeviceLost(serviceInfo.getServiceName());
                }
            });
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            Log.d(TAG, "Cast discovery stopped");
            discovering = false;
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(TAG, "Starting cast discovery failed: " + errorCode);
            discovering = false;
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(TAG, "Stopping cast discovery failed: " + errorCode);
            discovering = true;
        }
    }

    private final class ResolveListener implements NsdManager.ResolveListener {
        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                return;
            }
            Log.w(TAG, "Could not resolve " + serviceInfo.getServiceName() + ": " + errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            final CastDevice device = createCastDevice(serviceInfo);
            if (device == null) {
                return;
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    onDeviceFound(device, serviceInfo.getServiceName());
                }
            });
        }
    }

    private CastDevice createCastDevice(NsdServiceInfo serviceInfo) {
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        if (attributes == null) {
            Log.w(TAG, "Device " + serviceInfo.getServiceName() + " has no DNS-SD attributes");
            return null;
        }
        String id = getAttribute(attributes, "id");
        if (id == null || id.length() == 0) {
            Log.w(TAG, "Device " + serviceInfo.getServiceName() + " does not announce an id");
            return null;
        }
        String friendlyName = getAttribute(attributes, "fn");
        if (friendlyName == null) {
            friendlyName = serviceInfo.getServiceName();
        }
        String modelName = getAttribute(attributes, "md");
        String deviceVersion = getAttribute(attributes, "ve");
        String iconPath = getAttribute(attributes, "ic");
        int status = 0;
        String statusAttribute = getAttribute(attributes, "st");
        if (statusAttribute != null) {
            try {
                status = Integer.parseInt(statusAttribute);
            } catch (NumberFormatException ignored) {
                // Devices are allowed to send garbage here, the status is informational anyway.
            }
        }
        int capabilities = CastDevice.CAPABILITY_VIDEO_OUT | CastDevice.CAPABILITY_AUDIO_OUT;
        InetAddress address = serviceInfo.getHost();
        if (address == null) {
            Log.w(TAG, "Device " + friendlyName + " has no address");
            return null;
        }
        return new CastDevice(id, serviceInfo.getServiceName(), address, serviceInfo.getPort(),
                deviceVersion, friendlyName, modelName, iconPath, status, capabilities);
    }

    private static String getAttribute(Map<String, byte[]> attributes, String key) {
        byte[] value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return new String(value, UTF_8);
    }

    private void onDeviceFound(CastDevice device, String serviceName) {
        String id = device.getDeviceId();
        if (routes.containsKey(id)) {
            serviceNameByDeviceId.put(id, serviceName);
            return;
        }
        Log.d(TAG, "Found cast device " + device.getFriendlyName() + " (" + id + ")");
        routes.put(id, new RouteEntry(device));
        serviceNameByDeviceId.put(id, serviceName);
        publishRoutes();
    }

    private void onDeviceLost(String serviceName) {
        String deviceId = null;
        for (Map.Entry<String, String> entry : serviceNameByDeviceId.entrySet()) {
            if (entry.getValue().equals(serviceName)) {
                deviceId = entry.getKey();
                break;
            }
        }
        if (deviceId == null) {
            return;
        }
        RouteEntry entry = routes.get(deviceId);
        if (entry != null && (entry.connected || entry.connecting)) {
            // The device is still reachable over the Cast channel, mDNS answers can be lost
            // spuriously while a session is running.
            Log.d(TAG, "Device " + deviceId + " disappeared from DNS-SD but is still connected");
            return;
        }
        Log.d(TAG, "Lost cast device " + deviceId);
        serviceNameByDeviceId.remove(deviceId);
        routes.remove(deviceId);
        publishRoutes();
    }

    @Override
    public void onDiscoveryRequestChanged(MediaRouteDiscoveryRequest request) {
        if (nsdManager == null || discoveryListener == null) {
            return;
        }
        List<String> categories = request == null || request.getSelector() == null
                ? Collections.<String>emptyList() : request.getSelector().getControlCategories();
        boolean wantsCast = false;
        List<String> newCustomCategories = new ArrayList<String>();
        for (String category : categories) {
            if (CastMediaControlIntent.isCategoryForCast(category)) {
                wantsCast = true;
                if (!newCustomCategories.contains(category)) {
                    newCustomCategories.add(category);
                }
            }
        }
        boolean customCategoriesChanged = !newCustomCategories.equals(customCategories);
        if (customCategoriesChanged) {
            customCategories.clear();
            customCategories.addAll(newCustomCategories);
        }

        boolean active = request != null && request.isValid() && request.isActiveScan() && wantsCast;
        if (active && !discovering) {
            try {
                nsdManager.discoverServices(CAST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
                discovering = true;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Could not start cast discovery: " + e.getMessage());
            }
        } else if (!active && discovering) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Could not stop cast discovery: " + e.getMessage());
            } finally {
                discovering = false;
            }
        }
        if (customCategoriesChanged) {
            publishRoutes();
        }
    }

    // endregion

    // region Routes

    /**
     * State of a single published route, everything in here is mirrored into the route descriptor.
     */
    private static final class RouteEntry {
        final CastDevice device;
        boolean connected;
        boolean connecting;
        int volume;
        String applicationId;
        String sessionId;
        String playerState;
        String mediaTitle;
        String mediaArtist;
        String mediaContentId;
        long mediaPositionMs = -1;
        long mediaDurationMs = -1;
        CastMediaStatus mediaStatus;

        RouteEntry(CastDevice device) {
            this.device = device;
        }
    }

    @Override
    public RouteController onCreateRouteController(String routeId) {
        RouteEntry entry = routes.get(routeId);
        if (entry == null) {
            Log.w(TAG, "Requested controller for unknown route " + routeId);
            return null;
        }
        return new CastMediaRouteController(this, entry.device);
    }

    void onRouteConnecting(String routeId) {
        RouteEntry entry = routes.get(routeId);
        if (entry == null) {
            return;
        }
        entry.connecting = true;
        publishRoutes();
    }

    void onRouteConnected(String routeId, String applicationId, String sessionId) {
        RouteEntry entry = routes.get(routeId);
        if (entry == null) {
            return;
        }
        entry.connecting = false;
        entry.connected = true;
        entry.applicationId = applicationId;
        entry.sessionId = sessionId;
        publishRoutes();
    }

    void onRouteDisconnected(String routeId) {
        RouteEntry entry = routes.get(routeId);
        if (entry == null) {
            return;
        }
        entry.connecting = false;
        entry.connected = false;
        entry.applicationId = null;
        entry.sessionId = null;
        entry.mediaStatus = null;
        entry.playerState = CastProtocol.PLAYER_STATE_IDLE;
        entry.mediaTitle = null;
        entry.mediaArtist = null;
        entry.mediaContentId = null;
        entry.mediaPositionMs = -1;
        entry.mediaDurationMs = -1;
        publishRoutes();
    }

    void onRouteVolumeChanged(String routeId, int volume) {
        RouteEntry entry = routes.get(routeId);
        if (entry == null) {
            return;
        }
        entry.volume = Math.max(0, Math.min(VOLUME_MAX, volume));
        publishRoutes();
    }

    void onRouteMediaStatusChanged(String routeId, CastMediaStatus status) {
        RouteEntry entry = routes.get(routeId);
        if (entry == null) {
            return;
        }
        entry.mediaStatus = status;
        if (status == null) {
            entry.playerState = CastProtocol.PLAYER_STATE_IDLE;
            entry.mediaTitle = null;
            entry.mediaArtist = null;
            entry.mediaContentId = null;
            entry.mediaPositionMs = -1;
            entry.mediaDurationMs = -1;
        } else {
            entry.playerState = status.playerState;
            entry.mediaPositionMs = status.getPositionMillis();
            entry.mediaDurationMs = status.getDurationMillis();
            if (status.media != null) {
                entry.mediaContentId = status.media.contentId;
                if (status.media.metadata != null) {
                    entry.mediaTitle = status.media.metadata.title;
                    entry.mediaArtist = status.media.metadata.artist;
                } else {
                    entry.mediaTitle = null;
                    entry.mediaArtist = null;
                }
            }
        }
        publishRoutes();
    }

    private void publishRoutes() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    publishRoutes();
                }
            });
            return;
        }
        MediaRouteProviderDescriptor.Builder providerBuilder = new MediaRouteProviderDescriptor.Builder();
        for (RouteEntry entry : routes.values()) {
            providerBuilder.addRoute(createRouteDescriptor(entry));
        }
        setDescriptor(providerBuilder.build());
    }

    private MediaRouteDescriptor createRouteDescriptor(RouteEntry entry) {
        CastDevice device = entry.device;
        List<IntentFilter> controlFilters = new ArrayList<IntentFilter>(BASE_CONTROL_FILTERS);
        for (String category : customCategories) {
            controlFilters.add(filterOf(category));
        }

        Bundle extras = new Bundle();
        // Stores the CastDevice under EXTRA_CAST_DEVICE, readable via CastDevice.getFromBundle().
        device.putInBundle(extras);
        if (entry.applicationId != null) {
            extras.putString(EXTRA_CAST_APPLICATION_ID, entry.applicationId);
        }
        if (entry.sessionId != null) {
            extras.putString(EXTRA_CAST_SESSION_ID, entry.sessionId);
        }
        if (entry.playerState != null) {
            extras.putString(EXTRA_CAST_PLAYER_STATE, entry.playerState);
        }
        if (entry.mediaTitle != null) {
            extras.putString(EXTRA_CAST_MEDIA_TITLE, entry.mediaTitle);
        }
        if (entry.mediaArtist != null) {
            extras.putString(EXTRA_CAST_MEDIA_ARTIST, entry.mediaArtist);
        }
        if (entry.mediaContentId != null) {
            extras.putString(EXTRA_CAST_MEDIA_CONTENT_ID, entry.mediaContentId);
        }
        if (entry.mediaPositionMs >= 0) {
            extras.putLong(EXTRA_CAST_MEDIA_POSITION_MS, entry.mediaPositionMs);
        }
        if (entry.mediaDurationMs >= 0) {
            extras.putLong(EXTRA_CAST_MEDIA_DURATION_MS, entry.mediaDurationMs);
        }

        String description = device.getModelName();
        if (entry.mediaTitle != null) {
            description = entry.mediaTitle + (entry.mediaArtist == null ? "" : " - " + entry.mediaArtist);
        } else if (description == null || description.length() == 0) {
            description = device.getFriendlyName();
        }

        int connectionState = MediaRouter.RouteInfo.CONNECTION_STATE_DISCONNECTED;
        if (entry.connected) {
            connectionState = MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED;
        } else if (entry.connecting) {
            connectionState = MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING;
        }

        return new MediaRouteDescriptor.Builder(device.getDeviceId(), device.getFriendlyName())
                .setDescription(description)
                .addControlFilters(controlFilters)
                .setDeviceType(MediaRouter.RouteInfo.DEVICE_TYPE_TV)
                .setPlaybackType(MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
                .setPlaybackStream(android.media.AudioManager.STREAM_MUSIC)
                .setVolumeHandling(MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(VOLUME_MAX)
                .setVolume(entry.volume)
                .setEnabled(true)
                .setConnectionState(connectionState)
                .setExtras(extras)
                .build();
    }

    // endregion
}
