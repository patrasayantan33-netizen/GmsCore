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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.mediarouter.media.MediaControlIntent;
import androidx.mediarouter.media.MediaItemStatus;
import androidx.mediarouter.media.MediaRouteProvider;
import androidx.mediarouter.media.MediaRouter;
import androidx.mediarouter.media.MediaSessionStatus;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;

import org.microg.gms.cast.protocol.CastClient;
import org.microg.gms.cast.protocol.CastClientListener;
import org.microg.gms.cast.protocol.CastMediaInfo;
import org.microg.gms.cast.protocol.CastMediaMetadata;
import org.microg.gms.cast.protocol.CastMediaStatus;
import org.microg.gms.cast.protocol.CastProtocol;
import org.microg.gms.cast.protocol.CastReceiverStatus;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Controls a single Cast route: opens the Cast v2 channel to the device, starts and stops receiver
 * applications and translates media router control requests into Cast protocol messages.
 * <p>
 * All network traffic happens on a private worker thread, all state changes are published back to
 * the media router through the {@link CastMediaRouteProvider} that created this controller.
 */
public class CastMediaRouteController extends MediaRouteProvider.RouteController implements CastClientListener {
    private static final String TAG = "GmsCastMediaRouteController";

    /**
     * Extra to specify the namespace of a {@link MediaControlIntent#ACTION_SEND_MESSAGE} request.
     */
    public static final String EXTRA_CAST_NAMESPACE = "com.google.android.gms.cast.EXTRA_CAST_NAMESPACE";

    private static final long REQUEST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    /**
     * Unselect reason used by the media router when the user switched away from the route.
     */
    private static final int UNSELECT_REASON_UNKNOWN = 0;

    private final CastMediaRouteProvider provider;
    private final CastDevice castDevice;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<Long, PendingRequest>();
    private final List<Runnable> queuedWhileConnecting = new ArrayList<Runnable>();

    private CastClient client;
    private volatile boolean selected;
    private volatile boolean released;
    private volatile int volume = -1;
    private volatile CastMediaStatus mediaStatus;
    private volatile String applicationId;
    private volatile String sessionId;
    private volatile CastMediaInfo queuedMedia;
    private volatile double queuedMediaPosition;
    private volatile boolean queuedMediaAutoplay = true;

    public CastMediaRouteController(CastMediaRouteProvider provider, CastDevice castDevice) {
        this.provider = provider;
        this.castDevice = castDevice;
    }

    // region Route lifecycle

    @Override
    public void onSelect() {
        Log.d(TAG, "Selecting " + castDevice.getFriendlyName());
        selected = true;
        provider.onRouteConnecting(castDevice.getDeviceId());
        execute(new Runnable() {
            @Override
            public void run() {
                connect();
            }
        });
    }

    @Override
    public void onUnselect() {
        onUnselect(UNSELECT_REASON_UNKNOWN);
    }

    @Override
    public void onUnselect(int reason) {
        Log.d(TAG, "Unselecting " + castDevice.getFriendlyName() + " (reason " + reason + ")");
        selected = false;
        execute(new Runnable() {
            @Override
            public void run() {
                disconnect();
            }
        });
    }

    @Override
    public void onRelease() {
        Log.d(TAG, "Releasing " + castDevice.getFriendlyName());
        released = true;
        selected = false;
        failAllPending("Controller released", MediaControlIntent.ERROR_UNKNOWN);
        execute(new Runnable() {
            @Override
            public void run() {
                disconnect();
            }
        });
        executor.shutdown();
    }

    private void connect() {
        CastClient previous = client;
        if (previous != null && previous.isConnected()) {
            return;
        }
        CastClient newClient = new CastClient(castDevice.getAddress(), castDevice.getServicePort(), this);
        client = newClient;
        try {
            newClient.connect();
            Log.d(TAG, "Connected to " + castDevice.getFriendlyName());
        } catch (IOException e) {
            Log.w(TAG, "Could not connect to " + castDevice.getFriendlyName() + ": " + e.getMessage());
            client = null;
            provider.onRouteDisconnected(castDevice.getDeviceId());
            failAllPending("Could not connect to device: " + e.getMessage(),
                    CastMediaControlIntent.ERROR_CODE_TEMPORARILY_DISCONNECTED);
        }
    }

