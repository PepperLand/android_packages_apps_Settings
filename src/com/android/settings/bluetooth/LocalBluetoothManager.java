/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.bluetooth;

import com.android.settings.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Config;
import android.util.Log;
import android.widget.Toast;

// TODO: have some notion of shutting down.  Maybe a minute after they leave BT settings?
/**
 * LocalBluetoothManager provides a simplified interface on top of a subset of
 * the Bluetooth API.
 */
public class LocalBluetoothManager {
    private static final String TAG = "LocalBluetoothManager";
    static final boolean V = Config.LOGV;
    static final boolean D = Config.LOGD;

    private static final String SHARED_PREFERENCES_NAME = "bluetooth_settings";

    private static LocalBluetoothManager INSTANCE;
    /** Used when obtaining a reference to the singleton instance. */
    private static Object INSTANCE_LOCK = new Object();
    private boolean mInitialized;

    private Context mContext;
    /** If a BT-related activity is in the foreground, this will be it. */
    private Activity mForegroundActivity;
    private AlertDialog mErrorDialog = null;

    private BluetoothAdapter mAdapter;

    private CachedBluetoothDeviceManager mCachedDeviceManager;
    private BluetoothEventRedirector mEventRedirector;
    private BluetoothA2dp mBluetoothA2dp;

    private int mState = BluetoothError.ERROR;

    private List<Callback> mCallbacks = new ArrayList<Callback>();

    private static final int SCAN_EXPIRATION_MS = 5 * 60 * 1000; // 5 mins
    private long mLastScan;

    public static LocalBluetoothManager getInstance(Context context) {
        synchronized (INSTANCE_LOCK) {
            if (INSTANCE == null) {
                INSTANCE = new LocalBluetoothManager();
            }

            if (!INSTANCE.init(context)) {
                return null;
            }

            return INSTANCE;
        }
    }

    private boolean init(Context context) {
        if (mInitialized) return true;
        mInitialized = true;

        // This will be around as long as this process is
        mContext = context.getApplicationContext();

        mAdapter = (BluetoothAdapter) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (mAdapter == null) {
            return false;
        }

        mCachedDeviceManager = new CachedBluetoothDeviceManager(this);

        mEventRedirector = new BluetoothEventRedirector(this);
        mEventRedirector.start();

        mBluetoothA2dp = new BluetoothA2dp(context);

        return true;
    }

    public BluetoothAdapter getBluetoothAdapter() {
        return mAdapter;
    }

    public Context getContext() {
        return mContext;
    }

    public Activity getForegroundActivity() {
        return mForegroundActivity;
    }

    public void setForegroundActivity(Activity activity) {
        if (mErrorDialog != null) {
            mErrorDialog.dismiss();
            mErrorDialog = null;
        }
        mForegroundActivity = activity;
    }

    public SharedPreferences getSharedPreferences() {
        return mContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public CachedBluetoothDeviceManager getCachedDeviceManager() {
        return mCachedDeviceManager;
    }

    List<Callback> getCallbacks() {
        return mCallbacks;
    }

    public void registerCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    public void unregisterCallback(Callback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    public void startScanning(boolean force) {
        if (mAdapter.isDiscovering()) {
            /*
             * Already discovering, but give the callback that information.
             * Note: we only call the callbacks, not the same path as if the
             * scanning state had really changed (in that case the device
             * manager would clear its list of unpaired scanned devices).
             */
            dispatchScanningStateChanged(true);
        } else {
            if (!force) {
                // Don't scan more than frequently than SCAN_EXPIRATION_MS,
                // unless forced
                if (mLastScan + SCAN_EXPIRATION_MS > System.currentTimeMillis()) {
                    return;
                }

                // If we are playing music, don't scan unless forced.
                Set<BluetoothDevice> sinks = mBluetoothA2dp.getConnectedSinks();
                if (sinks != null) {
                    for (BluetoothDevice sink : sinks) {
                        if (mBluetoothA2dp.getSinkState(sink) == BluetoothA2dp.STATE_PLAYING) {
                            return;
                        }
                    }
                }
            }

            if (mAdapter.startDiscovery()) {
                mLastScan = System.currentTimeMillis();
            }
        }
    }

    public int getBluetoothState() {

        if (mState == BluetoothError.ERROR) {
            syncBluetoothState();
        }

        return mState;
    }

    void setBluetoothStateInt(int state) {
        mState = state;
        if (state == BluetoothAdapter.BLUETOOTH_STATE_ON ||
            state == BluetoothAdapter.BLUETOOTH_STATE_OFF) {
            mCachedDeviceManager.onBluetoothStateChanged(state ==
                    BluetoothAdapter.BLUETOOTH_STATE_ON);
        }
    }

    private void syncBluetoothState() {
        int bluetoothState;

        if (mAdapter != null) {
            bluetoothState = mAdapter.isEnabled()
                    ? BluetoothAdapter.BLUETOOTH_STATE_ON
                    : BluetoothAdapter.BLUETOOTH_STATE_OFF;
        } else {
            bluetoothState = BluetoothError.ERROR;
        }

        setBluetoothStateInt(bluetoothState);
    }

    public void setBluetoothEnabled(boolean enabled) {
        boolean wasSetStateSuccessful = enabled
                ? mAdapter.enable()
                : mAdapter.disable();

        if (wasSetStateSuccessful) {
            setBluetoothStateInt(enabled
                ? BluetoothAdapter.BLUETOOTH_STATE_TURNING_ON
                : BluetoothAdapter.BLUETOOTH_STATE_TURNING_OFF);
        } else {
            if (V) {
                Log.v(TAG,
                        "setBluetoothEnabled call, manager didn't return success for enabled: "
                                + enabled);
            }

            syncBluetoothState();
        }
    }

    /**
     * @param started True if scanning started, false if scanning finished.
     */
    void onScanningStateChanged(boolean started) {
        // TODO: have it be a callback (once we switch bluetooth state changed to callback)
        mCachedDeviceManager.onScanningStateChanged(started);
        dispatchScanningStateChanged(started);
    }

    private void dispatchScanningStateChanged(boolean started) {
        synchronized (mCallbacks) {
            for (Callback callback : mCallbacks) {
                callback.onScanningStateChanged(started);
            }
        }
    }

    public void showError(BluetoothDevice device, int titleResId, int messageResId) {
        CachedBluetoothDevice cachedDevice = mCachedDeviceManager.findDevice(device);
        if (cachedDevice == null) return;

        String name = cachedDevice.getName();
        String message = mContext.getString(messageResId, name);

        if (mForegroundActivity != null) {
            // Need an activity context to show a dialog
            mErrorDialog = new AlertDialog.Builder(mForegroundActivity)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(titleResId)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        } else {
            // Fallback on a toast
            Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
        }
    }

    public interface Callback {
        void onScanningStateChanged(boolean started);
        void onDeviceAdded(CachedBluetoothDevice cachedDevice);
        void onDeviceDeleted(CachedBluetoothDevice cachedDevice);
    }

}
