/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.ApplicationStatus;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastDeviceStatus;
import com.google.android.gms.cast.JoinOptions;
import com.google.android.gms.cast.LaunchOptions;
import com.google.android.gms.cast.framework.ICastConnectionController;
import com.google.android.gms.cast.internal.ICastDeviceController;
import com.google.android.gms.cast.internal.ICastDeviceControllerListener;
import com.google.android.gms.common.api.CommonStatusCodes;

/**
 * Connects a cast session to a device.
 * <p>
 * The controller implements the {@link ICastConnectionController} used by the cast session and
 * speaks to the cast device controller service through {@link CastDeviceControllerConnector}. All
 * state changes reported by the device controller are forwarded to the {@link SessionImpl} that
 * owns this controller.
 */
public class CastConnectionControllerImpl extends ICastConnectionController.Stub
        implements ICastDeviceControllerListener {
    private static final String TAG = "GmsCastConnectionController";

    /**
     * Version of the cast connection controller protocol implemented by this class. Applications
     * check this value to decide whether they can use newer session features.
     */
    private static final int SUPPORTED_VERSION = 3;

    private final SessionImpl session;
    private final CastDevice castDevice;
    private final Bundle routeInfoExtra;
    private final CastDeviceControllerConnector connector;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ICastDeviceController controller;
    private boolean connecting;
    private boolean disconnected;

    private String pendingApplicationId;
    private LaunchOptions pendingLaunchOptions;
    private String pendingJoinSessionId;
    private boolean pendingStop;

    public CastConnectionControllerImpl(CastContextImpl castContext, SessionImpl session,
                                        CastDevice castDevice, Bundle routeInfoExtra) {
        this.session = session;
        this.castDevice = castDevice;
        this.routeInfoExtra = routeInfoExtra;
        this.connector = new CastDeviceControllerConnector(castContext.getContext());
    }

    /**
     * Starts connecting to the device. The result is reported through
     * {@link SessionImpl#onApplicationConnectionSuccess} or
     * {@link SessionImpl#onApplicationConnectionFailure}.
     */
    public void connect() {
        if (connecting || controller != null || disconnected) {
            return;
        }
        if (castDevice == null) {
            Log.w(TAG, "No cast device for session");
            notifyConnectionFailed(CommonStatusCodes.INTERNAL_ERROR);
            return;
        }
        connecting = true;
        connector.connect(castDevice, this, routeInfoExtra, new CastDeviceControllerConnector.Callback() {
            @Override
            public void onConnected(ICastDeviceController deviceController) {
                controller = deviceController;
                connecting = false;
                flushPendingRequests();
            }

            @Override
            public void onConnectionFailed(int statusCode) {
                connecting = false;
                Log.w(TAG, "Connecting to " + castDevice.getFriendlyName() + " failed: " + statusCode);
                notifyConnectionFailed(statusCode);
            }
        });
    }

    private void flushPendingRequests() {
        ICastDeviceController current = controller;
        if (current == null) {
            return;
        }
        try {
            if (pendingStop) {
                pendingStop = false;
                current.disconnect();
                return;
            }
            if (pendingJoinSessionId != null && pendingApplicationId != null) {
                String applicationId = pendingApplicationId;
                String sessionId = pendingJoinSessionId;
                pendingApplicationId = null;
                pendingJoinSessionId = null;
                current.joinApplication(applicationId, sessionId, new JoinOptions());
                return;
            }
            if (pendingApplicationId != null) {
                String applicationId = pendingApplicationId;
                LaunchOptions launchOptions = pendingLaunchOptions;
                pendingApplicationId = null;
                pendingLaunchOptions = null;
                current.launchApplication(applicationId, launchOptions == null ? new LaunchOptions() : launchOptions);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Could not send pending cast request: " + e.getMessage());
            notifyConnectionFailed(CommonStatusCodes.INTERNAL_ERROR);
        }
    }

    // region ICastConnectionController

    @Override
    public void joinApplication(String applicationId, String sessionId) {
        Log.d(TAG, "joinApplication(" + applicationId + ", " + sessionId + ")");
        ICastDeviceController current = controller;
        if (current == null) {
            pendingApplicationId = applicationId;
            pendingJoinSessionId = sessionId;
            connect();
            return;
        }
        try {
            current.joinApplication(applicationId, sessionId, new JoinOptions());
        } catch (RemoteException e) {
            Log.w(TAG, "Could not join application: " + e.getMessage());
        }
    }

    @Override
    public void launchApplication(String applicationId, LaunchOptions launchOptions) {
        Log.d(TAG, "launchApplication(" + applicationId + ")");
        ICastDeviceController current = controller;
        if (current == null) {
            pendingApplicationId = applicationId;
            pendingLaunchOptions = launchOptions;
            connect();
            return;
        }
        try {
            current.launchApplication(applicationId, launchOptions);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not launch application: " + e.getMessage());
            notifyConnectionFailed(CommonStatusCodes.INTERNAL_ERROR);
        }
    }

    @Override
    public void stopApplication(String sessionId) {
        Log.d(TAG, "stopApplication(" + sessionId + ")");
        ICastDeviceController current = controller;
        if (current == null) {
            pendingStop = true;
            return;
        }
        try {
            current.stopApplication(sessionId);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not stop application: " + e.getMessage());
        }
    }

    @Override
    public void closeConnection(int reason) {
        Log.d(TAG, "closeConnection(" + reason + ")");
        disconnect();
    }

    @Override
    public int getSupportedVersion() {
        return SUPPORTED_VERSION;
    }

    // endregion

    /**
     * Disconnects from the device and releases the service binding.
     */
    public void disconnect() {
        if (disconnected) {
            return;
        }
        disconnected = true;
        ICastDeviceController current = controller;
        controller = null;
        if (current != null) {
            try {
                current.disconnect();
            } catch (RemoteException e) {
                Log.w(TAG, "Could not disconnect from device: " + e.getMessage());
            }
        }
        connector.disconnect();
    }

    // region ICastDeviceControllerListener

    @Override
    public void onDisconnected(int reason) {
        Log.d(TAG, "Device disconnected: " + reason);
        if (disconnected) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                session.onDeviceDisconnected();
            }
        });
    }

    @Override
    public void onApplicationConnectionSuccess(final ApplicationMetadata applicationMetadata,
                                               final String applicationStatus, final String sessionId,
                                               final boolean wasLaunched) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                session.onApplicationConnectionSuccess(applicationMetadata, applicationStatus, sessionId, wasLaunched);
            }
        });
    }

    @Override
    public void onApplicationConnectionFailure(final int statusCode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                session.onApplicationConnectionFailure(statusCode);
            }
        });
    }

    @Override
    public void onTextMessageReceived(String namespace, String message) {
        Log.d(TAG, "Message in " + namespace + ": " + message);
    }

    @Override
    public void onBinaryMessageReceived(String namespace, byte[] data) {
        Log.d(TAG, "Binary message in " + namespace + " (" + (data == null ? 0 : data.length) + " bytes)");
    }

    @Override
    public void onApplicationDisconnected(final int statusCode) {
        Log.d(TAG, "Application disconnected: " + statusCode);
        handler.post(new Runnable() {
            @Override
            public void run() {
                session.onDeviceDisconnected();
            }
        });
    }

    @Override
    public void onSendMessageFailure(String response, long requestId, int statusCode) {
        Log.w(TAG, "Sending message " + requestId + " failed: " + response);
    }

    @Override
    public void onSendMessageSuccess(String response, long requestId) {
        Log.d(TAG, "Message " + requestId + " sent");
    }

    @Override
    public void onApplicationStatusChanged(ApplicationStatus applicationStatus) {
        Log.d(TAG, "Application status changed");
    }

    @Override
    public void onDeviceStatusChanged(CastDeviceStatus deviceStatus) {
        Log.d(TAG, "Device status changed");
    }

    // endregion

    private void notifyConnectionFailed(final int statusCode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                session.onApplicationConnectionFailure(statusCode);
            }
        });
    }
}
