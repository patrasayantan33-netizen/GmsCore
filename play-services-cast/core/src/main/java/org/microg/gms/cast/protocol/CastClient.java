/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLSocket;

/**
 * Connection to a single Cast device.
 * <p>
 * This is the implementation of the Cast v2 protocol as needed by a sender: it opens the TLS
 * channel on port 8009, keeps it alive with heartbeats, manages virtual connections to the running
 * receiver application and exposes the receiver and media namespaces as an asynchronous API.
 * <p>
 * All methods that send a message return the Cast request id of the message (or {@code -1} if the
 * message does not carry one). The answer of the device is delivered through the
 * {@link CastClientListener}: statuses through {@link CastClientListener#onReceiverStatus} /
 * {@link CastClientListener#onMediaStatus}, failures through
 * {@link CastClientListener#onRequestFailed}.
 * <p>
 * Instances are thread safe, but not reusable: after {@link #disconnect()} a new client has to be
 * created.
 */
public class CastClient implements Closeable {
    private static final String TAG = "GmsCastClient";

    /**
     * The channel was closed on request of the local side.
     */
    public static final int DISCONNECT_REASON_USER_REQUEST = 0;
    /**
     * The device closed the virtual connection.
     */
    public static final int DISCONNECT_REASON_CLOSED_BY_DEVICE = 1;
    /**
     * The network connection broke down.
     */
    public static final int DISCONNECT_REASON_NETWORK_ERROR = 2;

    private static final String KEY_TYPE = "type";
    private static final String KEY_REQUEST_ID = "requestId";
    private static final String KEY_MEDIA_SESSION_ID = "mediaSessionId";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_TRANSPORT_ID = "transportId";
    private static final String KEY_STATUS = "status";
    private static final String KEY_MEDIA = "media";

    private static final String TYPE_PING = "PING";
    private static final String TYPE_PONG = "PONG";
    private static final String TYPE_CONNECT = "CONNECT";
    private static final String TYPE_CLOSE = "CLOSE";
    private static final String TYPE_GET_STATUS = "GET_STATUS";
    private static final String TYPE_LAUNCH = "LAUNCH";
    private static final String TYPE_STOP = "STOP";
    private static final String TYPE_LOAD = "LOAD";
    private static final String TYPE_PLAY = "PLAY";
    private static final String TYPE_PAUSE = "PAUSE";
    private static final String TYPE_SEEK = "SEEK";
    private static final String TYPE_SET_VOLUME = "SET_VOLUME";
    private static final String TYPE_RECEIVER_STATUS = "RECEIVER_STATUS";
    private static final String TYPE_MEDIA_STATUS = "MEDIA_STATUS";

    private final String host;
    private final int port;
    private final String certificateFingerprint;
    private final CastClientListener listener;

    private final AtomicLong requestIds = new AtomicLong(1);
    private final Object writeLock = new Object();
    private final Object stateLock = new Object();
    private final List<String> registeredNamespaces = new CopyOnWriteArrayList<String>();

    private SSLSocket socket;
    private OutputStream outputStream;
    private Thread readerThread;
    private Thread heartbeatThread;
    private volatile boolean running;
    private volatile boolean connected;

    private volatile String sourceId = "sender-0";
    private volatile String transportId;
    private volatile String sessionId;
    private volatile String applicationId;
    private volatile CastReceiverStatus receiverStatus;
    private volatile CastMediaStatus mediaStatus;

    public CastClient(String host, CastClientListener listener) {
        this(host, CastProtocol.DEFAULT_PORT, null, listener);
    }

    public CastClient(String host, int port, CastClientListener listener) {
        this(host, port, null, listener);
    }