    private void disconnect() {
        CastClient current = client;
        client = null;
        if (current != null) {
            current.disconnect();
        }
        provider.onRouteDisconnected(castDevice.getDeviceId());
        failAllPending("Route disconnected", CastMediaControlIntent.ERROR_CODE_SESSION_START_FAILED);
    }

    @Override
    public void onSetVolume(int volume) {
        Log.d(TAG, "Setting volume of " + castDevice.getFriendlyName() + " to " + volume);
        setVolumeLevel(volume);
    }

    @Override
    public void onUpdateVolume(int delta) {
        final int current = volume >= 0 ? volume : CastMediaRouteProvider.VOLUME_MAX / 2;
        setVolumeLevel(current + delta);
    }

    private void setVolumeLevel(final int newVolume) {
        final int clamped = Math.max(0, Math.min(CastMediaRouteProvider.VOLUME_MAX, newVolume));
        volume = clamped;
        provider.onRouteVolumeChanged(castDevice.getDeviceId(), clamped);
        execute(new Runnable() {
            @Override
            public void run() {
                CastClient current = client;
                if (current == null || !current.isConnected()) {
                    return;
                }
                try {
                    current.setVolume(clamped / (double) CastMediaRouteProvider.VOLUME_MAX);
                } catch (IOException e) {
                    Log.w(TAG, "Could not set volume: " + e.getMessage());
                }
            }
        });
    }

    // endregion

    // region Control requests

    @Override
    public boolean onControlRequest(Intent intent, MediaRouter.ControlRequestCallback callback) {
        if (intent == null || intent.getAction() == null) {
            return false;
        }
        String action = intent.getAction();
        Log.d(TAG, "Control request " + action + " for " + castDevice.getFriendlyName());

        if (MediaControlIntent.ACTION_GET_STATUS.equals(action)
                || MediaControlIntent.ACTION_GET_SESSION_STATUS.equals(action)
                || CastMediaControlIntent.ACTION_SYNC_STATUS.equals(action)) {
            if (callback != null) {
                callback.onResult(createStatusBundle());
            }
            // Nothing to wait for, but the media router expects us to refresh the mirrors.
            execute(new Runnable() {
                @Override
                public void run() {
                    requestStatus();
                }
            });
            return true;
        }

        if (MediaControlIntent.ACTION_END_SESSION.equals(action)) {
            return handleEndSession(intent, callback);
        }

        if (MediaControlIntent.ACTION_START_SESSION.equals(action)) {
            return handleStartSession(intent, callback);
        }

        if (MediaControlIntent.ACTION_SEND_MESSAGE.equals(action)) {
            return handleSendMessage(intent, callback);
        }

        if (MediaControlIntent.ACTION_PLAY.equals(action) || MediaControlIntent.ACTION_ENQUEUE.equals(action)) {
            return handlePlay(intent, callback);
        }

        if (MediaControlIntent.ACTION_PAUSE.equals(action)) {
            return sendMediaCommand(callback, new MediaCommand() {
                @Override
                public long execute(CastClient client) throws IOException {
                    return client.pause();
                }
            });
        }

        if (MediaControlIntent.ACTION_RESUME.equals(action)) {
            return sendMediaCommand(callback, new MediaCommand() {
                @Override
                public long execute(CastClient client) throws IOException {
                    return client.play();
                }
            });
        }

        if (MediaControlIntent.ACTION_STOP.equals(action)) {
            return sendMediaCommand(callback, new MediaCommand() {
                @Override
                public long execute(CastClient client) throws IOException {
                    return client.stopMedia();
                }
            });
        }

        if (MediaControlIntent.ACTION_SEEK.equals(action)) {
            final long positionMs = intent.getLongExtra(MediaControlIntent.EXTRA_ITEM_CONTENT_POSITION, 0);
            return sendMediaCommand(callback, new MediaCommand() {
                @Override
                public long execute(CastClient client) throws IOException {
                    return client.seek(positionMs / 1000.0);
                }
            });
        }

        if (MediaControlIntent.ACTION_REMOVE.equals(action)) {
            return sendMediaCommand(callback, new MediaCommand() {
                @Override
                public long execute(CastClient client) throws IOException {
                    return client.stopMedia();
                }
            });
        }

        Log.d(TAG, "Unsupported control action " + action);
        return false;
    }

    private interface MediaCommand {
        long execute(CastClient client) throws IOException;
    }

