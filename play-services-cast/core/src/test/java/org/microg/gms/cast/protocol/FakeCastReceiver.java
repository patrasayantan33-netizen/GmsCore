/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManager;

/**
 * A minimal, but protocol correct, implementation of a Cast device which is used by the unit tests
 * to exercise {@link CastClient} without any hardware.
 * <p>
 * The fake device speaks the Cast v2 wire format over TLS, implements the receiver, connection,
 * heartbeat and media namespaces and records everything it receives so that tests can assert on the
 * messages that were sent by the client.
 */
public class FakeCastReceiver {
    public static final String DEFAULT_RECEIVER_APP_ID = "CC1AD845";

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final List<CastMessage> receivedMessages = new CopyOnWriteArrayList<CastMessage>();
    private final CountDownLatch connected = new CountDownLatch(1);

    private volatile Socket clientSocket;
    private volatile OutputStream outputStream;
    private volatile String clientId;
    private volatile String applicationId;
    private volatile String sessionId;
    private volatile String transportId;
    private volatile double volume = 0.5;
    private volatile boolean muted;
    private volatile boolean running = true;
    private volatile long mediaSessionId = 1;
    private volatile String playerState = CastProtocol.PLAYER_STATE_IDLE;
    private volatile double currentTime;
    private volatile String contentId;
    private volatile String contentType = "video/mp4";
    private volatile String metadataTitle;
    private volatile boolean rejectNextMediaCommand;
    private volatile boolean rejectLaunch;