    public CastClient(String host, int port, String certificateFingerprint, CastClientListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.host = host;
        this.port = port > 0 ? port : CastProtocol.DEFAULT_PORT;
        this.certificateFingerprint = certificateFingerprint;
        this.listener = listener;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * @return the virtual connection id of this sender.
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * @return the transport id of the running receiver application or {@code null}.
     */
    public String getTransportId() {
        return transportId;
    }

    /**
     * @return the session id of the running receiver application or {@code null}.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * @return the application id of the running receiver application or {@code null}.
     */
    public String getApplicationId() {
        return applicationId;
    }

    public CastReceiverStatus getReceiverStatus() {
        return receiverStatus;
    }

    public CastMediaStatus getMediaStatus() {
        return mediaStatus;
    }

    public boolean isConnected() {
        return connected;
    }

    public List<String> getRegisteredNamespaces() {
        return Collections.unmodifiableList(new ArrayList<String>(registeredNamespaces));
    }

    /**
     * Establishes the TLS channel and connects to the receiver namespace. This method blocks until
     * the channel is usable and must not be called on the main thread.
     *
     * @throws IOException if the device cannot be reached.
     */
    public void connect() throws IOException {
        synchronized (stateLock) {
            if (running) {
                return;
            }
            SSLSocket newSocket = CastTls.createSocket(host, port, CastProtocol.CONNECT_TIMEOUT_MS, certificateFingerprint);
            socket = newSocket;
            try {
                outputStream = newSocket.getOutputStream();
            } catch (IOException e) {
                closeSocketQuietly(newSocket);
                socket = null;
                throw e;
            }
            // "sender-0" is the source id used by the reference Cast senders. The virtual
            // connection is scoped to the TLS channel, so the id does not need to be unique across
            // devices.
            sourceId = "sender-0";
            running = true;
            connected = true;
            readerThread = new Thread(new Reader(), "CastClient-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            heartbeatThread = new Thread(new Heartbeat(), "CastClient-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
        }
        try {
            sendConnect(CastProtocol.RECEIVER_ID);
            requestReceiverStatus();
        } catch (IOException e) {
            disconnectInternal(DISCONNECT_REASON_NETWORK_ERROR);
            throw e;
        }
        if (listener != null) {
            listener.onConnected();
        }
    }

    /**
     * Closes the channel. Calling this method on a closed client has no effect.
     */
    public void disconnect() {
        disconnectInternal(DISCONNECT_REASON_USER_REQUEST);
    }

    private void disconnectInternal(int reason) {
        boolean wasRunning;
        Thread reader;
        Thread heartbeat;
        synchronized (stateLock) {
            wasRunning = running;
            running = false;
            connected = false;
            reader = readerThread;
            heartbeat = heartbeatThread;
            readerThread = null;
            heartbeatThread = null;
        }
        if (!wasRunning) {
            return;
        }
        if (transportId != null) {
            try {
                sendJson(transportId, CastProtocol.NAMESPACE_CONNECTION, messageOfType(TYPE_CLOSE));
            } catch (IOException ignored) {
                // The channel is going away anyway.
            }
        }
        closeSocketQuietly(socket);
        socket = null;
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
        transportId = null;
        sessionId = null;
        applicationId = null;
        mediaStatus = null;
        if (listener != null) {
            listener.onDisconnected(reason);
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    // region Receiver namespace

    /**
     * Launches the given receiver application.
     *
     * @param appId              the application id, e.g. {@code CC1AD845} for the default media
     *                           receiver.
     * @param relaunchIfRunning  whether a running instance should be restarted.
     * @return the request id of the launch request.
     */
    public long launchApplication(String appId, boolean relaunchIfRunning) throws IOException {
        requireAppId(appId);
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_LAUNCH);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put("appId", appId);
        if (relaunchIfRunning) {
            payload.put("relaunchIfRunning", true);
        }
        sendJson(CastProtocol.RECEIVER_ID, CastProtocol.NAMESPACE_RECEIVER, payload);
        return requestId;
    }

    /**
     * Joins an already running application session of the given device.
     *
     * @param appId     the application id.
     * @param sessionId the session id of the running application.
     * @return the request id of the request.
     */
    public long joinApplication(String appId, String sessionId) throws IOException {
        requireAppId(appId);
        if (sessionId == null || sessionId.length() == 0) {
            return launchApplication(appId, false);
        }
        CastReceiverStatus.Application running = receiverStatus == null ? null : receiverStatus.findApplication(appId);
        if (running != null && running.transportId != null) {
            // The application is already known to us, connect to its transport instead of launching
            // a second instance.
            connectToApplication(running);
            return nextRequestId();
        }
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_LAUNCH);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put("appId", appId);
        payload.put(KEY_SESSION_ID, sessionId);
        sendJson(CastProtocol.RECEIVER_ID, CastProtocol.NAMESPACE_RECEIVER, payload);
        return requestId;
    }

    /**
     * Stops the given application session.
     *
     * @param sessionId the session to stop, {@code null} stops the currently running one.
     * @return the request id of the request.
     */
    public long stopApplication(String sessionId) throws IOException {
        String effectiveSessionId = sessionId != null ? sessionId : this.sessionId;
        if (effectiveSessionId == null) {
            throw new CastProtocolException("No application session running");
        }
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_STOP);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put(KEY_SESSION_ID, effectiveSessionId);
        sendJson(CastProtocol.RECEIVER_ID, CastProtocol.NAMESPACE_RECEIVER, payload);
        return requestId;
    }

    /**
     * Asks the device for its current receiver status.
     *
     * @return the request id of the request.
     */
    public long requestReceiverStatus() throws IOException {
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_GET_STATUS);
        payload.put(KEY_REQUEST_ID, requestId);
        sendJson(CastProtocol.RECEIVER_ID, CastProtocol.NAMESPACE_RECEIVER, payload);
        return requestId;
    }