    private boolean sendMediaCommand(MediaRouter.ControlRequestCallback callback, final MediaCommand command) {
        return runWhenConnected(new Command() {
            @Override
            public void execute(CastClient client, ControlRequestCallbackHolder holder) {
                long requestId;
                try {
                    requestId = command.execute(client);
                } catch (IOException e) {
                    fail(holder, "Could not send command: " + e.getMessage(), MediaControlIntent.ERROR_UNKNOWN);
                    return;
                }
                if (holder.callback != null) {
                    registerPending(requestId, holder.callback, true);
                }
            }
        }, callback);
    }

    private boolean handlePlay(Intent intent, MediaRouter.ControlRequestCallback callback) {
        Uri uri = intent.getData();
        if (uri == null) {
            Log.w(TAG, "Play request without data uri");
            if (callback != null) {
                callback.onError("No media uri given", errorBundle(MediaControlIntent.ERROR_UNKNOWN));
            }
            return true;
        }
        final CastMediaInfo media = createMediaInfo(intent, uri);
        final boolean autoplay = !MediaControlIntent.ACTION_ENQUEUE.equals(intent.getAction());
        final double position = intent.getLongExtra(MediaControlIntent.EXTRA_ITEM_CONTENT_POSITION, 0) / 1000.0;

        return runWhenConnected(new Command() {
            @Override
            public void execute(CastClient client, ControlRequestCallbackHolder holder) {
                if (!client.isConnected()) {
                    fail(holder, "Not connected", CastMediaControlIntent.ERROR_CODE_TEMPORARILY_DISCONNECTED);
                    return;
                }
                if (client.getTransportId() == null) {
                    // No receiver application running yet: start the default media receiver and
                    // load the media as soon as the session is up.
                    queuedMedia = media;
                    queuedMediaPosition = position;
                    queuedMediaAutoplay = autoplay;
                    try {
                        long requestId = client.launchApplication(
                                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID, false);
                        if (holder.callback != null) {
                            registerPending(requestId, holder.callback, false);
                        }
                    } catch (IOException e) {
                        queuedMedia = null;
                        fail(holder, "Could not start receiver: " + e.getMessage(),
                                CastMediaControlIntent.ERROR_CODE_SESSION_START_FAILED);
                    }
                    return;
                }
                try {
                    long requestId = client.loadMedia(media, autoplay, position);
                    if (holder.callback != null) {
                        registerPending(requestId, holder.callback, true);
                    }
                } catch (IOException e) {
                    fail(holder, "Could not load media: " + e.getMessage(), MediaControlIntent.ERROR_UNKNOWN);
                }
            }
        }, callback);
    }

    private boolean handleStartSession(Intent intent, MediaRouter.ControlRequestCallback callback) {
        String appId = intent.getStringExtra(CastMediaControlIntent.EXTRA_CAST_APPLICATION_ID);
        if (appId == null || appId.length() == 0) {
            appId = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID;
        }
        final String applicationId = appId;
        final boolean relaunch = intent.getBooleanExtra(CastMediaControlIntent.EXTRA_CAST_RELAUNCH_APPLICATION, false);
        return runWhenConnected(new Command() {
            @Override
            public void execute(CastClient client, ControlRequestCallbackHolder holder) {
                try {
                    long requestId = client.launchApplication(applicationId, relaunch);
                    if (holder.callback != null) {
                        registerPending(requestId, holder.callback, false);
                    }
                } catch (IOException e) {
                    fail(holder, "Could not start session: " + e.getMessage(),
                            CastMediaControlIntent.ERROR_CODE_SESSION_START_FAILED);
                }
            }
        }, callback);
    }

    private boolean handleEndSession(Intent intent, MediaRouter.ControlRequestCallback callback) {
        final String intentSessionId = intent.getStringExtra(MediaControlIntent.EXTRA_SESSION_ID);
        return runWhenConnected(new Command() {
            @Override
            public void execute(CastClient client, ControlRequestCallbackHolder holder) {
                try {
                    long requestId = client.stopApplication(intentSessionId != null ? intentSessionId : sessionId);
                    if (holder.callback != null) {
                        registerPending(requestId, holder.callback, false);
                    }
                } catch (IOException e) {
                    fail(holder, "Could not end session: " + e.getMessage(),
                            MediaControlIntent.ERROR_INVALID_SESSION_ID);
                }
            }
        }, callback);
    }

