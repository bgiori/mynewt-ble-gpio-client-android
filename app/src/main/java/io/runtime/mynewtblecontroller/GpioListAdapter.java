package io.runtime.mynewtblecontroller;

import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Created by briangiori on 7/12/16.
 */
public class GpioListAdapter extends ArrayAdapter<GpioPin> {
    private final Context context;
    private ArrayList<GpioPin> pins;
    private int resource;
    private BluetoothLeService bleService;

    public GpioListAdapter(Context context, int resource, ArrayList<GpioPin> values) {
        super(context, resource, values);
        this.context = context;
        this.pins = values;
        this.resource = resource;

    }

    public void setBleService(BluetoothLeService bleService) {
        this.bleService = bleService;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View rowView = inflater.inflate(resource, parent, false);
        // Set up row's view references
        TextView pinNumber = (TextView) rowView.findViewById(R.id.gpio_list_pin);
        TextView direction = (TextView) rowView.findViewById(R.id.gpio_list_dir);
        TextView readValue = (TextView) rowView.findViewById(R.id.gpio_list_read_value);
        Switch writeSwitch = (Switch) rowView.findViewById(R.id.gpio_list_write_switch);
        // Get pin from position
        final GpioPin pin = pins.get(position);

        // Set up views based on pin
        pinNumber.setText(String.valueOf(pin.pinNumber));
        direction.setText(String.valueOf(pin.isOuput ? "Output" : "Input"));
        if(pin.isOuput) {
            writeSwitch.setVisibility(View.VISIBLE);
            writeSwitch.setChecked(pin.isHigh);
            writeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                    compoundButton.setChecked(b);
                    BluetoothGattCharacteristic gpioChr = bleService.getGattCharacteristic(
                            DeviceControlActivity.UUID_GPIO_SVC,
                            DeviceControlActivity.UUID_GPIO_CHR_INSTR);
                    GpioPin tmpPin = new GpioPin(pin.pinNumber, pin.isOuput, b);
                    int instr = GpioPin.createGpioInstr(tmpPin);
                    gpioChr.setValue(instr, BluetoothGattCharacteristic.FORMAT_UINT16, 0);
                    bleService.writeCharacteristic(gpioChr);
                }
            });
        } else {
            readValue.setVisibility(View.VISIBLE);
            Log.i(getClass().toString(), "Setting text value...isHigh=" + pin.isHigh);
            readValue.setText(pin.isHigh ? "High" : "Low");
        }

        return rowView;
    }
}


