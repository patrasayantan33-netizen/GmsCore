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
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.ApplicationStatus;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastDeviceStatus;
import com.google.android.gms.cast.JoinOptions;
import com.google.android.gms.cast.LaunchOptions;
import com.google.android.gms.cast.internal.ICastDeviceController;
import com.google.android.gms.cast.internal.ICastDeviceControllerListener;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.images.WebImage;
import com.google.android.gms.common.internal.BinderWrapper;

import org.microg.gms.cast.protocol.CastClient;
import org.microg.gms.cast.protocol.CastClientListener;
import org.microg.gms.cast.protocol.CastMediaStatus;
import org.microg.gms.cast.protocol.CastReceiverStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Implementation of the Cast device controller service, i.e. the binder interface used by the Cast
 * framework and by applications using the low level {@code Cast.CastApi}.
 * <p>
 * The controller owns a {@link CastClient} for the device identified by the session request and
 * forwards the state changes of the device to the {@link ICastDeviceControllerListener} of the
 * calling application. All binder methods are oneway, so the work is serialized on a private
 * worker thread.
 */
public class CastDeviceControllerImpl extends ICastDeviceController.Stub implements CastClientListener {
    private static final String TAG = "GmsCastDeviceController";

    private static final String EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED =
            "com.google.android.gms.cast.EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED";
    private static final String EXTRA_CAST_FLAGS = "com.google.android.gms.cast.EXTRA_CAST_FLAGS";
    private static final String EXTRA_LISTENER = "listener";

    private final Context context;
    private final String packageName;
    private final CastDevice castDevice;
    private final boolean notificationEnabled;
    private final long castFlags;
    private final ICastDeviceControllerListener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final List<Runnable> queuedWhileConnecting = new ArrayList<Runnable>();

    private CastClient client;
    private CastReceiverStatus.Application lastApplication;
    private String lastApplicationStatusText;
    private String pendingLaunchApplicationId;
    private boolean pendingLaunchJoinedRunningApplication;

    public CastDeviceControllerImpl(Context context, String packageName, Bundle extras) {
        this.context = context;
        this.packageName = packageName;

        extras.setClassLoader(BinderWrapper.class.getClassLoader());
        this.castDevice = CastDevice.getFromBundle(extras);
        this.notificationEnabled = extras.getBoolean(EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED, false);
        this.castFlags = extras.getLong(EXTRA_CAST_FLAGS, 0L);
        Log.d(TAG, "Controller for " + castDevice + " (notifications: " + notificationEnabled
                + ", flags: " + castFlags + ")");
        BinderWrapper listenerWrapper = (BinderWrapper) extras.get(EXTRA_LISTENER);
        this.listener = listenerWrapper == null ? null
                : ICastDeviceControllerListener.Stub.asInterface(listenerWrapper.binder);
    }

    // region ICastDeviceController

    @Override
    public void disconnect() {
        Log.d(TAG, "disconnect()");
        execute(new Runnable() {
            @Override
            public void run() {
                CastClient current = client;
                client = null;
                if (current != null) {
                    current.disconnect();
                }
                executor.shutdown();
            }
        });
    }

    @Override
    public void stopApplication(final String sessionId) {
        Log.d(TAG, "stopApplication(" + sessionId + ")");
        withClient(new ClientAction() {
            @Override
            public void run(CastClient client) throws IOException {
                client.stopApplication(sessionId);
            }
        });
    }

    @Override
    public void sendMessage(final String namespace, final String message, final long requestId) {
        Log.d(TAG, "sendMessage(" + namespace + ", " + requestId + ")");
        withClient(new ClientAction() {
            @Override
            public void run(CastClient client) throws IOException {
                client.sendMessage(namespace, message, requestId);
                callOnSendMessageSuccess(message, requestId);
            }
        });
    }

    @Override
    public void registerNamespace(final String namespace) {
        Log.d(TAG, "registerNamespace(" + namespace + ")");
        withClient(new ClientAction() {
            @Override
            public void run(CastClient client) {
                client.registerNamespace(namespace);
            }
        });
    }

    @Override
    public void unregisterNamespace(final String namespace) {
        Log.d(TAG, "unregisterNamespace(" + namespace + ")");
        withClient(new ClientAction() {
            @Override
            public void run(CastClient client) {
                client.unregisterNamespace(namespace);
            }
        });
    }