    private boolean handleSendMessage(Intent intent, MediaRouter.ControlRequestCallback callback) {
        String namespace = intent.getStringExtra(EXTRA_CAST_NAMESPACE);
        if (namespace == null) {
            namespace = "urn:x-cast:" + castDevice.getDeviceId();
        }
        final String messageNamespace = namespace;
        Bundle messageBundle = intent.getBundleExtra(MediaControlIntent.EXTRA_MESSAGE);
        String message = messageBundle != null ? messageBundle.getString("message") : null;
        if (message == null) {
            message = intent.getStringExtra(MediaControlIntent.EXTRA_MESSAGE);
        }
        if (message == null) {
            Log.w(TAG, "Send message request without message");
            if (callback != null) {
                callback.onError("No message given", errorBundle(MediaControlIntent.ERROR_UNKNOWN));
            }
            return true;
        }
        final String payload = message;
        return runWhenConnected(new Command() {
            @Override
            public void execute(CastClient client, ControlRequestCallbackHolder holder) {
                try {
                    long requestId = client.sendMessage(messageNamespace, payload, 0);
                    if (holder.callback != null) {
                        registerPending(requestId, holder.callback, false);
                    }
                } catch (IOException e) {
                    fail(holder, "Could not send message: " + e.getMessage(), MediaControlIntent.ERROR_UNKNOWN);
                }
            }
        }, callback);
    }

    private CastMediaInfo createMediaInfo(Intent intent, Uri uri) {
        String contentType = intent.getType();
        if (contentType == null) {
            contentType = guessContentType(uri);
        }
        CastMediaMetadata.Builder metadata = CastMediaMetadata.builder(guessMetadataType(contentType));
        Bundle metadataBundle = intent.getBundleExtra(MediaControlIntent.EXTRA_ITEM_METADATA);
        if (metadataBundle != null) {
            metadata.setTitle(metadataBundle.getString("android.media.metadata.TITLE"));
            metadata.setArtist(metadataBundle.getString("android.media.metadata.ARTIST"));
            metadata.setAlbumName(metadataBundle.getString("android.media.metadata.ALBUM"));
            String albumArt = metadataBundle.getString("android.media.metadata.ALBUM_ART_URI");
            if (albumArt != null) {
                metadata.addImage(albumArt, 0, 0);
            }
        }
        Bundle headers = intent.getBundleExtra(MediaControlIntent.EXTRA_ITEM_HTTP_HEADERS);
        JSONObject customData = null;
        if (headers != null && !headers.isEmpty()) {
            // Cast receivers fetch media on their own, headers can only be passed as custom data.
            customData = new JSONObject();
            try {
                JSONObject headerJson = new JSONObject();
                for (String key : headers.keySet()) {
                    headerJson.put(key, headers.getString(key));
                }
                customData.put("httpHeaders", headerJson);
            } catch (JSONException e) {
                Log.w(TAG, "Could not encode http headers: " + e.getMessage());
            }
        }
        return CastMediaInfo.builder(uri.toString(), contentType)
                .setStreamType(CastProtocol.STREAM_TYPE_BUFFERED)
                .setCustomData(customData)
                .setMetadata(metadata.build())
                .build();
    }

    private static String guessMetadataType(String contentType) {
        if (contentType == null) {
            return CastMediaMetadata.TYPE_GENERIC;
        }
        if (contentType.startsWith("audio/")) {
            return CastMediaMetadata.TYPE_MUSIC_TRACK;
        }
        if (contentType.startsWith("image/")) {
            return CastMediaMetadata.TYPE_PHOTO;
        }
        return CastMediaMetadata.TYPE_MOVIE;
    }

