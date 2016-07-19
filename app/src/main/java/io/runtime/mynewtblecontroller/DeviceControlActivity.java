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

package io.runtime.mynewtblecontroller;

import android.app.DialogFragment;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device.  The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends AppCompatActivity implements InitGpioDialogFragment.InitGpioDialogListener {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    /* GPIO Service and Characteristic UUIDs */
    public static final String UUID_GPIO_SVC = "59462f12-9543-9999-12c8-58b459a2712b";
    public static final String UUID_GPIO_CHR_INSTR = "5c3a659e-897e-45e1-b016-007107c96db7";
    public static final String UUID_GPIO_CHR_NOTIFY = "5c3a659e-897e-45e1-b016-007107c96db8";

    /* To get device name and address from DeviceScanActivity */
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    /* BluetoothLeService reference */
    private BluetoothLeService mBluetoothLeService;
    private boolean mConnected = false;

    /* GPIO Gatt Service/Characteristic */
    private BluetoothGattService mGpioService;
    private BluetoothGattCharacteristic mGpioInstrChr;
    private BluetoothGattCharacteristic mGpioNotifChr;

    /* Connection Information Display */
    private TextView mConnectionState;
    private TextView mDataField;
    private String mDeviceName;
    private String mDeviceAddress;

    /* GPIO List */
    private ListView mGpioList;
    private GpioListAdapter mGpioListAdapter;
    private ArrayList<GpioPin> gpioPins = new ArrayList<GpioPin>();
    private Button mNewGpioButton;

    /* Loading Gatt Services dialog */
    private ProgressDialog mProgressDialog;

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
            // Set the BLE service for our GPIO List Adapter
            mGpioListAdapter.setBleService(mBluetoothLeService);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    /**
     * Handles various events fired by the Service.
     *
     * ACTION_GATT_CONNECTED: connected to a GATT server.
     * ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
     * ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
     * ACTION_DATA_AVAILABLE: Data has been received from a read/write to the device.
     *                        If there is data in EXTRA_DATA then
     */
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "Broadcast received, action=" + action);
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                Log.d(TAG, "Gatt services discovered.");
                // Init our GPIO Service and Characteristic
                mGpioInstrChr = mBluetoothLeService.getGattCharacteristic(UUID_GPIO_SVC,
                        UUID_GPIO_CHR_INSTR);
                mGpioNotifChr = mBluetoothLeService.getGattCharacteristic(UUID_GPIO_SVC,
                        UUID_GPIO_CHR_NOTIFY);

                mBluetoothLeService.setCharacteristicNotification(mGpioNotifChr, true);
                mProgressDialog.dismiss();
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                // Get instrction and data from intent
                int instr = intent.getIntExtra(BluetoothLeService.EXTRA_INSTR, 0);
                int data = intent.getIntExtra(BluetoothLeService.EXTRA_DATA, -1);
                Log.d(TAG, String.format("Instruction received: 0x%x", instr));
                Log.d(TAG, (data == -1 ?
                        "No data received" :
                        "Data received: " + (data == 0 ? "Low" : "High")));
                displayData(String.format("0x%x", instr));

                // Make new GpioPin from instruction
                GpioPin pin = new GpioPin(instr);
                // Check if there is data available
                if(data != -1) {
                    // There is data available (i.e. read instruction)
                    pin.isHigh = (data != 0);
                }
                // Find and update pin in gpioPins
                if(!findAndUpdatePin(pin)) {
                    // Add pin if we cannot find pin
                    addPin(pin);
                }
            } else if (BluetoothLeService.ACTION_NOTIFICATION_DATA_AVAILABLE.equals(action)) {
                // Get pin info and update gpio list
                int pinInfo = intent.getIntExtra(BluetoothLeService.EXTRA_NOTIFICATION, 0);
                GpioPin pin = new GpioPin(pinInfo);
                findAndUpdatePin(pin);
            }
        }
    };

    /**
     * Add a pin to the list of gpio pins and sort the list. After sorting, notify
     * the adapter that the data set has been updated.
     *
     * @param pin: the GpioPin to add to GpioPins
     */
    private void addPin(GpioPin pin) {
        gpioPins.add(pin);
        Collections.sort(gpioPins, new Comparator<GpioPin>() {
            @Override
            public int compare(GpioPin p1, GpioPin p2) {
                if (p1.pinNumber < p2.pinNumber) {
                    return -1;
                } else if (p1.pinNumber > p2.pinNumber) {
                    return 1;
                }
                return 0;
            }
        });
        mGpioListAdapter.notifyDataSetChanged();
    }

    /**
     * Look for a pin with matching pin number in gpioPins. If found update the isHigh
     * value and return true, otherwise return false.
     * @param pin: the pin to find and update
     * @return true if found, false otherwise
     */
    public boolean findAndUpdatePin(GpioPin pin) {
        for (GpioPin p : gpioPins) {
            if(p.pinNumber == pin.pinNumber) {
                p.isHigh = pin.isHigh;
                mGpioListAdapter.notifyDataSetChanged();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        // Get the device name and address
        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mDataField = (TextView) findViewById(R.id.data_value);
        mNewGpioButton = (Button) findViewById(R.id.new_gpio_btn);
        mGpioList = (ListView) findViewById(R.id.gpio_list);

        // Set up and set the GPIO List Adapter
        mGpioListAdapter = new GpioListAdapter(this, R.layout.list_item_gpio, gpioPins);
        mGpioList.setAdapter(mGpioListAdapter);

        // Set up progress dialog
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setMessage("Discovering Gatt Services...");
        mProgressDialog.setCanceledOnTouchOutside(true);

        // Set up listeners
        mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialogInterface) {
                finish();
            }
        });
        mNewGpioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DialogFragment initGpioFragment = new InitGpioDialogFragment();
                initGpioFragment.show(getFragmentManager(), "new_gpio");
            }
        });

        //Show gatt service progress dialog
        mProgressDialog.show();

        getSupportActionBar().setTitle(mDeviceName);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayData(String data) {
        if (data != null) {
            mDataField.setText(data);
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_NOTIFICATION_DATA_AVAILABLE);
        return intentFilter;
    }

    /**
     * Called when InitGpioPinDialogFragment positive button is clicked. This function
     * gets the values from the dialog and builds the instruction.
     *
     * @param dialog the InitGpioDialogFragment that was positively closed
     */
    @Override
    public void onDialogPositiveClick(DialogFragment dialog) {
        //Get views from dialog
        EditText pinNumberView = (EditText) dialog.getDialog().findViewById(R.id.pin_number);
        Spinner ioSpinner = (Spinner) dialog.getDialog().findViewById(R.id.io_spinner);
        Switch initValSwitch = (Switch) dialog.getDialog().findViewById(R.id.initial_value_switch);

        // Parse dialog views for isOutput, pinNumber, and isHigh
        String pinNumberStr = pinNumberView.getText().toString();
        if (pinNumberStr.isEmpty()) {
            Toast.makeText(DeviceControlActivity.this,
                    "Please specify a pin number.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create instruction
        int pinNumber = Integer.parseInt(pinNumberStr);
        boolean isOutput = (ioSpinner.getSelectedItemPosition() + 1) == 1;
        boolean isHigh = initValSwitch.isChecked();
        int instr = GpioPin.createGpioInstr(new GpioPin(pinNumber, isOutput, isHigh));

        // Get the GPIO characteristic from BluetoothLeService
        mGpioInstrChr = mBluetoothLeService.getGattCharacteristic(UUID_GPIO_SVC,
                UUID_GPIO_CHR_INSTR);
        // Set the characteristic value and write to device
        mGpioInstrChr.setValue(instr, BluetoothGattCharacteristic.FORMAT_UINT16, 0);
        mBluetoothLeService.writeCharacteristic(mGpioInstrChr);
    }
}