    @Override
    public void launchApplication(final String applicationId, final LaunchOptions launchOptions) {
        Log.d(TAG, "launchApplication(" + applicationId + ")");
        final boolean relaunch = launchOptions != null && launchOptions.getRelaunchIfRunning();
        withClient(new ClientAction() {
            @Override
            public void run(CastClient client) throws IOException {
                CastReceiverStatus status = client.getReceiverStatus();
                boolean alreadyRunning = status != null && status.isApplicationRunning(applicationId);
                pendingLaunchApplicationId = applicationId;
                pendingLaunchJoinedRunningApplication = alreadyRunning && !relaunch;
                client.launchApplication(applicationId, relaunch);
            }
        });
    }

    @Override
    public void joinApplication(final String applicationId, final String sessionId, JoinOptions joinOptions) {
        Log.d(TAG, "joinApplication(" + applicationId + ", " + sessionId + ")");
        withClient(new ClientAction() {
            @Override
            public void run(CastClient client) throws IOException {
                pendingLaunchApplicationId = applicationId;
                pendingLaunchJoinedRunningApplication = true;
                client.joinApplication(applicationId, sessionId);
            }
        });
    }

    // endregion

    // region Connection handling

    private interface ClientAction {
        void run(CastClient client) throws IOException;
    }

    private void withClient(final ClientAction action) {
        execute(new Runnable() {
            @Override
            public void run() {
                CastClient current = client;
                if (current == null || !current.isConnected()) {
                    current = connect();
                }
                if (current == null || !current.isConnected()) {
                    synchronized (queuedWhileConnecting) {
                        if (queuedWhileConnecting.size() < 32) {
                            queuedWhileConnecting.add(new Runnable() {
                                @Override
                                public void run() {
                                    runAction(action);
                                }
                            });
                            return;
                        }
                    }
                    Log.w(TAG, "Not connected to cast device, dropping command");
                    callOnDisconnected(CommonStatusCodes.NETWORK_ERROR);
                    return;
                }
                runAction(action);
            }
        });
    }

    private void runAction(ClientAction action) {
        CastClient current = client;
        if (current == null) {
            return;
        }
        try {
            action.run(current);
        } catch (IOException e) {
            Log.w(TAG, "Cast command failed: " + e.getMessage());
        }
    }

    private CastClient connect() {
        if (castDevice == null) {
            Log.e(TAG, "No cast device given, cannot connect");
            return null;
        }
        if (client != null) {
            client.disconnect();
        }
        CastClient newClient = new CastClient(castDevice.getAddress(), castDevice.getServicePort(), this);
        client = newClient;
        try {
            newClient.connect();
            Log.d(TAG, "Connected to " + castDevice.getFriendlyName());
            return newClient;
        } catch (IOException e) {
            Log.w(TAG, "Could not connect to " + castDevice.getFriendlyName() + ": " + e.getMessage());
            return null;
        }
    }

    private void execute(Runnable runnable) {
        try {
            executor.execute(runnable);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Controller is closed, dropping command");
        }
    }

    // endregion

    // region CastClientListener