    private static String guessContentType(Uri uri) {
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment == null) {
            return CastProtocol.CONTENT_TYPE_GENERIC;
        }
        String lower = lastSegment.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".m3u8")) return "application/x-mpegurl";
        if (lower.endsWith(".mpd")) return "application/dash+xml";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/x-m4a";
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return CastProtocol.CONTENT_TYPE_GENERIC;
    }

    // endregion

    // region Command plumbing

    /**
     * Wrapper that allows the {@link Command} implementations to be written without caring about
     * whether a result callback was requested.
     */
    private static final class ControlRequestCallbackHolder {
        final MediaRouter.ControlRequestCallback callback;

        ControlRequestCallbackHolder(MediaRouter.ControlRequestCallback callback) {
            this.callback = callback;
        }
    }

    private interface Command {
        void execute(CastClient client, ControlRequestCallbackHolder holder);
    }

    /**
     * Runs the given command on the worker thread, connecting first if necessary. Commands issued
     * while the channel is being established are queued.
     */
    private boolean runWhenConnected(final Command command, final MediaRouter.ControlRequestCallback callback) {
        final ControlRequestCallbackHolder holder = new ControlRequestCallbackHolder(callback);
        execute(new Runnable() {
            @Override
            public void run() {
                CastClient current = client;
                if (current == null || !current.isConnected()) {
                    connect();
                    current = client;
                }
                if (current == null || !current.isConnected()) {
                    synchronized (queuedWhileConnecting) {
                        if (!released && queuedWhileConnecting.size() < 16) {
                            queuedWhileConnecting.add(new Runnable() {
                                @Override
                                public void run() {
                                    command.execute(client, holder);
                                }
                            });
                            return;
                        }
                    }
                    fail(holder, "Not connected to device", CastMediaControlIntent.ERROR_CODE_TEMPORARILY_DISCONNECTED);
                    return;
                }
                command.execute(current, holder);
            }
        });
        return true;
    }

    private void execute(Runnable runnable) {
        try {
            executor.execute(runnable);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Controller already released, dropping command");
        }
    }

    private static final class PendingRequest {
        final long requestId;
        final MediaRouter.ControlRequestCallback callback;
        final boolean mediaRequest;
        final Runnable timeout;

        PendingRequest(final long requestId, MediaRouter.ControlRequestCallback callback, boolean mediaRequest,
                       final Map<Long, PendingRequest> pendingRequests) {
            this.requestId = requestId;
            this.callback = callback;
            this.mediaRequest = mediaRequest;
            this.timeout = new Runnable() {
                @Override
                public void run() {
                    if (pendingRequests.remove(requestId) != null) {
                        callback.onError("Request timed out", errorBundle(MediaControlIntent.ERROR_UNKNOWN));
                    }
                }
            };
        }
    }

    private void registerPending(long requestId, MediaRouter.ControlRequestCallback callback, boolean mediaRequest) {
        if (requestId <= 0 || callback == null) {
            // The device will not answer this request explicitly, the media router is informed
            // about the new state through the route descriptor instead.
            return;
        }
        PendingRequest pending = new PendingRequest(requestId, callback, mediaRequest, pendingRequests);
        pendingRequests.put(requestId, pending);
        handler.postDelayed(pending.timeout, REQUEST_TIMEOUT_MS);
    }

    private void resolvePending(long requestId, boolean mediaStatus) {
        if (requestId > 0) {
            PendingRequest pending = pendingRequests.remove(requestId);
            if (pending != null) {
                deliverResult(pending, mediaStatus);
            }
            return;
        }
        // Receivers that do not echo the request id send unsolicited statuses only, in that case
        // everything that waits for this kind of update is resolved.
        for (Map.Entry<Long, PendingRequest> entry : new ArrayList<Map.Entry<Long, PendingRequest>>(pendingRequests.entrySet())) {
            PendingRequest pending = entry.getValue();
            if (pending.mediaRequest == mediaStatus) {
                pendingRequests.remove(entry.getKey());
                deliverResult(pending, mediaStatus);
            }
        }
    }

    private void deliverResult(PendingRequest pending, boolean mediaStatus) {
        handler.removeCallbacks(pending.timeout);
        final Bundle result;
        try {
            result = createStatusBundle();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not create status bundle: " + e.getMessage());
            pending.callback.onError("Internal error", errorBundle(MediaControlIntent.ERROR_UNKNOWN));
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                pending.callback.onResult(result);
            }
        });
    }

    private void fail(ControlRequestCallbackHolder holder, String message, int errorCode) {
        if (holder == null || holder.callback == null) {
            Log.w(TAG, message);
            return;
        }
        final MediaRouter.ControlRequestCallback callback = holder.callback;
        final Bundle extras = errorBundle(errorCode);
        handler.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(message, extras);
            }
        });
    }

    private void failAllPending(String message, int errorCode) {
        for (Map.Entry<Long, PendingRequest> entry : new ArrayList<Map.Entry<Long, PendingRequest>>(pendingRequests.entrySet())) {
            PendingRequest pending = pendingRequests.remove(entry.getKey());
            handler.removeCallbacks(pending.timeout);
            final Bundle extras = errorBundle(errorCode);
            final MediaRouter.ControlRequestCallback callback = pending.callback;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onError(message, extras);
                }
            });
        }
    }

    private static Bundle errorBundle(int errorCode) {
        Bundle bundle = new Bundle();
        bundle.putInt(MediaControlIntent.EXTRA_ERROR_CODE, errorCode);
        return bundle;
    }

    /**
     * Creates a bundle containing both the media router session status and the Cast specific state
     * of the route.
     */
    private Bundle createStatusBundle() {
        Bundle result = new Bundle();

        // The media router session status only knows integer session ids, the Cast session id is
        // carried in the extras of the status.
        Bundle sessionExtras = new Bundle();
        if (sessionId != null) {
            sessionExtras.putString(CastMediaRouteProvider.EXTRA_CAST_SESSION_ID, sessionId);
        }
        MediaSessionStatus sessionStatus = new MediaSessionStatus.Builder(
                sessionId != null ? sessionId.hashCode() : 0)
                .setSessionState(applicationId != null ? MediaSessionStatus.SESSION_STATE_ACTIVE
                        : MediaSessionStatus.SESSION_STATE_ENDED)
                .setExtras(sessionExtras)
                .build();
        result.putBundle(MediaControlIntent.EXTRA_SESSION_STATUS, sessionStatus.asBundle());

        CastMediaStatus status = mediaStatus;
        if (status != null) {
            int playbackState = MediaItemStatus.PLAYBACK_STATE_PENDING;
            if (CastProtocol.PLAYER_STATE_PLAYING.equals(status.playerState)) {
                playbackState = MediaItemStatus.PLAYBACK_STATE_PLAYING;
            } else if (CastProtocol.PLAYER_STATE_PAUSED.equals(status.playerState)) {
                playbackState = MediaItemStatus.PLAYBACK_STATE_PAUSED;
            } else if (CastProtocol.PLAYER_STATE_BUFFERING.equals(status.playerState)) {
                playbackState = MediaItemStatus.PLAYBACK_STATE_BUFFERING;
            } else if (status.isIdle()) {
                playbackState = MediaItemStatus.PLAYBACK_STATE_FINISHED;
            }
            MediaItemStatus itemStatus = new MediaItemStatus.Builder(playbackState)
                    .setContentPosition(status.getPositionMillis())
                    .setContentDuration(Math.max(0, status.getDurationMillis()))
                    .build();
            result.putBundle(MediaControlIntent.EXTRA_ITEM_STATUS, itemStatus.asBundle());
            result.putString(MediaControlIntent.EXTRA_ITEM_ID,
                    String.valueOf(status.mediaSessionId));
            result.putString(CastMediaRouteProvider.EXTRA_CAST_PLAYER_STATE, status.playerState);
            result.putLong(CastMediaRouteProvider.EXTRA_CAST_MEDIA_POSITION_MS, status.getPositionMillis());
            result.putLong(CastMediaRouteProvider.EXTRA_CAST_MEDIA_DURATION_MS, status.getDurationMillis());
            if (status.media != null) {
                result.putString(CastMediaRouteProvider.EXTRA_CAST_MEDIA_CONTENT_ID, status.media.contentId);
                if (status.media.metadata != null) {
                    if (status.media.metadata.title != null) {
                        result.putString(CastMediaRouteProvider.EXTRA_CAST_MEDIA_TITLE, status.media.metadata.title);
                    }
                    if (status.media.metadata.artist != null) {
                        result.putString(CastMediaRouteProvider.EXTRA_CAST_MEDIA_ARTIST, status.media.metadata.artist);
                    }
                }
            }
        }

        if (applicationId != null) {
            result.putString(CastMediaRouteProvider.EXTRA_CAST_APPLICATION_ID, applicationId);
        }
        if (sessionId != null) {
            result.putString(CastMediaRouteProvider.EXTRA_CAST_SESSION_ID, sessionId);
            result.putString(MediaControlIntent.EXTRA_SESSION_ID, sessionId);
        }
        castDevice.putInBundle(result);
        return result;
    }

    // endregion

    // region CastClient callbacks

    @Override
    public void onConnected() {
        CastClient current = client;
        if (current == null) {
            return;
        }
        final String appId = current.getApplicationId();
        final String deviceSessionId = current.getSessionId();
        provider.onRouteConnected(castDevice.getDeviceId(), appId, deviceSessionId);
        List<Runnable> queued;
        synchronized (queuedWhileConnecting) {
            queued = new ArrayList<Runnable>(queuedWhileConnecting);
            queuedWhileConnecting.clear();
        }
        for (Runnable runnable : queued) {
            runnable.run();
        }
    }

    @Override
    public void onDisconnected(int reason) {
        Log.d(TAG, "Disconnected from " + castDevice.getFriendlyName() + " (reason " + reason + ")");
        mediaStatus = null;
        applicationId = null;
        sessionId = null;
        provider.onRouteDisconnected(castDevice.getDeviceId());
        failAllPending("Connection to device lost", CastMediaControlIntent.ERROR_CODE_TEMPORARILY_DISCONNECTED);
        if (selected && !released) {
            // Reconnect once, the media router keeps the route selected while the device reboots.
            execute(new Runnable() {
                @Override
                public void run() {
                    client = null;
                    connect();
                }
            });
        }
    }

    @Override
    public void onReceiverStatus(CastReceiverStatus status) {
        if (status == null) {
            return;
        }
        volume = (int) Math.round(status.volume.level * CastMediaRouteProvider.VOLUME_MAX);
        provider.onRouteVolumeChanged(castDevice.getDeviceId(), volume);
        CastReceiverStatus.Application application = status.getRunningApplication();
        String newApplicationId = application == null ? null : application.appId;
        String newSessionId = application == null ? null : application.sessionId;
        boolean sessionChanged = !equal(applicationId, newApplicationId) || !equal(sessionId, newSessionId);
        applicationId = newApplicationId;
        sessionId = newSessionId;
        if (application == null) {
            mediaStatus = null;
            provider.onRouteDisconnected(castDevice.getDeviceId());
        } else {
            provider.onRouteConnected(castDevice.getDeviceId(), applicationId, sessionId);
        }
        resolvePending(status.requestId, false);
        if (sessionChanged) {
            CastMediaInfo pendingMedia = queuedMedia;
            if (pendingMedia != null && application != null) {
                queuedMedia = null;
                final CastMediaInfo media = pendingMedia;
                final double position = queuedMediaPosition;
                final boolean autoplay = queuedMediaAutoplay;
                execute(new Runnable() {
                    @Override
                    public void run() {
                        CastClient current = client;
                        if (current == null || current.getTransportId() == null) {
                            return;
                        }
                        try {
                            current.loadMedia(media, autoplay, position);
                        } catch (IOException e) {
                            Log.w(TAG, "Could not load queued media: " + e.getMessage());
                        }
                    }
                });
            }
        }
    }

    @Override
    public void onMediaStatus(CastMediaStatus status) {
        mediaStatus = status;
        provider.onRouteMediaStatusChanged(castDevice.getDeviceId(), status);
        resolvePending(status == null ? -1 : status.requestId, true);
    }

    @Override
    public void onApplicationStatus(String statusText) {
        Log.d(TAG, "Application status of " + castDevice.getFriendlyName() + ": " + statusText);
    }

    @Override
    public void onRequestFailed(long requestId, String reason) {
        PendingRequest pending = requestId > 0 ? pendingRequests.remove(requestId) : null;
        if (pending != null) {
            handler.removeCallbacks(pending.timeout);
            final MediaRouter.ControlRequestCallback callback = pending.callback;
            final Bundle extras = errorBundle(MediaControlIntent.ERROR_UNKNOWN);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onError(reason, extras);
                }
            });
        } else {
            Log.w(TAG, "Request failed: " + reason);
        }
    }

    @Override
    public void onMessageReceived(String namespace, String payload) {
        Log.d(TAG, "Message in " + namespace + ": " + payload);
    }

    @Override
    public void onBinaryMessageReceived(String namespace, byte[] payload) {
        Log.d(TAG, "Binary message in " + namespace + " (" + (payload == null ? 0 : payload.length) + " bytes)");
    }

    /**
     * Asks the device for the current receiver and media status.
     */
    private void requestStatus() {
        CastClient current = client;
        if (current == null || !current.isConnected()) {
            return;
        }
        try {
            current.requestReceiverStatus();
            current.requestMediaStatus();
        } catch (IOException e) {
            Log.w(TAG, "Could not request status: " + e.getMessage());
        }
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    // endregion
}
