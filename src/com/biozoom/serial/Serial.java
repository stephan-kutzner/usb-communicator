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
import android.util.Base64;

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
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;


public class Serial extends CordovaPlugin implements SerialListener {

    private enum Connected { False, Pending, True }
    private UsbSerialPort usbSerialPort;
    private SerialService service;
    private BlockFormat blockFormat;
    private String TAG = "CommunicatorUSB";
    private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
    private static final String ACTION_WRITE = "writeSerial";
    private static final String ACTION_OPEN = "openSerial";
    private static final String ACTION_CLOSE = "closeSerial";
    private JSONObject optsLast;

    private CallbackContext callbackContext;
    private BroadcastReceiver broadcastReceiver;


    private String command = "";
    private boolean resultSent = true;
    private double lastReport = 0;
    private double lastRead = 0;
    private double minWait = 0;
    private int version = 1;
    private String uuid = "";
    private boolean isMatrix = false;
    private static final String[][] deviceTypes = {
            {"04b4", "0003", "CdcAcmSerialDriver"},
            {"0416", "b002", "CdcAcmSerialDriver"},
            {"10c4", "ea60", "Cp21xxSerialDriver"},
    };

    private List<Byte> result;


    /**
     * entrypoint of all external requests
     * @param action String representing the desired action
     * @param args Object containing all additional parameters
     * @param callbackContext callback context object, can be used to call .success and .error
     *                        with a result-message
     * @return
     * @throws JSONException
     */
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {

        if (this.blockFormat == null) {
            this.blockFormat = new BlockFormat();
        }

        double startDate = new Date().getTime();
        int sleepDelay = 5;
        if ((this.command == "M" || this.command == "K") && !this.resultSent) {
            sleepDelay = 50;
        }
        while(true) {
            double now = new Date().getTime();

            if (now > startDate + 10000) {
                this.callbackContext.error("Device busy.");
                throw new RuntimeException();
            }

            if (now > (this.lastRead + sleepDelay) && now > this.minWait) {
                break;
            }
            Log.d(TAG, "Sleeping...");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        double now = new Date().getTime();
        this.lastRead = now;
        this.lastReport = 0;
        this.result = new ArrayList<Byte>();
        this.resultSent = false;
        this.callbackContext = callbackContext;

        /**
         * in case permissions for a device are requested, create a new listener
         * that reacts to permission granted/denied
         * do this only for permission requests, as any other request will never cause this trigger,
         * and re-creating the listener for this would be unnecessary overhead
         */
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
            cordova.getActivity().registerReceiver(this.broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        }


        /**
         * if no service exists, create one and attach this class as the listener
         */
        if (this.service == null) {
            this.service = new SerialService();
            this.service.attach(this);

            // Test: is this required?
//            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
//            PluginResult result = new PluginResult(PluginResult.Status.OK);
//            result.setKeepCallback(true);
        }
        // Maybe reenable?
//        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        Log.d(TAG, "Action: " + action);
        Log.d(TAG, "Args: " + args);
        JSONObject arg_object = args.optJSONObject(0);
        // request permission
        if (ACTION_REQUEST_PERMISSION.equals(action)) {
            this.command = "requestPermission";
            requestPermission();
            return true;
        }
        else if (ACTION_OPEN.equals(action)) {
            this.command = "open";
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            this.optsLast = opts;
            open(opts, true);
            return true;
        }
        else if (ACTION_CLOSE.equals(action)) {
            this.command = "close";
            disconnect();
            return true;
        }
        else if (ACTION_WRITE.equals(action)) {
            String data = arg_object.getString("command");
            String versionStr = arg_object.getString("version");
            String uuid = arg_object.getString("id");
            this.version = Integer.parseInt(versionStr);
            this.uuid = uuid;
            if (this.version == 2) {
                data = data.replace("\n", "");
                if (data.contains("?")) {
                    data = data + "&call-index=" + uuid;
                } else {
                    data = data + "?call-index=" + uuid;
                }
                data += "\n";
            }
            this.command = data;
            byte[] command = data.getBytes(StandardCharsets.UTF_8);
            byte[] inputData;
            inputData = command;
            try {
                service.write(inputData);
                this.minWait = new Date().getTime();
                Log.d(TAG, "" + data);
                if (data.equals("W") || data.startsWith("hand")) {
                    Log.d(TAG, "COMMAND W RECEIVED");
                    this.minWait += 1000;
                }
            } catch (IOException e) {
                Log.e(TAG, "ERROR. Retrying...");
                this.retry();
                return true;
            }

            return true;
        }
        return false;
    }

    /**
     * check whether any connected usb device satisfies the given criteria
     * if so, create a driver for it
     * @param usbManager usb interface object
     * @return driver for the matched usb device
     */
    private UsbSerialDriver getDevice(UsbManager usbManager) {
        UsbDevice device = null;
        String driver_type = "";

        for(UsbDevice v : usbManager.getDeviceList().values()) {
            for(String[] w: deviceTypes) {
                int vid = Integer.parseInt(w[0], 16);
                int pid = Integer.parseInt(w[1], 16);
                if(
                        v.getVendorId() == vid
                                && v.getProductId() == pid
                ) {
                    device = v;
                    driver_type = w[2];
                    break;
                }
            }
            if (driver_type != "") {
                break;
            }
        }
        if(device == null) {
            return null;
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
        return driver;
    }

    /**
     * check whether a usb-device is connected that satisfies the given criteria (vendor ID, product ID)
     * if so, request permission if it wasn't granted before, or return with success in case
     * permission was already granted
     * @param opts additional parameters
     * @param callbackContext callback context, used for .success and .error
     */
    private void requestPermission() {
//        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
//        PluginResult result = new PluginResult(PluginResult.Status.OK);
//        result.setKeepCallback(true);

        UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);

        /**
         * if no device is connected at all, return an error message here already
         */
        int len = usbManager.getDeviceList().values().size();
        if (len == 0) {
            this.callbackContext.error("No device connected.");
            return;
        }

        UsbSerialDriver driver = getDevice(usbManager);
        if(driver == null) {
            this.callbackContext.error("No driver found.");
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
        } else {
            this.callbackContext.success("Permission already granted.");
        }

    }

    /**
     * attempt to open a connection to the attached device
     * use the baudrate provided via the opts argument
     * @param opts object containing the parameter 'baudRate'
     */
    private void open(final JSONObject opts, boolean feedback) {
        UsbManager usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);

        int baudrate;
        if (opts.has("baudRate")) {
            Object o_baudrate = opts.opt("baudRate"); //can be an integer Number or a hex String
            baudrate = o_baudrate instanceof Number ? ((Number) o_baudrate).intValue() : Integer.parseInt((String) o_baudrate, 16);
        } else {
            baudrate = 2000000;
        }

        UsbSerialDriver driver = getDevice(usbManager);
        if(driver == null) {
            if (feedback) {
                this.callbackContext.error("No driver found.");
            }
            return;
        }
        usbSerialPort = driver.getPorts().get(0);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());

