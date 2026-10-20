/*
 * Copyright (C) 2013-2017 microG Project Team
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

package com.google.android.gms.cast.framework.internal;

import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;


import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.ISession;
import com.google.android.gms.cast.framework.ISessionProxy;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.dynamic.IObjectWrapper;
import com.google.android.gms.dynamic.ObjectWrapper;

public class SessionImpl extends ISession.Stub {
    private static final String TAG = SessionImpl.class.getSimpleName();

    private String category;
    private String sessionId;
    private ISessionProxy proxy;

    private CastSessionImpl castSession;

    private CastContextImpl castContext;
    private CastDevice castDevice;
    private Bundle routeInfoExtra;

    private boolean mIsConnecting = false;
    private boolean mIsConnected = false;
    private boolean mIsDisconnecting = false;
    private boolean mIsDisconnected = false;
    private String routeId = null;
    private CastConnectionControllerImpl connectionController;

    public SessionImpl(String category, String sessionId, ISessionProxy proxy) {
        this.category = category;
        this.sessionId = sessionId;
        this.proxy = proxy;
    }

    public void start(CastContextImpl castContext, CastDevice castDevice, String routeId, Bundle routeInfoExtra) throws RemoteException {
        this.castContext = castContext;
        this.castDevice = castDevice;
        this.routeInfoExtra = routeInfoExtra;
        this.routeId = routeId;

        this.mIsConnecting = true;
        this.mIsConnected = false;
        this.mIsDisconnecting = false;
        this.mIsDisconnected = false;
        this.castContext.getSessionManagerImpl().onSessionStarting(this);
        if (this.proxy != null) {
            this.proxy.start(routeInfoExtra);
        }

        CastOptions options = this.castContext.getOptions();
        this.connectionController = new CastConnectionControllerImpl(this.castContext, this, castDevice, routeInfoExtra);
        this.connectionController.connect();
        if (options != null && options.getReceiverApplicationId() != null) {
            this.connectionController.launchApplication(options.getReceiverApplicationId(), options.getLaunchOptions());
        } else {
            Log.w(TAG, "No receiver application id configured, not launching an application");
        }
    }

    public void onApplicationConnectionSuccess(ApplicationMetadata applicationMetadata, String applicationStatus, String sessionId, boolean wasLaunched) {
        this.mIsConnecting = false;
        this.mIsConnected = true;
        this.mIsDisconnected = false;
        this.sessionId = sessionId;
        this.castContext.getSessionManagerImpl().onSessionStarted(this, sessionId);
        try {
            this.castContext.getRouter().selectRouteById(this.getRouteId());
        } catch (RemoteException ex) {
            Log.e(TAG, "Error calling selectRouteById: " + ex.getMessage());
        }
    }

    public void onApplicationConnectionFailure(int statusCode) {
        this.mIsConnecting = false;
        this.mIsConnected = false;
        this.mIsDisconnected = true;
        if (this.connectionController != null) {
            this.connectionController.disconnect();
            this.connectionController = null;
        }
        if (this.castContext != null) {
            this.castContext.getSessionManagerImpl().onSessionStartFailed(this, statusCode);
            try {
                this.castContext.getRouter().selectDefaultRoute();
            } catch (RemoteException ex) {
                Log.e(TAG, "Error calling selectDefaultRoute: " + ex.getMessage());
            }
        }
        this.routeId = null;
        this.castDevice = null;
        this.routeInfoExtra = null;
    }

    /**
     * Called when the device closed the connection or the receiver application stopped.
     */
    public void onDeviceDisconnected() {
        Log.d(TAG, "Device disconnected, ending session");
        end(false);
    }

    /**
     * Ends this session.
     *
     * @param stopCasting whether the receiver application should be stopped.
     */
    public void end(boolean stopCasting) {
        if (this.mIsDisconnecting || this.mIsDisconnected) {
            return;
        }
        this.mIsDisconnecting = true;
        if (this.castContext != null) {
            this.castContext.getSessionManagerImpl().onSessionEnding(this);
        }
        CastConnectionControllerImpl controller = this.connectionController;
        this.connectionController = null;
        if (controller != null) {
            if (stopCasting && this.sessionId != null) {
                controller.stopApplication(this.sessionId);
            }
            controller.disconnect();
        }
        if (this.proxy != null) {
            try {
                this.proxy.end(stopCasting);
            } catch (RemoteException ex) {
                Log.w(TAG, "Error ending session proxy: " + ex.getMessage());
            }
        }
        this.mIsConnecting = false;
        this.mIsConnected = false;
        this.mIsDisconnecting = false;
        this.mIsDisconnected = true;
        if (this.castContext != null) {
            this.castContext.getSessionManagerImpl().onSessionEnded(this, CommonStatusCodes.SUCCESS);
        }
    }

    public void onRouteSelected(Bundle extras) {
    }

    public CastSessionImpl getCastSession() {
        return this.castSession;
    }

    public void setCastSession(CastSessionImpl castSession) {
        this.castSession = castSession;
    }

    public ISessionProxy getSessionProxy() {
        return this.proxy;
    }

    public IObjectWrapper getWrappedSession() throws RemoteException {
        if (this.proxy == null) {
            return ObjectWrapper.wrap(null);
        }
        return this.proxy.getWrappedSession();
    }

    @Override
    public String getCategory() {
        return this.category;
    }

    @Override
    public String getSessionId() {
        return this.sessionId;
    }

    @Override
    public String getRouteId() {
        return this.routeId;
    }

    @Override
    public boolean isConnected() {
        return this.mIsConnected;
    }

    @Override
    public boolean isConnecting() {
        return this.mIsConnecting;
    }

    @Override
    public boolean isDisconnecting() {
        return this.mIsDisconnecting;
    }

    @Override
    public boolean isDisconnected() {
        return this.mIsDisconnected;
    }

    @Override
    public boolean isResuming() {
        Log.d(TAG, "unimplemented Method: isResuming");
        return false;
    }

    @Override
    public boolean isSuspended() {
        Log.d(TAG, "unimplemented Method: isSuspended");
        return false;
    }

    @Override
    public void notifySessionStarted(String sessionId) {
        Log.d(TAG, "unimplemented Method: notifySessionStarted");
    }

    @Override
    public void notifyFailedToStartSession(int error) {
        Log.d(TAG, "unimplemented Method: notifyFailedToStartSession");
    }

    @Override
    public void notifySessionEnded(int error) {
        Log.d(TAG, "unimplemented Method: notifySessionEnded");
    }

    @Override
    public void notifySessionResumed(boolean wasSuspended) {
        Log.d(TAG, "unimplemented Method: notifySessionResumed");
    }

    @Override
    public void notifyFailedToResumeSession(int error) {
        Log.d(TAG, "unimplemented Method: notifyFailedToResumeSession");
    }

    @Override
    public void notifySessionSuspended(int reason) {
        Log.d(TAG, "unimplemented Method: notifySessionSuspended");
    }

    @Override
    public IObjectWrapper getWrappedObject() {
        return ObjectWrapper.wrap(this);
    }
}