    @Override
    public void onConnected() {
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
    public void onDisconnected(final int reason) {
        int statusCode = reason == CastClient.DISCONNECT_REASON_USER_REQUEST
                ? CommonStatusCodes.SUCCESS : CommonStatusCodes.NETWORK_ERROR;
        lastApplication = null;
        lastApplicationStatusText = null;
        callOnDisconnected(statusCode);
    }

    @Override
    public void onReceiverStatus(CastReceiverStatus status) {
        if (status == null) {
            return;
        }
        CastReceiverStatus.Application application = status.getRunningApplication();
        ApplicationMetadata metadata = createMetadataFromApplication(application);

        callOnDeviceStatusChanged(new CastDeviceStatus(status.volume.level, status.volume.muted,
                status.activeInput ? 1 : 0, metadata, status.standBy ? 1 : 0));

        if (application == null) {
            if (lastApplication != null) {
                lastApplication = null;
                lastApplicationStatusText = null;
                callOnApplicationDisconnected(CommonStatusCodes.SUCCESS);
            }
            return;
        }

        if (application.statusText != null && !application.statusText.equals(lastApplicationStatusText)) {
            lastApplicationStatusText = application.statusText;
            callOnApplicationStatusChanged(new ApplicationStatus(application.statusText));
        }

        boolean applicationChanged = lastApplication == null
                || !equal(lastApplication.sessionId, application.sessionId);
        lastApplication = application;
        if (pendingLaunchApplicationId != null && pendingLaunchApplicationId.equals(application.appId)
                && applicationChanged) {
            boolean wasLaunched = !pendingLaunchJoinedRunningApplication;
            pendingLaunchApplicationId = null;
            callOnApplicationConnectionSuccess(metadata,
                    application.statusText != null ? application.statusText : "",
                    application.sessionId, wasLaunched);
        }
    }

    @Override
    public void onMediaStatus(CastMediaStatus status) {
        // The media status is delivered to the application through the media namespace messages,
        // applications using the framework get it via the media router.
    }

    @Override
    public void onApplicationStatus(String statusText) {
        if (statusText == null || statusText.equals(lastApplicationStatusText)) {
            return;
        }
        lastApplicationStatusText = statusText;
        callOnApplicationStatusChanged(new ApplicationStatus(statusText));
    }

    @Override
    public void onRequestFailed(long requestId, String reason) {
        if (pendingLaunchApplicationId != null) {
            pendingLaunchApplicationId = null;
            Log.w(TAG, "Launching the receiver application failed: " + reason);
            callOnApplicationConnectionFailure(CommonStatusCodes.INTERNAL_ERROR);
            return;
        }
        callOnSendMessageFailure(reason, requestId, CommonStatusCodes.INTERNAL_ERROR);
    }

    @Override
    public void onMessageReceived(String namespace, String payload) {
        callOnTextMessageReceived(namespace, payload);
    }

    @Override
    public void onBinaryMessageReceived(String namespace, byte[] payload) {
        callOnBinaryMessageReceived(namespace, payload);
    }

    // endregion

    // region Listener callbacks

    private ApplicationMetadata createMetadataFromApplication(CastReceiverStatus.Application application) {
        if (application == null) {
            return null;
        }
        ApplicationMetadata metadata = new ApplicationMetadata();
        metadata.applicationId = application.appId;
        metadata.name = application.displayName;
        metadata.images = new ArrayList<WebImage>();
        metadata.namespaces = new ArrayList<String>(application.namespaces);
        metadata.senderAppIdentifier = packageName;
        return metadata;
    }

    private void callOnDisconnected(int statusCode) {
        if (listener == null) {
            return;
        }
        try {
            listener.onDisconnected(statusCode);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onDisconnected: " + e.getMessage());
        }
    }

    private void callOnApplicationConnectionSuccess(ApplicationMetadata metadata, String status,
                                                    String sessionId, boolean wasLaunched) {
        if (listener == null) {
            return;
        }
        try {
            listener.onApplicationConnectionSuccess(metadata, status, sessionId, wasLaunched);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onApplicationConnectionSuccess: " + e.getMessage());
        }
    }

    private void callOnApplicationConnectionFailure(int statusCode) {
        if (listener == null) {
            return;
        }
        try {
            listener.onApplicationConnectionFailure(statusCode);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onApplicationConnectionFailure: " + e.getMessage());
        }
    }

    private void callOnApplicationDisconnected(int statusCode) {
        if (listener == null) {
            return;
        }
        try {
            listener.onApplicationDisconnected(statusCode);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onApplicationDisconnected: " + e.getMessage());
        }
    }

    private void callOnApplicationStatusChanged(ApplicationStatus status) {
        if (listener == null) {
            return;
        }
        try {
            listener.onApplicationStatusChanged(status);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onApplicationStatusChanged: " + e.getMessage());
        }
    }

    private void callOnDeviceStatusChanged(CastDeviceStatus status) {
        if (listener == null) {
            return;
        }
        try {
            listener.onDeviceStatusChanged(status);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onDeviceStatusChanged: " + e.getMessage());
        }
    }

    private void callOnTextMessageReceived(String namespace, String message) {
        if (listener == null) {
            return;
        }
        try {
            listener.onTextMessageReceived(namespace, message);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onTextMessageReceived: " + e.getMessage());
        }
    }

    private void callOnBinaryMessageReceived(String namespace, byte[] data) {
        if (listener == null) {
            return;
        }
        try {
            listener.onBinaryMessageReceived(namespace, data);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onBinaryMessageReceived: " + e.getMessage());
        }
    }

    private void callOnSendMessageSuccess(String response, long requestId) {
        if (listener == null) {
            return;
        }
        try {
            listener.onSendMessageSuccess(response, requestId);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onSendMessageSuccess: " + e.getMessage());
        }
    }

    private void callOnSendMessageFailure(String response, long requestId, int statusCode) {
        if (listener == null) {
            return;
        }
        try {
            listener.onSendMessageFailure(response, requestId, statusCode);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling onSendMessageFailure: " + e.getMessage());
        }
    }

    private static boolean equal(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    // endregion
}