        if (!usbManager.hasPermission(driver.getDevice())) {
            if (feedback) {
                this.callbackContext.error("Permission not granted.");
            }
            return;
        }

        try {
            Log.d(TAG, "Attempting to open port.");
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudrate, 8, 1, 0);
            } catch (UnsupportedOperationException e) {
            }
            SerialSocket socket = new SerialSocket(cordova.getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            Log.d(TAG, "Connecting...");
            service.connect(socket);
            Log.d(TAG, "Connected!");
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            Log.d(TAG, "Done");

            Thread.sleep(10);
            usbSerialPort.setDTR(false);
            Thread.sleep(10);
            usbSerialPort.setRTS(false);
            Thread.sleep(500);

            if (feedback) {
                this.callbackContext.success("Connection established.");
            }
        } catch (Exception e) {
            onSerialConnectError(e);
            Log.d(TAG, "Could not open port.");
            if (feedback) {
                this.callbackContext.error("Could not establish connection.");
            }
        }
    }


    private void disconnect() {
        try {
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
                this.callbackContext.success("Disconnected.");
            }
        } catch (Exception e) {
            if (this.callbackContext != null) {
                this.callbackContext.error("Could not disconnect scanner.");
            }
        }
    }

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

    private void retry() {
        for(int i = 0; i < 2; i++) {
            try {
                Log.e(TAG, "Attempt " + i + "...");
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
                        }
                    }
                };
                IntentFilter filter = new IntentFilter();
                filter.addAction(Constants.INTENT_ACTION_GRANT_USB);
                cordova.getActivity().registerReceiver(this.broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

                if (this.service == null) {
                    this.service = new SerialService();
                    this.service.attach(this);
                }
                Thread.sleep(100);



//                disconnect();
//                Thread.sleep(1000);
                open(this.optsLast, false);
                Thread.sleep(1000);
                String data = this.command;
                byte[] command = data.getBytes(StandardCharsets.UTF_8);
                byte[] inputData;
                inputData = command;
//            for (byte b: inputData) {
//                Log.d(TAG, String.format("%02X", b));
//            }
                try {
                    service.write(inputData);
                    this.minWait = new Date().getTime();
                    Log.d(TAG, "" + data);
                    if (data.equals("W") || data.startsWith("hand")) {
                        Log.d(TAG, "COMMAND W RECEIVED");this.minWait += 1000;
                    }
                    return;
                } catch (IOException e) {
                    Log.e(TAG, "ERROR.");
                    Thread.sleep(200);
                }

            } catch (Exception e) {
                Log.e(TAG, "Failure.");
                try {
                    Thread.sleep((i + 1) * 200);
                } catch (Exception e2) {}
            }
            //
        }
    }

    @Override
    public void onSerialConnect() {
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

    /**
     * Called when incoming data is available
     * Handle data depending on which method was last called
     * @param datas byte-array of raw data
     */
    public void onSerialRead(ArrayDeque<byte[]> datas) {
        if (this.version == 2) {
            this.onSerialRead2(datas);
            return;
        }
        /**
         * add each received byte to the persistent result-list,
         * then handle the full result if possible
         */
        try {


            lastRead = new Date().getTime();
            Iterator<byte[]> desIterate = datas.iterator();
            while (desIterate.hasNext()) {
                byte[] el = desIterate.next();
                for (byte e : el) {
                    this.result.add(e);
                }
            }
            // transform the persistent result-list into an array
            byte[] result = new byte[this.result.size()];
            for (int i = 0; i < this.result.size(); i++) {
                result[i] = this.result.get(i);
            }
            if (this.resultSent) {
                return;
            }

            if (result.length == 0) {
                return;
            }

            try {
                switch (this.command) {
                    case "W": {
                        // hand-recognition
                        if (result.length < 14) {
                            break;
                        }
                        float fLow = ByteBuffer.wrap(Arrays.copyOfRange(result, 2, 6)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                        float fHigh = ByteBuffer.wrap(Arrays.copyOfRange(result, 10, 14)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
                        float[] values = {fLow, fHigh};
                        String s = Arrays.toString(values);
                        this.callbackContext.success(s);
                        this.resultSent = true;
                        break;
                    }
                    // TODO: Replace command
                    case "K": {
                        // aox measurement
                        byte[] encoded = Base64.encode(result, Base64.NO_WRAP);
                        String s = new String(encoded);

                        // TODO: Replace command
                        int targetLength = isMatrix ? 49230 : 127028;
                        //                    if (this.command == "K") {
                        //                        targetLength = 1;
                        //                    }

                        // report the current progress back as a non-finishing-success
                        float progress = (((float) s.length()) / targetLength) * 100;
                        double progressInt = Math.floor(progress);
                        if (progressInt > this.lastReport) {
                            this.lastReport = progressInt;
                            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, String.valueOf(progressInt));
                            pluginResult.setKeepCallback(true); // keep callback
                            this.callbackContext.sendPluginResult(pluginResult);
                        }
                        if (s.length() >= targetLength) {
                            this.resultSent = true;
                            this.callbackContext.success(s);
                        }
                        break;
                    }
                    case "M": {
                        // aox measurement
                        byte[] encoded = Base64.encode(result, Base64.NO_WRAP);
                        String s = new String(encoded);

                        // TODO: Replace command
                        int targetLength = isMatrix ? 98460 : 254012;

                        // report the current progress back as a non-finishing-success
                        float progress = (((float) s.length()) / targetLength) * 100;
                        double progressInt = Math.floor(progress);
                        if (progressInt > this.lastReport) {
                            this.lastReport = progressInt;
                            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, String.valueOf(progressInt));
                            pluginResult.setKeepCallback(true); // keep callback
                            this.callbackContext.sendPluginResult(pluginResult);
                        }
                        if (s.length() >= targetLength) {
                            this.resultSent = true;
                            this.callbackContext.success(s);
                        }
                        break;
                    }
                    case "get_status":
                    case "get_status\n": {
                        // get a status-json
                        String s = new String(result, StandardCharsets.UTF_8);

//                        Log.d(TAG, "resStr: " + s);
                        if (s.contains("\n")) {
                            try {
                                JSONObject obj = new JSONObject(s);
                                String type = obj.getString("type");
                                if (type.equals("info")) {
                                    this.result = new ArrayList<Byte>();
                                    break;
                                }
                            } catch (Exception ex) {
                            }

                            try {
                                JSONObject obj = new JSONObject(s);
                                String ser = obj.getString("serial");
                            } catch (Exception ex) {
                                this.result = new ArrayList<Byte>();
                                break;
                            }

                            if (s.contains("error")) {
                                this.result = new ArrayList<Byte>();
                                break;
                            }
                            this.resultSent = true;
                            this.callbackContext.success(s);
                        }
                        break;
                    }
                    case "I": {
                        // get the serial number of the scanner
                        if (result.length < 28) {
                            break;
                        }
                        byte[] part1 = Arrays.copyOfRange(result, 2, 2 + 6);
                        byte[] part2 = Arrays.copyOfRange(result, 2 + 6, 2 + 13);
                        String s = new String(part1, StandardCharsets.UTF_8);
                        StringBuilder res = new StringBuilder();
                        for (byte a : part2) {
                            res.append(String.format("%02X", a));
                        }

//                        byte partVersion = result[23];
//                        String versionString = String.format("%02X", partVersion);
//                        Log.d(TAG, "VersionStr: " + versionString);
//                        this.version = 1;
//                        if (versionString.equals("02")) {
//                            this.version = 2;
//                            Log.d(TAG, "Version set to 2");
//                        } else {
//                            this.version = 1;
//                            Log.d(TAG, "Version set to 1");
//                        }

                        s = s + res.toString();
                        if (s.startsWith("4W") || s.startsWith("5W")) {
                            isMatrix = true;
                        } else {
                            isMatrix = false;
                        }

                        s = s + "-1";

                        this.resultSent = true;
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
                        // ignore the result
                        break;
                    }
                    case "status": {
                        String s = new String(result, StandardCharsets.UTF_8);
                        if (s.contains("\n")) {
                            this.resultSent = true;
                            this.callbackContext.success(s);
                        }
                        break;
                    }
                    default: {
                        this.resultSent = true;
                        this.callbackContext.success("Execution done.");
                        break;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "ERROR");
            }
        } catch (Exception e) {
            this.resultSent = true;
            this.callbackContext.error("Error: Connection lost");
//            return;
        }
    }

    public void onSerialRead2(ArrayDeque<byte[]> datas) {
        try {
            lastRead = new Date().getTime();
            Iterator<byte[]> desIterate = datas.iterator();
            while (desIterate.hasNext()) {
                byte[] el = desIterate.next();
                for (byte e : el) {
                    this.result.add(e);
                }
            }
            // transform the persistent result-list into an array
            byte[] result = new byte[this.result.size()];
            for (int i = 0; i < this.result.size(); i++) {
                result[i] = this.result.get(i);
            }
            if (this.resultSent) {
                return;
            }

            if (result.length == 0) {
                return;
            }

            try {
                switch (this.command) {
                    case "requestPermission":
                    case "open":
                    case "close": {
                        // ignore the result
                        break;
                    }
                    default: {
                        String s = new String(result, StandardCharsets.UTF_8);
                        Log.d(TAG, s);
                        if (s.contains("\n")) {
                            Log.d(TAG, "uuid: " + this.uuid + ", command: " + this.command);
                            try {
                                JSONObject obj = new JSONObject(s);
                                if (!obj.has("call-index")) {
                                    this.result = new ArrayList<Byte>();
                                    break;
                                }
                                String callUUID = obj.getString("call-index");
                                if (!callUUID.equals(this.uuid)) {
                                    this.result = new ArrayList<Byte>();
                                    break;
                                }
                                Log.d(TAG, "UUID check complete");
                            } catch (Exception ex) {
                            }

                            try {
                                JSONObject obj = new JSONObject(s);
                                if (!obj.has("command")) {
                                    this.result = new ArrayList<Byte>();
                                    break;
                                }
                                String cmd = obj.getString("command");
                                if (!(this.command.startsWith(cmd))) {
                                    this.result = new ArrayList<Byte>();
                                    break;
                                }
                                Log.d(TAG, "Command check complete");
                            } catch (Exception ex) {

                            }


                            this.resultSent = true;
                            this.callbackContext.success(s);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "ERROR");
            }
        } catch (Exception e) {
            this.resultSent = true;
            this.callbackContext.error("Error: Connection lost");
//            return;
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        disconnect();
    }

}
