package com.biozoom.serial;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;


public class Serial extends CordovaPlugin implements SerialListener {

    private enum Connected { False, Pending, True }
    private Connected connected = Connected.False;
    private UsbSerialPort usbSerialPort;
    private SerialService service;
    private String TAG = "CommunicatorUSB";
    private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
    private static final String ACTION_WRITE = "writeSerial";
    private static final String ACTION_OPEN = "openSerial";
    private static final String ACTION_CLOSE = "closeSerial";

    private CallbackContext callbackContext;
    private BroadcastReceiver broadcastReceiver;


    private String command = "";
    private double lastReport = 0;

    private List<Byte> result;


    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        this.lastReport = 0;
        this.result = new ArrayList<Byte>();
        this.callbackContext = callbackContext;
        if (ACTION_REQUEST_PERMISSION.equals(action)) {
            if (this.broadcastReceiver != null) {
                try {
                    cordova.getActivity().unregisterReceiver(this.broadcastReceiver);
                } catch (Exception IllegalArgumentException) {
                    //
                }
            }
            this.broadcastReceiver = null;
            this.broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                        Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        Log.d(TAG, "Granted: " + granted);
                        if (granted) {
                            callbackContext.success("Permission granted.");
                        } else {
                            callbackContext.error("Permission not granted.");
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Constants.INTENT_ACTION_GRANT_USB);
            cordova.getActivity().registerReceiver(this.broadcastReceiver, filter);

        }



        if (this.service == null) {
            this.service = new SerialService();
            this.service.attach(this);

            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            PluginResult result = new PluginResult(PluginResult.Status.OK);
            result.setKeepCallback(true);
        }
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
//        this.callbackContext = callbackContext;
        Log.d(TAG, "Action: " + action);
        Log.d(TAG, "Args: " + args);
        JSONObject arg_object = args.optJSONObject(0);
        // request permission
        if (ACTION_REQUEST_PERMISSION.equals(action)) {
            this.command = "requestPermission";
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            requestPermission(opts, callbackContext);
            return true;
        }
        else if (ACTION_OPEN.equals(action)) {
            this.command = "open";
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            open(opts, callbackContext);
            return true;
        }
        else if (ACTION_CLOSE.equals(action)) {
            this.command = "close";
            disconnect();
            return true;
        }
        else if (ACTION_WRITE.equals(action)) {
            String data = arg_object.getString("command");
            this.command = data;
            byte[] command = data.getBytes(StandardCharsets.UTF_8);
            try {
                service.write(command);
            } catch (IOException e) {
                Log.e(TAG, "ERROR.");
            }

            return true;
        }
        return false;
    }

    private void requestPermission(final JSONObject opts, final CallbackContext callbackContext) {
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        PluginResult result = new PluginResult(PluginResult.Status.OK);
        result.setKeepCallback(true);

        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);

        int vid;
        int pid;
        String driver_type = "CdcAcmSerialDriver";
        if (opts.has("vid") && opts.has("pid")) {
            Object o_vid = opts.opt("vid"); //can be an integer Number or a hex String
            Object o_pid = opts.opt("pid"); //can be an integer Number or a hex String
            vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid, 16);
            pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid, 16);
            //            String driver = opts.has("driver") ? (String) opts.opt("driver") : "CdcAcmSerialDriver";
        } else {
            vid = 0;
            pid = 0;
        }

        if (opts.has("driver")){
            Object o_driver = opts.opt("driver");
            driver_type = o_driver instanceof String ? o_driver.toString() : driver_type;
        }

        int len = usbManager.getDeviceList().values().size();
        if (len == 0) {
            callbackContext.error("No device connected.");
            return;
        }

        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getVendorId() == vid && v.getProductId() == pid)
                device = v;
        if(device == null) {
            callbackContext.error("No device found.");
            return;
        }
        UsbSerialDriver driver;
        switch (driver_type) {
            case "CdcAcmSerialDriver": {
                driver = new CdcAcmSerialDriver(device);
                break;
            }
            case "Cp21xxSerialDriver": {
                driver = new Cp21xxSerialDriver(device);
                break;
            }
            default: {
                driver = new CdcAcmSerialDriver(device);
                break;
            }
        }
        if(driver == null) {
            callbackContext.error("No driver found.");
            return;
        }
        usbSerialPort = driver.getPorts().get(0);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && !usbManager.hasPermission(driver.getDevice())) {
            int flags = PendingIntent.FLAG_MUTABLE;
            Intent intent = new Intent(Constants.INTENT_ACTION_GRANT_USB);
            intent.setPackage(cordova.getActivity().getPackageName());
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0, intent, flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            Log.d(TAG, "Permission requested.");
            return;
        } else {
            callbackContext.success("Permission already granted.");
        }

    }

    private void open(final JSONObject opts, final CallbackContext callbackContext) {

        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);

        int vid;
        int pid;
        int baudrate;
        String driver_type = "CdcAcmSerialDriver";
        if (opts.has("vid") && opts.has("pid")) {
            Object o_vid = opts.opt("vid"); //can be an integer Number or a hex String
            Object o_pid = opts.opt("pid"); //can be an integer Number or a hex String
            Object o_baudrate = opts.opt("baudRate"); //can be an integer Number or a hex String
            vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid, 16);
            pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid, 16);
            baudrate = o_baudrate instanceof Number ? ((Number) o_baudrate).intValue() : Integer.parseInt((String) o_baudrate, 16);
        } else {
            vid = 0;
            pid = 0;
            baudrate = 2000000;
        }

        if (opts.has("driver")){
            Object o_driver = opts.opt("driver");
            driver_type = o_driver instanceof String ? o_driver.toString() : driver_type;
        }

        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getVendorId() == vid && v.getProductId() == pid)
                device = v;
        if(device == null) {
            callbackContext.error("No device found.");
            return;
        }

        UsbSerialDriver driver;
        switch (driver_type) {
            case "CdcAcmSerialDriver": {
                driver = new CdcAcmSerialDriver(device);
                break;
            }
            case "Cp21xxSerialDriver": {
                driver = new Cp21xxSerialDriver(device);
                break;
            }
            default: {
                driver = new CdcAcmSerialDriver(device);
                break;
            }
        }
        if(driver == null) {
            callbackContext.error("No driver found.");
            return;
        }
        usbSerialPort = driver.getPorts().get(0);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());

        if (!usbManager.hasPermission(driver.getDevice())) {
            callbackContext.error("Permission not granted.");
            return;
        }

        connected = Connected.Pending;
        try {
            Log.d(TAG, "Attempting to open port.");
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudrate, 8, 1, 0);
            } catch (UnsupportedOperationException e) {
//                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(cordova.getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            Log.d(TAG, "Connecting...");
            service.connect(socket);
            Log.d(TAG, "Connected!");
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
            Log.d(TAG, "Done");
            callbackContext.success("Connection established.");
        } catch (Exception e) {
            onSerialConnectError(e);
            Log.d(TAG, "Could not open port.");
            callbackContext.error("Could not establish connection.");
        }
    }


    private void disconnect() {
        try {
            connected = Connected.False;
            service.disconnect();
            service = null;
            usbSerialPort = null;
            if (this.broadcastReceiver != null) {
                try {
                    cordova.getActivity().unregisterReceiver(this.broadcastReceiver);
                } catch (Exception IllegalArgumentException) {
                    //
                }
                this.broadcastReceiver = null;
            }

            if (this.callbackContext != null) {
                callbackContext.success("Disconnected.");
            }
        } catch (Exception e) {
            if (this.callbackContext != null) {
                callbackContext.error("Could not disconnect scanner.");
            }
        }
    }