    public FakeCastReceiver() throws Exception {
        SSLContext context = createSslContext();
        SSLServerSocket socket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        socket.setReuseAddress(true);
        this.serverSocket = socket;
        this.acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "FakeCastReceiver-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * Blocks until a sender connected to this fake device.
     */
    public boolean awaitConnection(long timeout, TimeUnit unit) throws InterruptedException {
        return connected.await(timeout, unit);
    }

    public List<CastMessage> getReceivedMessages() {
        return new ArrayList<CastMessage>(receivedMessages);
    }

    public void clearReceivedMessages() {
        receivedMessages.clear();
    }

    /**
     * @return the payloads of all received messages in the given namespace, in order.
     */
    public List<JSONObject> getReceivedPayloads(String namespace) {
        List<JSONObject> payloads = new ArrayList<JSONObject>();
        for (CastMessage message : receivedMessages) {
            if (message.getNamespace().equals(namespace)) {
                try {
                    payloads.add(new JSONObject(message.getPayloadUtf8()));
                } catch (JSONException ignored) {
                    // Not a JSON payload.
                }
            }
        }
        return payloads;
    }

    /**
     * @return the last received payload of the given namespace with the given type or {@code null}.
     */
    public JSONObject findLastPayload(String namespace, String type) {
        JSONObject result = null;
        for (JSONObject payload : getReceivedPayloads(namespace)) {
            if (type.equals(CastJson.type(payload))) {
                result = payload;
            }
        }
        return result;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTransportId() {
        return transportId;
    }

    public String getPlayerState() {
        return playerState;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public double getVolume() {
        return volume;
    }

    public boolean isMuted() {
        return muted;
    }

    /**
     * Makes the device reject the next media command with {@code INVALID_PLAYER_STATE}.
     */
    public void rejectNextMediaCommand() {
        this.rejectNextMediaCommand = true;
    }

    /**
     * Waits until a payload of the given namespace and type was received.
     *
     * @return the payload or {@code null} if it did not arrive within the timeout.
     */
    public JSONObject awaitPayload(String namespace, String type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JSONObject payload = findLastPayload(namespace, type);
            if (payload != null) {
                return payload;
            }
            Thread.sleep(10);
        }
        return null;
    }

    public void setRejectLaunch(boolean rejectLaunch) {
        this.rejectLaunch = rejectLaunch;
    }

    /**
     * Sends a {@code PING} to the sender, the client is expected to answer with {@code PONG}.
     */
    public void pingSender() throws IOException {
        send(clientId, CastProtocol.NAMESPACE_HEARTBEAT, payloadWithType("PING"));
    }

    /**
     * Sends an arbitrary payload, as a real receiver application would do in a custom namespace.
     */
    public void sendCustomMessage(String namespace, JSONObject payload) throws IOException {
        send(transportId, namespace, payload);
    }

    /**
     * Drops the connection to the sender.
     */
    public void closeConnection() {
        try {
            Socket socket = clientSocket;
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Nothing to do.
        }
    }

    public void shutdown() {
        running = false;
        closeConnection();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Nothing to do.
        }
        acceptThread.interrupt();
    }

    // region Device behaviour

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                handleClient(socket);
            } catch (IOException e) {
                if (running) {
                    // Accept failed, wait for the next client.
                }
            }
        }
    }

    private void handleClient(Socket socket) throws IOException {
        clientSocket = socket;
        outputStream = socket.getOutputStream();
        InputStream inputStream = socket.getInputStream();
        while (running && !socket.isClosed()) {
            CastMessage message;
            try {
                message = CastMessageCodec.read(inputStream);
            } catch (IOException e) {
                return;
            }
            receivedMessages.add(message);
            try {
                handle(message);
            } catch (JSONException e) {
                throw new IOException("Malformed payload received: " + message, e);
            }
        }
    }

    private void handle(CastMessage message) throws IOException, JSONException {
        String namespace = message.getNamespace();
        JSONObject payload = message.getPayloadUtf8() == null || message.getPayloadUtf8().length() == 0
                ? new JSONObject() : new JSONObject(message.getPayloadUtf8());
        String type = CastJson.type(payload);

        if (CastProtocol.NAMESPACE_CONNECTION.equals(namespace)) {
            if ("CONNECT".equals(type)) {
                if (CastProtocol.RECEIVER_ID.equals(message.getDestinationId())) {
                    clientId = message.getSourceId();
                    connected.countDown();
                } else if (message.getDestinationId().equals(transportId)) {
                    clientId = message.getSourceId();
                }
            } else if ("CLOSE".equals(type)) {
                if (message.getDestinationId().equals(transportId)) {
                    // Sender detached from the application but stays connected.
                }
            }
            return;
        }

        if (CastProtocol.NAMESPACE_HEARTBEAT.equals(namespace)) {
            if ("PING".equals(type)) {
                send(message.getSourceId(), CastProtocol.NAMESPACE_HEARTBEAT, payloadWithType("PONG"));
            }
            return;
        }

        if (CastProtocol.NAMESPACE_RECEIVER.equals(namespace)) {
            handleReceiver(payload, type);
            return;
        }

        if (CastProtocol.NAMESPACE_MEDIA.equals(namespace)) {
            handleMedia(payload, type);
        }
    }

    private void handleReceiver(JSONObject payload, String type) throws IOException, JSONException {
        long requestId = CastJson.optLong(payload, "requestId", 0L);
        if ("GET_STATUS".equals(type)) {
            sendReceiverStatus(requestId);
            return;
        }
        if ("LAUNCH".equals(type)) {
            if (rejectLaunch) {
                JSONObject error = payloadWithType("LAUNCH_ERROR");
                error.put("requestId", requestId);
                error.put("reason", "NOT_FOUND");
                send(clientId, CastProtocol.NAMESPACE_RECEIVER, error);
                return;
            }
            applicationId = payload.optString("appId", DEFAULT_RECEIVER_APP_ID);
            sessionId = "session-" + applicationId;
            transportId = "web-" + mediaSessionId;
            playerState = CastProtocol.PLAYER_STATE_IDLE;
            sendReceiverStatus(requestId);
            return;
        }
        if ("STOP".equals(type)) {
            if (payload.has("mediaSessionId")) {
                playerState = CastProtocol.PLAYER_STATE_IDLE;
                sendMediaStatus(requestId, mediaSessionId);
                return;
            }
            applicationId = null;
            sessionId = null;
            transportId = null;
            playerState = CastProtocol.PLAYER_STATE_IDLE;
            contentId = null;
            sendReceiverStatus(requestId);
            return;
        }
        if ("SET_VOLUME".equals(type)) {
            JSONObject volumeJson = CastJson.optObject(payload, "volume");
            if (volumeJson != null) {
                volume = CastJson.optDouble(volumeJson, "level", volume);
                muted = CastJson.optBoolean(volumeJson, "muted", muted);
            }
            sendReceiverStatus(requestId);
        }
    }

    private void handleMedia(JSONObject payload, String type) throws IOException, JSONException {
        long requestId = CastJson.optLong(payload, "requestId", 0L);
        if ("GET_STATUS".equals(type)) {
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        if (rejectNextMediaCommand && !"GET_STATUS".equals(type)) {
            rejectNextMediaCommand = false;
            JSONObject error = payloadWithType("INVALID_PLAYER_STATE");
            error.put("requestId", requestId);
            error.put("reason", "INVALID_PLAYER_STATE");
            send(transportId, CastProtocol.NAMESPACE_MEDIA, error);
            return;
        }
        if ("LOAD".equals(type)) {
            JSONObject media = CastJson.optObject(payload, "media");
            if (media == null) {
                JSONObject error = payloadWithType("LOAD_FAILED");
                error.put("requestId", requestId);
                error.put("reason", "INVALID_REQUEST");
                send(transportId, CastProtocol.NAMESPACE_MEDIA, error);
                return;
            }
            contentId = CastJson.optString(media, "contentId");
            contentType = CastJson.optString(media, "contentType", contentType);
            JSONObject metadata = CastJson.optObject(media, "metadata");
            metadataTitle = metadata == null ? null : CastJson.optString(metadata, "title");
            currentTime = CastJson.optDouble(payload, "currentTime", 0.0);
            playerState = payload.optBoolean("autoplay", true)
                    ? CastProtocol.PLAYER_STATE_PLAYING : CastProtocol.PLAYER_STATE_PAUSED;
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        if ("PLAY".equals(type)) {
            playerState = CastProtocol.PLAYER_STATE_PLAYING;
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        if ("PAUSE".equals(type)) {
            playerState = CastProtocol.PLAYER_STATE_PAUSED;
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        if ("SEEK".equals(type)) {
            currentTime = CastJson.optDouble(payload, "currentTime", currentTime);
            String resumeState = CastJson.optString(payload, "resumeState", "PLAYBACK_START");
            playerState = "PLAYBACK_PAUSE".equals(resumeState)
                    ? CastProtocol.PLAYER_STATE_PAUSED : CastProtocol.PLAYER_STATE_PLAYING;
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        if ("STOP".equals(type)) {
            playerState = CastProtocol.PLAYER_STATE_IDLE;
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        if ("SET_VOLUME".equals(type)) {
            sendMediaStatus(requestId, mediaSessionId);
            return;
        }
        JSONObject error = payloadWithType("INVALID_REQUEST");
        error.put("requestId", requestId);
        error.put("reason", "INVALID_COMMAND");
        send(transportId, CastProtocol.NAMESPACE_MEDIA, error);
    }

    // endregion

    // region Sending

    private void sendReceiverStatus(long requestId) throws IOException, JSONException {
        JSONObject status = new JSONObject();
        JSONObject volumeJson = new JSONObject();
        volumeJson.put("level", volume);
        volumeJson.put("muted", muted);
        status.put("volume", volumeJson);
        status.put("isActiveInput", true);
        status.put("isStandBy", false);
        JSONArray applications = new JSONArray();
        if (applicationId != null) {
            JSONObject application = new JSONObject();
            application.put("appId", applicationId);
            application.put("sessionId", sessionId);
            application.put("transportId", transportId);
            application.put("displayName", "Fake Receiver");
            application.put("statusText", "Ready To Cast");
            application.put("appType", "WEB");
            application.put("isIdleScreen", false);
            JSONArray namespaces = new JSONArray();
            JSONObject mediaNamespace = new JSONObject();
            mediaNamespace.put("name", CastProtocol.NAMESPACE_MEDIA);
            namespaces.put(mediaNamespace);
            application.put("namespaces", namespaces);
            applications.put(application);
        } else {
            JSONObject idle = new JSONObject();
            idle.put("appId", "E8C28D3C");
            idle.put("sessionId", "idle-session");
            idle.put("transportId", "idle-transport");
            idle.put("displayName", "Backdrop");
            idle.put("isIdleScreen", true);
            applications.put(idle);
        }
        status.put("applications", applications);

        JSONObject payload = payloadWithType("RECEIVER_STATUS");
        if (requestId > 0) {
            payload.put("requestId", requestId);
        }
        payload.put("status", status);
        send(clientId, CastProtocol.NAMESPACE_RECEIVER, payload);
    }

    private void sendMediaStatus(long requestId, long sessionIdOfMedia) throws IOException, JSONException {
        JSONObject status = new JSONObject();
        status.put("mediaSessionId", sessionIdOfMedia);
        status.put("playbackRate", CastProtocol.PLAYER_STATE_PLAYING.equals(playerState) ? 1 : 0);
        status.put("playerState", playerState);
        status.put("currentTime", currentTime);
        status.put("supportedMediaCommands", 15);
        if (requestId > 0) {
            status.put("requestId", requestId);
        }
        if (contentId != null) {
            JSONObject media = new JSONObject();
            media.put("contentId", contentId);
            media.put("contentType", contentType);
            media.put("streamType", CastProtocol.STREAM_TYPE_BUFFERED);
            media.put("duration", 3600.0);
            if (metadataTitle != null) {
                JSONObject metadata = new JSONObject();
                metadata.put("metadataType", CastMediaMetadata.TYPE_MOVIE);
                metadata.put("title", metadataTitle);
                metadata.put("artist", "microG");
                media.put("metadata", metadata);
            }
            status.put("media", media);
        } else {
            status.put("idleReason", CastProtocol.IDLE_REASON_FINISHED);
        }
        JSONArray array = new JSONArray();
        array.put(status);
        JSONObject payload = payloadWithType("MEDIA_STATUS");
        payload.put("status", array);
        send(transportId != null ? transportId : clientId, CastProtocol.NAMESPACE_MEDIA, payload);
    }

    private void send(String destinationId, String namespace, JSONObject payload) throws IOException {
        OutputStream output = outputStream;
        if (output == null) {
            throw new IOException("No client connected");
        }
        CastMessage message = new CastMessage(CastProtocol.RECEIVER_ID, destinationId, namespace, payload.toString());
        synchronized (this) {
            CastMessageCodec.write(output, message);
        }
    }

    private static JSONObject payloadWithType(String type) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", type);
        } catch (JSONException ignored) {
            // Cannot happen for a plain string.
        }
        return payload;
    }

    private static SSLContext createSslContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        InputStream stream = FakeCastReceiver.class.getClassLoader().getResourceAsStream("cast-test.p12");
        if (stream == null) {
            throw new IllegalStateException("Test keystore cast-test.p12 is missing");
        }
        try {
            keyStore.load(stream, "cast-test".toCharArray());
        } finally {
            stream.close();
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "cast-test".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), new TrustManager[0], null);
        return context;
    }

    // endregion
}
