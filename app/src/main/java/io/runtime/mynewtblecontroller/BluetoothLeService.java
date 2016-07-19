package io.runtime.mynewtblecontroller;

/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private int prevInstr;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String ACTION_READ_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_READ_DATA_AVAILABLE";
    public final static String ACTION_NOTIFICATION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_NOTIFICATION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";
    public final static String EXTRA_INSTR =
            "com.example.bluetooth.le.EXTRA_INSTR";
    public final static String EXTRA_NOTIFICATION =
            "com.example.bluetooth.le.EXTRA_NOTIFICATION";

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, String.format("Characteristic Read Success: 0x%x", characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)));
                broadcastUpdate(ACTION_READ_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, String.format("Characteristic Write Success: 0x%x", characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)));
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "Characteristic changed");
            broadcastUpdate(ACTION_NOTIFICATION_DATA_AVAILABLE, characteristic);
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    /**
     *  Called from onCharacteristicWrite and onCharacteristicRead. Sends Intent to
     *  activity based on supplied action:
     *    - ACTION_DATA_AVAILABLE: A characteristic write has completed.
     *    - ACTION_READ_DATA_AVAILABLE: A characteristic read has completed.
     * @param action The action: read/write
     * @param characteristic The characteristic which was read/written
     */
    private void broadcastUpdate(final String action,
                                 final BluetoothGattCharacteristic characteristic) {
        // Create intent with action
        final Intent intent = new Intent(action);

        // Write has completed successfully
        if (action.equals(ACTION_DATA_AVAILABLE)) {

            // Get instruction from characteristic
            final int instr = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0);

            // Create GpioPin from instruction
            GpioPin currentPin = new GpioPin(instr);
            if (currentPin.isOuput) {
                // If current pin is output, send instruction to DeviceControlActivity
                Log.d(TAG, "Write instrction found, putting instr...");
                intent.putExtra(EXTRA_INSTR, instr);
            } else {
                // Set prevInstr field to be used if pin is input and we must read from
                prevInstr = instr;
                Log.d(TAG, "Read instrction found, reading...");
                // If pin is input, read from pin and return before sending intent
                readCharacteristic(characteristic);
                return;
            }
        } else if (action.equals(ACTION_READ_DATA_AVAILABLE)) {
            Log.d(TAG, "Read data available, putting data...");
            // Read has completed successfully, get data from characteristic
            final int data = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0);
            // Put instruction AND Data into intent
            intent.putExtra(EXTRA_DATA, data);
            intent.putExtra(EXTRA_INSTR, prevInstr);
            intent.setAction(ACTION_DATA_AVAILABLE);
        } else if (action.equals(ACTION_NOTIFICATION_DATA_AVAILABLE)) {
            Log.d(TAG, "Notification data available, putting data...");
            final int notif = characteristic.getIntValue(
                    BluetoothGattCharacteristic.FORMAT_UINT16, 0);
            Log.d(TAG, "Notification data available: " + notif);
            intent.putExtra(EXTRA_NOTIFICATION, notif);
        }
        sendBroadcast(intent);
    }



    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated fully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        refreshDeviceCache(mBluetoothGatt); // Remove call for better performance
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * This method is used to refresh the android's Cached GATT values. If the user
     * adds/removes/changes GATT Characteristics or services on the device side, Android
     * would continue to use old UUIDs and connection handles. This method refreshes the device
     * cache every time we connect to a device. If you don't plan on changing your GATT
     * characteristics or services you may remove this method for better performance.
     *
     * @param gatt The Bluetooth GATT Server
     * @return True if success
     */
    private boolean refreshDeviceCache(BluetoothGatt gatt){
        try {
            BluetoothGatt localBluetoothGatt = gatt;
            Method localMethod = localBluetoothGatt.getClass().getMethod("refresh", new Class[0]);
            if (localMethod != null) {
                boolean bool = ((Boolean) localMethod.invoke(localBluetoothGatt, new Object[0])).booleanValue();
                return bool;
            }
        }
        catch (Exception localException) {
            Log.e(TAG, "An exception occured while refreshing device");
        }
        return false;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Request a write to a given {@code BluetoothGattCharacteristic}.
     *
     * @param characteristic The characteristic to write to.
     */
    public void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.writeCharacteristic(characteristic);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }

        if(mBluetoothGatt.setCharacteristicNotification(characteristic, enabled)) {
            UUID uuid = characteristic.getUuid();
            Log.d(TAG, "UUID: " + uuid.toString());
            BluetoothGattDescriptor descriptor = null;
            for (BluetoothGattDescriptor desc : characteristic.getDescriptors()) {
                Log.d(TAG, "descriptor: " + desc.getUuid());
                descriptor = desc;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(descriptor);
        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getServices();
    }

    /**
     * Retrieves a Gatt Service from UUID.
     *
     * @param uuid the string representation of the Gatt Service's UUID
     * @return The Gatt Service or null if not found.
     */
    public BluetoothGattService getGattService(String uuid) {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getService(UUID.fromString(uuid));
    }

    /**
     * Retirieves a Gatt Characteristic using the Parent Service UUID and the
     * Characteristic's UUID.
     *
     * @param svcUuid Parent GATT Service UUID string representation.
     * @param chrUuid Desired GATT Characteristic's UUID string representation.
     * @return The GATT Characteristic or null if failed.
     */
    public BluetoothGattCharacteristic getGattCharacteristic(String svcUuid, String chrUuid) {
        if (mBluetoothGatt == null) return null;
        BluetoothGattService gattSvc = getGattService(svcUuid);
        if (gattSvc == null) return null;
        return gattSvc.getCharacteristic(UUID.fromString(chrUuid));
    }


}