//    @Override
//    public void onServiceConnected(ComponentName name, IBinder binder) {
//        service = ((SerialService.SerialBinder) binder).getService();
//        service.attach(this);
//    }
//@Override
//public void onStart() {
//    super.onStart();
//    this.broadcastReceiver = new BroadcastReceiver() {
//        @Override
//        public void onReceive(Context context, Intent intent) {
//            if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
//                Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
//                Log.d(TAG, "Granted: " + granted);
//            }
//        }
//    };
//    Log.d(TAG, "Registering receiver...");
//
//
//    if (this.service == null) {
//        this.service = new SerialService();
//        this.service.attach(this);
//    }
//    IntentFilter filter = new IntentFilter();
//    filter.addAction(Context.USB_SERVICE);
//    cordova.getActivity().registerReceiver(this.broadcastReceiver, filter);
//}

    @Override
    public void onStop() {
        if (this.broadcastReceiver != null) {
            try {
                cordova.getActivity().unregisterReceiver(this.broadcastReceiver);
            } catch (Exception IllegalArgumentException) {
                //
            }
        }
        if(service != null && !cordova.getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

//    @Override
//    public void onStart() {
//        Log.d(TAG, "ABCDASD");
//        super.onStart();
//        if (service != null)
//            service.attach(this);
//        else
//            cordova.getActivity().startService(new Intent(cordova.getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
//    }

    @Override
    public void onSerialConnect() {
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        Iterator<byte[]> desIterate = datas.iterator();
        while(desIterate.hasNext()) {
            byte[] el = desIterate.next();
            for (byte e : el) {
                this.result.add(e);
            }
        }
        byte[] result = new byte[this.result.size()];
        for (int i = 0; i < this.result.size(); i++) {
            result[i] = this.result.get(i);
        }
        try {
            switch (this.command) {
                case "W": {
                    float fLow = ByteBuffer.wrap(Arrays.copyOfRange(result, 2, 6)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                    float fHigh = ByteBuffer.wrap(Arrays.copyOfRange(result, 10, 14)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                    float[] values = {fLow, fHigh};
                    String s = Arrays.toString(values);
                    Log.d(TAG, "" + s);
                    this.callbackContext.success(s);
                    break;
                }
                case "M": {
                    byte[] encoded = Base64.getEncoder().encode(result);
                    String s = new String(encoded);

                    float progress = (((float)s.length()) / 254012) * 100;
                    double progressInt = Math.floor(progress);
                    if (progressInt > this.lastReport) {
                        this.lastReport = progressInt;
                        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, String.valueOf(progressInt));
                        pluginResult.setKeepCallback(true); // keep callback
                        callbackContext.sendPluginResult(pluginResult);
                    }

                    if (s.length() >= 254012) {
                        this.callbackContext.success(s);
                    }
                    break;
                }
                case "get_status\n": {
                    String s = new String(result, StandardCharsets.UTF_8);
                    Log.d(TAG, "" + s);
                    if (s.contains("\n")) {
                        this.callbackContext.success(s);
                    }
                    break;
                }
                case "I": {
                    if (result.length < 28) {
                        break;
                    }
                    byte[] part1 = Arrays.copyOfRange(result, 2, 2+6);
                    byte[] part2 = Arrays.copyOfRange(result, 2+6, 2+13);
                    String s = new String(part1, StandardCharsets.UTF_8);
                    StringBuilder res = new StringBuilder();
                    for (byte a: part2) {
                        res.append(String.format("%02X", a));
                    }
                    s = s + res.toString();
                    if (s.contains("\u0000")) {
                        this.callbackContext.error(s);
                        break;
                    }
                    this.callbackContext.success(s);
                    break;
                }
                case "requestPermission":
                case "open":
                case "close": {
                    break;
                }
                default: {
                    this.callbackContext.success("Execution done.");
                    break;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "ERROR");
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        disconnect();
    }
}