    /**
     * Sets the device volume.
     *
     * @param level volume level between {@code 0.0} and {@code 1.0}.
     * @return the request id of the request.
     */
    public long setVolume(double level) throws IOException {
        CastVolume current = receiverStatus == null ? null : receiverStatus.volume;
        boolean muted = current != null && current.muted;
        return setVolume(new CastVolume(level, muted));
    }

    /**
     * Mutes or unmutes the device, keeping the current volume level.
     *
     * @return the request id of the request.
     */
    public long setMuted(boolean muted) throws IOException {
        CastVolume current = receiverStatus == null ? new CastVolume(1.0, muted) : receiverStatus.volume;
        return setVolume(current.withMuted(muted));
    }

    /**
     * Changes the device volume relatively, e.g. {@code 0.05} to raise it by five percent.
     *
     * @return the request id of the request.
     */
    public long updateVolume(double delta) throws IOException {
        CastVolume current = receiverStatus == null ? new CastVolume(1.0, false) : receiverStatus.volume;
        return setVolume(new CastVolume(current.level + delta, current.muted));
    }

    /**
     * Sets the device volume to the given absolute value.
     *
     * @return the request id of the request.
     */
    public long setVolume(CastVolume volume) throws IOException {
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_SET_VOLUME);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put("volume", CastVolume.toJson(volume, true));
        sendJson(CastProtocol.RECEIVER_ID, CastProtocol.NAMESPACE_RECEIVER, payload);
        return requestId;
    }

    // endregion

    // region Media namespace

    /**
     * Loads a media item into the running receiver application.
     *
     * @param media         the media to load.
     * @param autoplay      whether playback should start immediately.
     * @param startPosition position to start playback at, in seconds.
     * @return the request id of the request.
     * @throws CastProtocolException if no application is running.
     */
    public long loadMedia(CastMediaInfo media, boolean autoplay, double startPosition) throws IOException {
        String transport = requireTransportId();
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_LOAD);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put(KEY_MEDIA, media.toJson());
        payload.put("autoplay", autoplay);
        if (startPosition > 0) {
            payload.put("currentTime", startPosition);
        }
        sendJson(transport, CastProtocol.NAMESPACE_MEDIA, payload);
        return requestId;
    }

    /**
     * Resumes playback of the current media item.
     *
     * @return the request id of the request.
     */
    public long play() throws IOException {
        return sendMediaCommand(TYPE_PLAY);
    }

    /**
     * Pauses playback of the current media item.
     *
     * @return the request id of the request.
     */
    public long pause() throws IOException {
        return sendMediaCommand(TYPE_PAUSE);
    }

    /**
     * Seeks inside the current media item.
     *
     * @param position the position to seek to, in seconds.
     * @return the request id of the request.
     */
    public long seek(double position) throws IOException {
        String transport = requireTransportId();
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_SEEK);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put(KEY_MEDIA_SESSION_ID, requireMediaSessionId());
        payload.put("currentTime", position);
        payload.put("resumeState", CastProtocol.PLAYER_STATE_PLAYING.equals(currentPlayerState())
                ? "PLAYBACK_START" : "PLAYBACK_PAUSE");
        sendJson(transport, CastProtocol.NAMESPACE_MEDIA, payload);
        return requestId;
    }

    /**
     * Stops the current playback and unloads the media item, keeping the application running.
     *
     * @return the request id of the request.
     */
    public long stopMedia() throws IOException {
        String transport = requireTransportId();
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_STOP);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put(KEY_MEDIA_SESSION_ID, requireMediaSessionId());
        sendJson(transport, CastProtocol.NAMESPACE_MEDIA, payload);
        return requestId;
    }

    /**
     * Sets the volume of the media stream (only supported by some receivers).
     *
     * @param volume the new stream volume.
     * @return the request id of the request.
     */
    public long setStreamVolume(CastVolume volume) throws IOException {
        String transport = requireTransportId();
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_SET_VOLUME);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put(KEY_MEDIA_SESSION_ID, requireMediaSessionId());
        payload.put("volume", CastVolume.toJson(volume, true));
        sendJson(transport, CastProtocol.NAMESPACE_MEDIA, payload);
        return requestId;
    }

    /**
     * Asks the running application for its current media status.
     *
     * @return the request id of the request.
     */
    public long requestMediaStatus() throws IOException {
        if (transportId == null) {
            return -1;
        }
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(TYPE_GET_STATUS);
        payload.put(KEY_REQUEST_ID, requestId);
        sendJson(transportId, CastProtocol.NAMESPACE_MEDIA, payload);
        return requestId;
    }

    private long sendMediaCommand(String type) throws IOException {
        String transport = requireTransportId();
        long requestId = nextRequestId();
        JSONObject payload = messageOfType(type);
        payload.put(KEY_REQUEST_ID, requestId);
        payload.put(KEY_MEDIA_SESSION_ID, requireMediaSessionId());
        sendJson(transport, CastProtocol.NAMESPACE_MEDIA, payload);
        return requestId;
    }

    // endregion

    // region Custom namespaces

    /**
     * Registers a namespace to be connected when an application is launched. The messages received
     * in this namespace are delivered via {@link CastClientListener#onMessageReceived}.
     */
    public void registerNamespace(String namespace) {
        if (namespace == null || registeredNamespaces.contains(namespace)) {
            return;
        }
        registeredNamespaces.add(namespace);
    }

    public void unregisterNamespace(String namespace) {
        registeredNamespaces.remove(namespace);
    }

    /**
     * Sends an arbitrary payload to the given namespace of the running application.
     *
     * @param requestId the request id to embed, may be {@code 0} to omit it.
     * @return the effective request id.
     */
    public long sendMessage(String namespace, String payload, long requestId) throws IOException {
        String transport = requireTransportId();
        long effectiveRequestId = requestId > 0 ? requestId : nextRequestId();
        JSONObject json;
        try {
            json = new JSONObject(payload);
        } catch (JSONException e) {
            throw new CastProtocolException("Payload is not valid JSON", e);
        }
        if (effectiveRequestId > 0) {
            json.put(KEY_REQUEST_ID, effectiveRequestId);
        }
        sendJson(transport, namespace, json);
        return effectiveRequestId;
    }

    /**
     * Sends an arbitrary payload to the running application, the request id is generated.
     */
    public long sendMessage(String namespace, JSONObject payload) throws IOException {
        String transport = requireTransportId();
        long requestId = nextRequestId();
        payload.put(KEY_REQUEST_ID, requestId);
        sendJson(transport, namespace, payload);
        return requestId;
    }

    // endregion

    // region Protocol plumbing

    private long nextRequestId() {
        return requestIds.getAndIncrement();
    }

    private void sendConnect(String destinationId) throws IOException {
        sendJson(destinationId, CastProtocol.NAMESPACE_CONNECTION, messageOfType(TYPE_CONNECT));
    }

    private void sendJson(String destinationId, String namespace, JSONObject payload) throws IOException {
        if (!running) {
            throw new CastProtocolException("Not connected to " + host);
        }
        CastMessage message = new CastMessage(sourceId, destinationId, namespace, payload.toString());
        synchronized (writeLock) {
            OutputStream output = outputStream;
            if (output == null) {
                throw new CastProtocolException("Connection to " + host + " is closed");
            }
            try {
                CastMessageCodec.write(output, message);
            } catch (IOException e) {
                Log.w(TAG, "Failed to send " + message + ": " + e.getMessage());
                disconnectInternal(DISCONNECT_REASON_NETWORK_ERROR);
                throw e;
            }
        }
    }

    private static JSONObject messageOfType(String type) {
        JSONObject payload = new JSONObject();
        try {
            payload.put(KEY_TYPE, type);
        } catch (JSONException e) {
            // Cannot happen for a plain string value.
        }
        return payload;
    }

    private static void requireAppId(String appId) throws CastProtocolException {
        if (appId == null || appId.length() == 0) {
            throw new CastProtocolException("No application id given");
        }
    }

    private String requireTransportId() throws CastProtocolException {
        if (transportId == null) {
            throw new CastProtocolException("No receiver application running");
        }
        return transportId;
    }

    private long requireMediaSessionId() throws CastProtocolException {
        CastMediaStatus status = mediaStatus;
        if (status == null || status.mediaSessionId == 0) {
            throw new CastProtocolException("No media session running");
        }
        return status.mediaSessionId;
    }

    private String currentPlayerState() {
        CastMediaStatus status = mediaStatus;
        return status == null ? CastProtocol.PLAYER_STATE_IDLE : status.playerState;
    }

    private void closeSocketQuietly(SSLSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to do.
        }
    }

    // endregion

    // region Receiving

    private final class Reader implements Runnable {
        @Override
        public void run() {
            SSLSocket currentSocket = socket;
            InputStream inputStream;
            try {
                inputStream = currentSocket.getInputStream();
            } catch (IOException e) {
                Log.w(TAG, "Could not open input stream: " + e.getMessage());
                disconnectInternal(DISCONNECT_REASON_NETWORK_ERROR);
                return;
            }
            while (running) {
                try {
                    CastMessage message = CastMessageCodec.read(inputStream);
                    handleMessage(message);
                } catch (IOException e) {
                    if (running) {
                        Log.w(TAG, "Connection to " + host + " lost: " + e.getMessage());
                        disconnectInternal(DISCONNECT_REASON_NETWORK_ERROR);
                    }
                    return;
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error while handling message", e);
                }
            }
        }
    }

    private final class Heartbeat implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    Thread.sleep(CastProtocol.HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) {
                    return;
                }
                try {
                    sendJson(CastProtocol.RECEIVER_ID, CastProtocol.NAMESPACE_HEARTBEAT, messageOfType(TYPE_PING));
                    String transport = transportId;
                    if (transport != null) {
                        sendJson(transport, CastProtocol.NAMESPACE_HEARTBEAT, messageOfType(TYPE_PING));
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Heartbeat failed: " + e.getMessage());
                    return;
                }
            }
        }
    }

    private void handleMessage(CastMessage message) {
        String namespace = message.getNamespace();
        if (message.isBinary()) {
            listener.onBinaryMessageReceived(namespace, message.getPayloadBinary());
            return;
        }
        String payload = message.getPayloadUtf8();
        JSONObject json;
        try {
            json = payload == null || payload.length() == 0 ? new JSONObject() : new JSONObject(payload);
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed payload in " + namespace + ": " + payload);
            listener.onMessageReceived(namespace, payload);
            return;
        }
        String type = CastJson.type(json);

        if (CastProtocol.NAMESPACE_HEARTBEAT.equals(namespace)) {
            if (TYPE_PING.equals(type)) {
                try {
                    sendJson(message.getSourceId(), CastProtocol.NAMESPACE_HEARTBEAT, messageOfType(TYPE_PONG));
                } catch (IOException e) {
                    Log.w(TAG, "Could not answer heartbeat: " + e.getMessage());
                }
            }
            return;
        }

        if (CastProtocol.NAMESPACE_CONNECTION.equals(namespace)) {
            if (TYPE_CLOSE.equals(type) && CastProtocol.RECEIVER_ID.equals(message.getSourceId())) {
                Log.d(TAG, "Receiver closed the connection");
                disconnectInternal(DISCONNECT_REASON_CLOSED_BY_DEVICE);
            }
            return;
        }

        if (CastProtocol.NAMESPACE_RECEIVER.equals(namespace)) {
            handleReceiverMessage(type, json);
            return;
        }

        if (CastProtocol.NAMESPACE_MEDIA.equals(namespace)) {
            handleMediaMessage(type, json);
            return;
        }

        listener.onMessageReceived(namespace, payload);
    }

    private void handleReceiverMessage(String type, JSONObject json) {
        if (TYPE_RECEIVER_STATUS.equals(type)) {
            CastReceiverStatus status = CastReceiverStatus.fromJson(CastJson.optObject(json, KEY_STATUS),
                    CastJson.optLong(json, KEY_REQUEST_ID, 0L));
            receiverStatus = status;
            CastReceiverStatus.Application application = status.getRunningApplication();
            if (application != null) {
                applicationId = application.appId;
                sessionId = application.sessionId;
                if (application.transportId != null && !application.transportId.equals(transportId)) {
                    connectToApplication(application);
                }
            } else {
                if (transportId != null) {
                    Log.d(TAG, "Application is not running any more");
                }
                transportId = null;
                sessionId = null;
                applicationId = null;
                if (mediaStatus != null) {
                    mediaStatus = null;
                    listener.onMediaStatus(null);
                }
            }
            listener.onReceiverStatus(status);
            return;
        }
        if ("LAUNCH_ERROR".equals(type) || "INVALID_REQUEST".equals(type)
                || "LOAD_FAILED".equals(type) || "LOAD_CANCELLED".equals(type)
                || "INVALID_PLAYER_STATE".equals(type)) {
            String reason = CastJson.optString(json, "reason", type);
            Log.w(TAG, "Receiver rejected request: " + type + " (" + reason + ")");
            long requestId = CastJson.optLong(json, KEY_REQUEST_ID, 0L);
            listener.onRequestFailed(requestId, reason);
            return;
        }
        listener.onMessageReceived(CastProtocol.NAMESPACE_RECEIVER, json.toString());
    }

    private void handleMediaMessage(String type, JSONObject json) {
        if (TYPE_MEDIA_STATUS.equals(type)) {
            JSONArray array = json.optJSONArray(KEY_STATUS);
            if (array == null || array.length() == 0) {
                if (mediaStatus != null) {
                    mediaStatus = null;
                    listener.onMediaStatus(null);
                }
                return;
            }
            CastMediaStatus status = null;
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                CastMediaStatus candidate = CastMediaStatus.fromJson(entry);
                if (candidate == null) {
                    continue;
                }
                if (status == null || !candidate.isIdle()) {
                    status = candidate;
                }
            }
            mediaStatus = status;
            listener.onMediaStatus(status);
            return;
        }
        if ("LOAD_FAILED".equals(type) || "LOAD_CANCELLED".equals(type)
                || "INVALID_REQUEST".equals(type) || "INVALID_PLAYER_STATE".equals(type)) {
            String reason = CastJson.optString(json, "reason", type);
            long requestId = CastJson.optLong(json, KEY_REQUEST_ID, 0L);
            Log.w(TAG, "Media request failed: " + type + " (" + reason + ")");
            listener.onRequestFailed(requestId, reason);
            return;
        }
        listener.onMessageReceived(CastProtocol.NAMESPACE_MEDIA, json.toString());
    }

    private void connectToApplication(CastReceiverStatus.Application application) {
        if (application == null || application.transportId == null) {
            return;
        }
        String transport = application.transportId;
        Log.d(TAG, "Connecting to application transport " + transport + " (" + application.appId + ")");
        transportId = transport;
        sessionId = application.sessionId;
        applicationId = application.appId;
        try {
            sendConnect(transport);
            sendJson(transport, CastProtocol.NAMESPACE_MEDIA, messageOfType(TYPE_GET_STATUS));
        } catch (IOException e) {
            Log.w(TAG, "Could not connect to application transport: " + e.getMessage());
        }
    }

    // endregion
}
