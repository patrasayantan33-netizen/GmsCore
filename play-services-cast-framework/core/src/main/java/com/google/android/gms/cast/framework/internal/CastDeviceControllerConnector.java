/*
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.android.gms.cast.framework.internal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.internal.ICastDeviceController;
import com.google.android.gms.cast.internal.ICastDeviceControllerListener;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.internal.BinderWrapper;
import com.google.android.gms.common.internal.GetServiceRequest;
import com.google.android.gms.common.internal.IGmsCallbacks;
import com.google.android.gms.common.internal.IGmsServiceBroker;
import com.google.android.gms.common.internal.ConnectionInfo;

import org.microg.gms.common.GmsService;

/**
 * Binds the cast framework to the cast device controller service.
 * <p>
 * Both, the cast framework and the cast device controller, live inside the Play Services package, so
 * the connection is established through the regular service broker of the package: the service is
 * bound directly, the broker is asked for {@link GmsService#CAST} and the
 * {@link ICastDeviceController} binder is delivered through {@link IGmsCallbacks}.
 */
public class CastDeviceControllerConnector {
    private static final String TAG = "GmsCastConnector";

    private static final String EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED =
            "com.google.android.gms.cast.EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED";
    private static final String EXTRA_CAST_FLAGS = "com.google.android.gms.cast.EXTRA_CAST_FLAGS";
    private static final String EXTRA_LISTENER = "listener";

    /**
     * Receives the result of {@link #connect(CastDevice, ICastDeviceControllerListener, Bundle, Callback)}.
     */
    public interface Callback {
        /**
         * The device controller is available.
         */
        void onConnected(ICastDeviceController controller);

        /**
         * The controller could not be created.
         *
         * @param statusCode a {@link CommonStatusCodes} error code.
         */
        void onConnectionFailed(int statusCode);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ServiceConnection serviceConnection;
    private Callback callback;
    private boolean bound;

    public CastDeviceControllerConnector(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Connects to the cast device controller service for the given device.
     *
     * @param castDevice       the device to control.
     * @param listener         listener that receives the state changes of the device.
     * @param additionalExtras additional extras handed to the controller, may be {@code null}.
     * @param callback         result callback, invoked on the main thread.
     */
    public void connect(final CastDevice castDevice, final ICastDeviceControllerListener listener,
                        final Bundle additionalExtras, Callback callback) {
        if (bound) {
            Log.d(TAG, "Already connected, ignoring connect request");
            return;
        }
        this.callback = callback;
        Intent intent = new Intent(GmsService.CAST.ACTION);
        intent.setPackage(context.getPackageName());
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                requestDeviceController(service, castDevice, listener, additionalExtras);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "Cast service disconnected");
                bound = false;
                notifyConnectionFailed(CommonStatusCodes.NETWORK_ERROR);
            }
        };
        try {
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not bind to the cast service: " + e.getMessage());
            bound = false;
        }
        if (!bound) {
            notifyConnectionFailed(CommonStatusCodes.API_NOT_CONNECTED);
        }
    }

    private void requestDeviceController(IBinder service, CastDevice castDevice,
                                        ICastDeviceControllerListener listener, Bundle additionalExtras) {
        IGmsServiceBroker broker = IGmsServiceBroker.Stub.asInterface(service);
        if (broker == null) {
            notifyConnectionFailed(CommonStatusCodes.INTERNAL_ERROR);
            return;
        }
        Bundle extras = additionalExtras == null ? new Bundle() : new Bundle(additionalExtras);
        extras.putBoolean(EXTRA_CAST_FRAMEWORK_NOTIFICATION_ENABLED, true);
        extras.putLong(EXTRA_CAST_FLAGS, 0L);
        if (castDevice != null) {
            castDevice.putInBundle(extras);
        }
        if (listener != null) {
            extras.putParcelable(EXTRA_LISTENER, new BinderWrapper(listener.asBinder()));
        }

        final GetServiceRequest request = new GetServiceRequest(GmsService.CAST.SERVICE_ID);
        request.packageName = context.getPackageName();
        request.extras = extras;
        try {
            broker.getService(new IGmsCallbacks.Stub() {
                @Override
                public void onPostInitComplete(int statusCode, IBinder binder, Bundle params) {
                    if (statusCode != CommonStatusCodes.SUCCESS || binder == null) {
                        Log.w(TAG, "Could not obtain cast device controller: " + statusCode);
                        notifyConnectionFailed(statusCode);
                        return;
                    }
                    final ICastDeviceController controller = ICastDeviceController.Stub.asInterface(binder);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onConnected(controller);
                            }
                        }
                    });
                }

                @Override
                public void onAccountValidationComplete(int statusCode, Bundle params) {
                    Log.d(TAG, "onAccountValidationComplete: " + statusCode);
                }

                @Override
                public void onPostInitCompleteWithConnectionInfo(int statusCode, IBinder binder,
                                                                 ConnectionInfo info) {
                    onPostInitComplete(statusCode, binder, info == null ? null : info.params);
                }
            }, request);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not request the cast service: " + e.getMessage());
            notifyConnectionFailed(CommonStatusCodes.INTERNAL_ERROR);
        }
    }

    /**
     * Releases the binding to the cast service.
     */
    public void disconnect() {
        callback = null;
        if (bound && serviceConnection != null) {
            try {
                context.unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Service was not bound: " + e.getMessage());
            }
        }
        bound = false;
        serviceConnection = null;
    }

    private void notifyConnectionFailed(final int statusCode) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    callback.onConnectionFailed(statusCode);
                }
            }
        });
    }
}
