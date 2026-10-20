/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cast.protocol;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration test of {@link CastClient} against {@link FakeCastReceiver}, i.e. a complete Cast v2
 * session including discovery independent connection setup, application launch, media control,
 * volume control and session teardown.
 */
public class CastClientTest {
    private static final long TIMEOUT_MS = 10_000;

    private FakeCastReceiver receiver;
    private RecordingListener listener;
    private CastClient client;

    @Before
    public void setUp() throws Exception {
        receiver = new FakeCastReceiver();
        listener = new RecordingListener();
        client = new CastClient("127.0.0.1", receiver.getPort(), listener);
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.disconnect();
        }
        receiver.shutdown();
    }

    @Test
    public void connectsAndReadsReceiverStatus() throws Exception {
        client.connect();

        assertTrue("Device did not see the sender", receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        CastReceiverStatus status = listener.awaitReceiverStatus();
        assertNotNull(status);
        assertEquals(0.5, status.volume.level, 0.001);
        assertNull(client.getTransportId());

        JSONObject getStatus = receiver.findLastPayload(CastProtocol.NAMESPACE_RECEIVER, "GET_STATUS");
        assertNotNull("Client did not request the receiver status", getStatus);
        JSONObject connect = receiver.findLastPayload(CastProtocol.NAMESPACE_CONNECTION, "CONNECT");
        assertNotNull("Client did not connect to the receiver namespace", connect);
    }

    @Test
    public void launchesApplicationAndConnectsToItsTransport() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        long requestId = client.launchApplication(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, false);
        assertTrue(requestId > 0);
        awaitTransport();

        assertEquals(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, client.getApplicationId());
        assertEquals(receiver.getSessionId(), client.getSessionId());
        assertEquals(receiver.getTransportId(), client.getTransportId());

        JSONObject launch = receiver.findLastPayload(CastProtocol.NAMESPACE_RECEIVER, "LAUNCH");
        assertNotNull(launch);
        assertEquals(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, launch.getString("appId"));
        assertEquals(requestId, launch.getLong("requestId"));
    }

    @Test
    public void loadsMediaAndControlsPlayback() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.launchApplication(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, false);
        awaitTransport();

        CastMediaInfo media = CastMediaInfo.builder("https://example.org/movie.mp4", "video/mp4")
                .setDuration(3600)
                .setMetadata(CastMediaMetadata.builder(CastMediaMetadata.TYPE_MOVIE)
                        .setTitle("Big Buck Bunny")
                        .setArtist("Blender Foundation")
                        .addImage("https://example.org/poster.jpg", 640, 360)
                        .build())
                .build();
        listener.clearMediaStatus();
        client.loadMedia(media, true, 0);

        CastMediaStatus status = listener.awaitMediaStatusWhere(CastProtocol.PLAYER_STATE_PLAYING);
        assertNotNull(status);
        assertEquals(CastProtocol.PLAYER_STATE_PLAYING, status.playerState);
        assertTrue(status.isPlaying());
        assertEquals("Big Buck Bunny", status.media.metadata.title);
        assertEquals(3_600_000, status.getDurationMillis());
        assertTrue(status.supportsCommand(CastProtocol.MEDIA_COMMAND_PAUSE));

        JSONObject load = receiver.awaitPayload(CastProtocol.NAMESPACE_MEDIA, "LOAD", TIMEOUT_MS);
        assertNotNull(load);
        assertEquals("https://example.org/movie.mp4", load.getJSONObject("media").getString("contentId"));
        assertTrue(load.getBoolean("autoplay"));

        listener.clearMediaStatus();
        client.pause();
        CastMediaStatus paused = listener.awaitMediaStatusWhere(CastProtocol.PLAYER_STATE_PAUSED);
        assertNotNull(paused);
        assertTrue(paused.isPaused());

        listener.clearMediaStatus();
        client.play();
        assertNotNull(listener.awaitMediaStatusWhere(CastProtocol.PLAYER_STATE_PLAYING));

        listener.clearMediaStatus();
        client.seek(42.5);
        CastMediaStatus seeked = listener.awaitMediaStatusWhere(CastProtocol.PLAYER_STATE_PLAYING);
        assertEquals(42.5, seeked.currentTime, 0.001);
        assertEquals(42_500, seeked.getPositionMillis());
        JSONObject seek = receiver.awaitPayload(CastProtocol.NAMESPACE_MEDIA, "SEEK", TIMEOUT_MS);
        assertNotNull(seek);
        assertEquals(42.5, seek.getDouble("currentTime"), 0.001);

        listener.clearMediaStatus();
        client.stopMedia();
        assertNotNull(listener.awaitMediaStatusWhere(CastProtocol.PLAYER_STATE_IDLE));
    }

    @Test
    public void controlsDeviceVolume() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        listener.awaitReceiverStatus();

        client.setVolume(0.3);
        CastReceiverStatus status = listener.awaitReceiverStatusWhereVolume(0.3);
        assertEquals(0.3, status.volume.level, 0.001);
        assertTrue(receiver.getVolume() == 0.3);

        client.updateVolume(0.1);
        status = listener.awaitReceiverStatusWhereVolume(0.4);
        assertEquals(0.4, status.volume.level, 0.001);

        client.setMuted(true);
        status = listener.awaitReceiverStatusWhereMuted(true);
        assertTrue(status.volume.muted);
        assertEquals(0.4, status.volume.level, 0.001);
    }

    @Test
    public void stopsApplicationAndClearsSession() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.launchApplication(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, false);
        awaitTransport();
        String sessionId = client.getSessionId();
        assertNotNull(sessionId);

        client.stopApplication(null);

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (client.getTransportId() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNull("Transport id was not cleared", client.getTransportId());
        assertNull(client.getSessionId());
        assertNull(listener.awaitNullMediaStatus());

        JSONObject stop = receiver.awaitPayload(CastProtocol.NAMESPACE_RECEIVER, "STOP", TIMEOUT_MS);
        assertNotNull(stop);
        assertEquals(sessionId, stop.getString("sessionId"));
    }

    @Test
    public void answersHeartbeats() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        listener.awaitReceiverStatus();

        receiver.clearReceivedMessages();
        receiver.pingSender();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        JSONObject pong = null;
        while (pong == null && System.currentTimeMillis() < deadline) {
            pong = receiver.findLastPayload(CastProtocol.NAMESPACE_HEARTBEAT, "PONG");
            if (pong == null) {
                Thread.sleep(10);
            }
        }
        assertNotNull("Client did not answer the heartbeat", pong);
    }

    @Test
    public void reportsRejectedRequests() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.launchApplication(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, false);
        awaitTransport();

        CastMediaInfo media = CastMediaInfo.builder("https://example.org/movie.mp4", "video/mp4").build();
        long loadRequestId = client.loadMedia(media, true, 0);
        assertNotNull(listener.awaitMediaStatusWhere(CastProtocol.PLAYER_STATE_PLAYING));

        receiver.rejectNextMediaCommand();
        long pauseRequestId = client.pause();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (listener.failures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("No failure was reported", !listener.failures.isEmpty());
        assertEquals("INVALID_PLAYER_STATE", listener.failures.get(0).reason);
        assertEquals(pauseRequestId, listener.failures.get(0).requestId);
        assertTrue(loadRequestId > 0);
        assertTrue(pauseRequestId > loadRequestId);
    }

    @Test
    public void reportsRejectedLaunch() throws Exception {
        receiver.setRejectLaunch(true);
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        long requestId = client.launchApplication("ABCDEFGH", false);

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (listener.failures.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!listener.failures.isEmpty());
        assertEquals(requestId, listener.failures.get(0).requestId);
        assertEquals("NOT_FOUND", listener.failures.get(0).reason);
    }

    @Test
    public void deliversCustomNamespaceMessages() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.launchApplication(FakeCastReceiver.DEFAULT_RECEIVER_APP_ID, false);
        awaitTransport();

        JSONObject payload = new JSONObject();
        payload.put("type", "HELLO");
        payload.put("value", 42);
        client.sendMessage("urn:x-cast:org.microg.test", payload);

        JSONObject received = receiver.awaitPayload("urn:x-cast:org.microg.test", "HELLO", TIMEOUT_MS);
        assertNotNull(received);
        assertEquals(42, received.getInt("value"));

        JSONObject answer = new JSONObject();
        answer.put("type", "WORLD");
        receiver.sendCustomMessage("urn:x-cast:org.microg.test", answer);

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (listener.messages.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, listener.messages.size());
        assertEquals("urn:x-cast:org.microg.test", listener.messages.get(0).namespace);
    }

    @Test
    public void reportsNetworkErrors() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        listener.awaitReceiverStatus();

        receiver.closeConnection();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (listener.disconnectReasons.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(!listener.disconnectReasons.isEmpty());
        assertEquals(CastClient.DISCONNECT_REASON_NETWORK_ERROR, (int) listener.disconnectReasons.get(0));
    }

    @Test
    public void disconnectNotifiesOnceWithUserReason() throws Exception {
        client.connect();
        receiver.awaitConnection(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        listener.awaitReceiverStatus();

        client.disconnect();
        client.disconnect();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (listener.disconnectReasons.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, listener.disconnectReasons.size());
        assertEquals(CastClient.DISCONNECT_REASON_USER_REQUEST, (int) listener.disconnectReasons.get(0));
    }

    private void awaitTransport() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (client.getTransportId() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(receiver.getTransportId(), client.getTransportId());
    }

    static class Failure {
        final long requestId;
        final String reason;

        Failure(long requestId, String reason) {
            this.requestId = requestId;
            this.reason = reason;
        }
    }

    static class Message {
        final String namespace;
        final String payload;

        Message(String namespace, String payload) {
            this.namespace = namespace;
            this.payload = payload;
        }
    }

    /**
     * Listener that records everything the client reports and allows the tests to wait for events.
     */
    static class RecordingListener implements CastClientListener {
        private final CountDownLatch connectedLatch = new CountDownLatch(1);
        final List<CastReceiverStatus> receiverStatuses = new CopyOnWriteArrayList<CastReceiverStatus>();
        final List<CastMediaStatus> mediaStatuses = new CopyOnWriteArrayList<CastMediaStatus>();
        final List<Failure> failures = new CopyOnWriteArrayList<Failure>();
        final List<Message> messages = new CopyOnWriteArrayList<Message>();
        final List<Integer> disconnectReasons = new CopyOnWriteArrayList<Integer>();
        final AtomicInteger nullMediaStatuses = new AtomicInteger();
        final AtomicReference<String> applicationStatus = new AtomicReference<String>();

        @Override
        public void onConnected() {
            connectedLatch.countDown();
        }

        @Override
        public void onDisconnected(int reason) {
            disconnectReasons.add(reason);
        }

        @Override
        public void onReceiverStatus(CastReceiverStatus status) {
            receiverStatuses.add(status);
        }

        @Override
        public void onMediaStatus(CastMediaStatus status) {
            if (status == null) {
                nullMediaStatuses.incrementAndGet();
                mediaStatuses.add(null);
            } else {
                mediaStatuses.add(status);
            }
        }

        @Override
        public void onApplicationStatus(String statusText) {
            applicationStatus.set(statusText);
        }

        @Override
        public void onRequestFailed(long requestId, String reason) {
            failures.add(new Failure(requestId, reason));
        }

        @Override
        public void onMessageReceived(String namespace, String payload) {
            messages.add(new Message(namespace, payload));
        }

        @Override
        public void onBinaryMessageReceived(String namespace, byte[] payload) {
        }

        CastReceiverStatus awaitReceiverStatus() throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (receiverStatuses.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            return receiverStatuses.isEmpty() ? null : receiverStatuses.get(receiverStatuses.size() - 1);
        }

        CastReceiverStatus awaitReceiverStatusWhereVolume(double level) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (CastReceiverStatus status : receiverStatuses) {
                    if (Math.abs(status.volume.level - level) < 0.001) {
                        return status;
                    }
                }
                Thread.sleep(10);
            }
            return null;
        }

        CastReceiverStatus awaitReceiverStatusWhereMuted(boolean muted) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (CastReceiverStatus status : receiverStatuses) {
                    if (status.volume.muted == muted) {
                        return status;
                    }
                }
                Thread.sleep(10);
            }
            return null;
        }

        CastMediaStatus awaitMediaStatusWhere(String playerState) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                for (int i = mediaStatuses.size() - 1; i >= 0; i--) {
                    CastMediaStatus status = mediaStatuses.get(i);
                    if (status != null && playerState.equals(status.playerState)) {
                        return status;
                    }
                }
                Thread.sleep(10);
            }
            return null;
        }

        CastMediaStatus awaitMediaStatus() throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (mediaStatuses.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue("No media status received", !mediaStatuses.isEmpty());
            return mediaStatuses.get(mediaStatuses.size() - 1);
        }

        Object awaitNullMediaStatus() throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (nullMediaStatuses.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            return nullMediaStatuses.get() > 0 ? null : "no null media status";
        }

        void clearMediaStatus() {
            mediaStatuses.clear();
        }
    }
}
