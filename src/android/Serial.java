package com.biozoom.serial;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Array;




import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
//import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.IBinder;
//import android.util.Base64;
import android.util.Log;
import java.util.HashMap;


public class Serial extends CordovaPlugin implements SerialListener {

    private enum Connected { False, Pending, True }
    private Connected connected = Connected.False;
    private UsbSerialPort usbSerialPort;
    private SerialService service;
    private boolean controlLinesEnabled = false;
    private String TAG = "CommunicatorUSB";
    private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
    private static final String ACTION_WRITE = "writeSerial";
    private static final String ACTION_OPEN = "openSerial";
    private static final String ACTION_CLOSE = "closeSerial";
    private static final String USB_PERMISSION = "com.biozoom.serial.USB_PERMISSION";

    private CallbackContext callbackContext;
    private BroadcastReceiver broadcastReceiver;


    private String command = "";

    private List<Byte> result;

//    public Serial() {
//    }




    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
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
        Log.d(TAG, "Action: " + this.broadcastReceiver);
        Log.d(TAG, "Action: " + action);
        Log.d(TAG, "Args: " + args);
        JSONObject arg_object = args.optJSONObject(0);
        // request permission
        if (ACTION_REQUEST_PERMISSION.equals(action)) {
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            requestPermission(opts, callbackContext);
            return true;
        }
        else if (ACTION_OPEN.equals(action)) {
            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
            open(opts, callbackContext);
            return true;
        }
        else if (ACTION_CLOSE.equals(action)) {
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
        Log.d(TAG, driver_type);

        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getVendorId() == vid && v.getProductId() == pid)
                device = v;
        if(device == null) {
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
            callbackContext.success("DONE!!!");
        }

    }

    private void open(final JSONObject opts, final CallbackContext callbackContext) {

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

        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getVendorId() == vid && v.getProductId() == pid)
                device = v;
        if(device == null) {
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
                usbSerialPort.setParameters(2000000, 8, 1, 0);
            } catch (UnsupportedOperationException e) {
//                status("Setting serial parameters failed: " + e.getMessage());
            }
            SerialSocket socket = new SerialSocket(cordova.getActivity().getApplicationContext(), usbConnection, usbSerialPort);
            Log.d(TAG, "" + service);
            Log.d(TAG, "" + socket);
            Log.d(TAG, "Connecting...");
            service.connect(socket);
            Log.d(TAG, "Connected!");
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
            Log.d(TAG, "Done");
        } catch (Exception e) {
            onSerialConnectError(e);
            Log.d(TAG, "Could not open port.");
        }
    }


    private void disconnect() {
        try {
            connected = Connected.False;
            service.disconnect();
            service = null;
            usbSerialPort = null;
            cordova.getActivity().unregisterReceiver(this.broadcastReceiver);
            this.broadcastReceiver = null;

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
@Override
public void onStart() {
    Log.d(TAG, "Registering receiver...");
    super.onStart();
    Log.d(TAG, "Registering receiver...");
    this.broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "" + intent);
            if(Constants.INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                Boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.d(TAG, "Granted: " + granted);
            }
        }
    };
    Log.d(TAG, "Registering receiver...");


    if (this.service == null) {
        Log.d(TAG, "Action1");
        this.service = new SerialService();
        Log.d(TAG, "Action2");
        this.service.attach(this);
        Log.d(TAG, "Action3");
    }
    Log.d(TAG, "Registering receiver...");
    IntentFilter filter = new IntentFilter();
    Log.d(TAG, "Registering receiver...");
    filter.addAction(Context.USB_SERVICE);
    Log.d(TAG, "Registering receiver...");
    cordova.getActivity().registerReceiver(this.broadcastReceiver, filter);
    Log.d(TAG, "Receiver registered.");
}

    @Override
    public void onStop() {
        cordova.getActivity().unregisterReceiver(this.broadcastReceiver);
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
        Log.d(TAG, "123");
//        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
//        Object[] data = datas.toArray();
//        byte[][] result = new byte[datas.size()][];
//        List<Byte> merged = new ArrayList<Byte>(Arrays.asList(this.result));
//        Log.d(TAG, "" + result);
        Iterator<byte[]> desIterate = datas.iterator();
        while(desIterate.hasNext()) {
            byte[] el = desIterate.next();
            for (byte e: el) {
                this.result.add(e);
            }
//            merged = Bytes.concat(merged, el);
//            result[i] = el;
//            Log.d(TAG, "" + el);
//            i += 1;
//            System.out.print(desIterate.next());
//            System.out.print(", ");
        }
//        for(int i = 0; i < datas.size(); i++) {
//            Log.d(TAG, "" + datas[i]);
////            result[i] = Array.getByte(data, i);
////            Log.d(TAG, "" + result[i]);
//        }
        byte[] result = new byte[this.result.size()];
        for (int i = 0; i < this.result.size(); i++) {
            result[i] = this.result.get(i);
        }
//        this.result = result;
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
                    //                    println(new String(encoded));   // Outputs "SGVsbG8="
                    String s = new String(encoded);
                    Log.e(TAG, "" + s.length());
                    if (s.length() >= 254012) {
                        this.callbackContext.success(s);
                    }
                    break;
                }
                default: {
                    String s = new String(result, StandardCharsets.UTF_8);
                    Log.d(TAG, "" + s);
                    if (s.contains("\n")) {
                        this.callbackContext.success(s);
                    }
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



//interface Communicator {
//
//    String INTERFACE_READY = "com.biozoom.healthtracker.communicationComponent.Communicator.INTERFACE_READY";
//    String INTERFACE_DISCONNECT = "com.biozoom.healthtracker.communicationComponent.Communicator.INTERFACE_DISCONNECT";
//    String GET_SERIAL = "com.biozoom.healthtracker.communicationComponent.Communicator.GET_SERIAL";
////    enum Protocol{CLASSIC, STICK};
////
////    void setProtocol(Protocol protocol);
////
//    void requestPermissionForDevice();
////
////    void closeConnection();
////
//    Boolean openConnection();
////
////    Boolean isConnected();
////
////    Boolean getSerial(byte[] getSerials, GetSerialListener listener);
////
////    Boolean transferUpdate(HashMap<String, byte[]> commandMap, File updateFile, TransferUpdateListener listener);
////
////    Boolean checkSensorFunction(byte[] bCommand, CheckSensorFunctionListener listener, int timeout);
////
////    Boolean startHandPatternMatching(byte[] startHandPatternMatchings, HandPatternMatchingListener listener, float[] limits);
////
////    Boolean startMeasureAox(byte[] startMeasureAoxes, MeasureAoxListener measureListener);
////
////    Boolean startMeasureDerma(byte[] startMeasureDermas, MeasureDermaListener measureListener, int timeout);
////
////    Boolean startMeasureHrv(byte[] startMeasureHrvs, MeasureHrvListener measureListener, int timeout, int duration);
////
////    boolean permissionGranted();
////
////    void onDestroy();
////
////    int getNumberOfHRVPackets();
//}
//
//interface DeviceEventListener {
//
//}
//
///**
// * Singleton class for low-level communication with the scanner device. Communication baudrate is
// * fixed to 250k BAUD. Direct USB function calls are used for communication. Only tested with
// * Cypress CY7C65213
// */
//public class Serial extends CordovaPlugin {
//
//    private static final String ACTION_USB_PERMISSION = "com.biozoom.serial.USB_PERMISSION";
//    private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
//    private static final String ACTION_OPEN = "openSerial";
//    private static final String ACTION_CLOSE = "closeSerial";
//    private static final String ACTION_WRITE = "writeSerial";
//    private static final String INTERFACE_READY = "com.biozoom.serial.INTERFACE_READY";
//    private static final String INTERFACE_DISCONNECT = "com.biozoom.serial.INTERFACE_DISCONNECT";
//    private static final String GET_SERIAL = "com.biozoom.serial.GET_SERIAL";
//
//    private static Serial instance = null;
//
////    private static CommunicatorUSB instance = null;
//    final private int BAUDRATE = 2000000;  ///< Fixed baudrate
//    final private int TIMEOUT = 2000;                   ///< 2 seconds for USB-Calls
//    final private int MAX_MEASUREMENT_BYTES = 512000;   ///< Buffer size for complete measurement
//    private String TAG = "CommunicatorUSB";
//    private UsbManager _usbManager;
//    private UsbDevice _usbDevice;
//    private UsbDeviceConnection _usbDeviceConnection;
//    private UsbRequest _usbRequest;
//    private UsbEndpoint _usbEndpointIn;
//    private UsbEndpoint _usbEndpointOut;
//    private PendingIntent _permissionIntent;
//    private IntentFilter intentFilter;
//    private DeviceEventListener _deviceEventListener;
//    private AsyncTask _commTask;
//    private CallbackContext callbackContext;
//    private SerialService service;
//
//
//    private Serial me = this;
//
//    private boolean _bAccessAllowed;
//
//    @Override
//    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
//        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
//        this.callbackContext = callbackContext;
//        Log.d(TAG, "Action: " + action);
//        Log.d(TAG, "Args: " + args);
//        JSONObject arg_object = args.optJSONObject(0);
//        // request permission
//        if (ACTION_REQUEST_PERMISSION.equals(action)) {
//            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
//            requestPermissionForDevice(opts, callbackContext);
//            return true;
//        }
//        // open serial port
//        else if (ACTION_OPEN.equals(action)) {
//            JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
//            openConnection(opts, callbackContext);
//            return true;
//        }
//        // write to the serial port
//        else if (ACTION_WRITE.equals(action)) {
//            String data = arg_object.getString("command");
//            write(data, callbackContext);
//            return true;
//        }
////        // Register read callback
////        else if (ACTION_READ_CALLBACK.equals(action)) {
////            registerReadCallback(callbackContext);
////            return true;
////        }
//        else if (ACTION_CLOSE.equals(action)) {
//            closeConnection(callbackContext);
//            return true;
//        }
//        // the action doesn't exist
//        return false;
//    }
//
//
//
//    /**
//     * Android-USB-Security-Routine
//     */
//    private final BroadcastReceiver _usbBroadcastReceiver = new BroadcastReceiver() {
//        public void onReceive(Context context, Intent intent) {
//            String action = intent.getAction();
//            if (action == null) {
//                return;
//            }
//            switch (action) {
//                case ACTION_USB_PERMISSION:
//                    synchronized (this) {
//                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
//                        Log.e(TAG, "Device:" + device);
//                        Log.e(TAG, "" + intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
//
//                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
//                            if (device != null) {
//                                Log.e(TAG, "Device:" + device);
//                                _bAccessAllowed = true;
//                                Intent updateIntent = new Intent(Communicator.INTERFACE_READY);
//                                cordova.getActivity().sendBroadcast(updateIntent);
//                                callbackContext.success("Permission to connect to the device was accepted!");
//
////                                updateIntent = new Intent(Communicator.GET_SERIAL);
////                                cordova.getActivity().sendBroadcast(updateIntent);
//
//                            } else {
//                                _bAccessAllowed = false;
//                                Log.e(TAG, "BroadcastReceiver/onReceive: permission granted, but device was null.");
//                                callbackContext.success("Permission to connect to the device was accepted, but the device was null!");
//                            }
//                        } else {
//                            _bAccessAllowed = false;
//                            Log.e(TAG, "BroadcastReceiver/onReceive: permission denied for device " + device);
//                            callbackContext.success("Permission to connect to the device was denied!");
//                        }
//                    }
//                    break;
//                case Communicator.GET_SERIAL:
////                    CommunicationProtocol.getInstance().getSerial(me);
//                    break;
//            }
//        }
//    };
//
//
//    public static int calculateCRC(byte[] buf, int start, int length) {
//        int crc_value = 0xffff;
//        int polynomial = 0x1021;
//        for (int pos = start; pos < start + length; pos++) {
//            byte b = buf[pos];
//            for (int i = 0; i < 8; i++) {
//                boolean bit = ((b >> (7 - i) & 1) == 1);
//                boolean c15 = ((crc_value >> 15 & 1) == 1);
//                crc_value <<= 1;
//                if (c15 ^ bit) {
//                    crc_value ^= polynomial;
//                }
//            }
//        }
//        return crc_value & 0xffff;
//    }
//
//    private IntentFilter getIntentFilter() {
//        if (intentFilter == null) {
//            intentFilter = new IntentFilter();
//            intentFilter.addAction(ACTION_USB_PERMISSION);
////            intentFilter.addAction(Communicator.GET_SERIAL);
//        }
//        return intentFilter;
//    }
//
//    public Serial() {
////        _usbManager = (UsbManager) BiozoomApp.getContext().getSystemService(Context.USB_SERVICE);
////        _usbManager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
//        _usbDevice = null;
//        _bAccessAllowed = false;
//        _deviceEventListener = null;
//        _commTask = null;
//        _usbDeviceConnection = null;
//
////        _permissionIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
//
////        try {
////            cordova.getActivity().registerReceiver(_usbBroadcastReceiver, getIntentFilter());
////        } catch (Exception e) {
////            Log.e(TAG, "registerReceiver(_usbBroadcastReceiver) error: " + e.toString());
////        }
//    }
//
//    public static Serial getInstance() {
//        if (instance == null) {
//            instance = new Serial();
//        }
//        return instance;
//    }
////    private UsbDevice _usbDevice;
////    private UsbDeviceConnection _usbDeviceConnection;
////    private UsbEndpoint _usbEndpointIn;
////    private UsbEndpoint _usbEndpointOut;
////    private DeviceEventListener _deviceEventListener;
////    private AsyncTask _commTask;
////    private PendingIntent _permissionIntent;
////    private IntentFilter intentFilter;
////    private Protocol _protocol;
////    private long _totalMeasurementTime;
////
////    @Override
////    public void setProtocol(Protocol protocol) {
////        _protocol = protocol;
////    }
////
////    /**
////     * Constructs the singleton object of the low-level communication class.
////     */
////    private CommunicatorUSB() {
////        _usbManager = (UsbManager) BiozoomApp.getContext().getSystemService(Context.USB_SERVICE);
////        _usbDevice = null;
////        _bAccessAllowed = false;
////        _deviceEventListener = null;
////        _commTask = null;
////        _usbDeviceConnection = null;
////
////        _permissionIntent = PendingIntent.getBroadcast(BiozoomApp.getContext(), 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
////
////        try {
////            BiozoomApp.getContext().registerReceiver(_usbBroadcastReceiver, getIntentFilter());
////        } catch (Exception e) {
////            Log.e(TAG, "registerReceiver(_usbBroadcastReceiver) error: " + e.toString());
////        }
////    }
////
////    public static CommunicatorUSB getInstance() {
////        if (instance == null) {
////            instance = new CommunicatorUSB();
////        }
////        return instance;
////    }
////
////    /**
////     * Calculates the CRC-CCITT checksum of a portion of the given byte array.
////     *
////     * @param buf    A byte array.
////     * @param start  The byte to start with.
////     * @param length The length of the array portion.
////     * @return The CRC-CCITT check sum.
////     */
////    public static int calculateCRC(byte[] buf, int start, int length) {
////        int crc_value = 0xffff;
////        int polynomial = 0x1021;
////        for (int pos = start; pos < start + length; pos++) {
////            byte b = buf[pos];
////            for (int i = 0; i < 8; i++) {
////                boolean bit = ((b >> (7 - i) & 1) == 1);
////                boolean c15 = ((crc_value >> 15 & 1) == 1);
////                crc_value <<= 1;
////                if (c15 ^ bit) {
////                    crc_value ^= polynomial;
////                }
////            }
////        }
////        return crc_value & 0xffff;
////    }
////
////    private IntentFilter getIntentFilter() {
////        if (intentFilter == null) {
////            intentFilter = new IntentFilter();
////            intentFilter.addAction(ACTION_USB_PERMISSION);
////            intentFilter.addAction(Communicator.GET_SERIAL);
////        }
////        return intentFilter;
////    }
////
//    private void requestPermissionForDevice(final JSONObject opts, final CallbackContext callbackContext) {
//        cordova.getThreadPool().execute(new Runnable() {
//            public void run() {
//                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
//                PluginResult result = new PluginResult(PluginResult.Status.OK);
//                result.setKeepCallback(true);
////            callbackContext.sendPluginResult(result);
//                _usbDevice = null;
//                _usbDeviceConnection = null;
//                cordova.getActivity().
//
//                        registerReceiver(_usbBroadcastReceiver, getIntentFilter());
//                _permissionIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0, new
//
//                        Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
//                _usbManager = (UsbManager) cordova.getActivity().
//
//                        getSystemService(Context.USB_SERVICE);
//                cordova.getActivity().
//
//                        registerReceiver(_usbBroadcastReceiver, getIntentFilter());
//
//
//                int vid;
//                int pid;
//                if (opts.has("vid") && opts.has("pid")) {
//                    Object o_vid = opts.opt("vid"); //can be an integer Number or a hex String
//                    Object o_pid = opts.opt("pid"); //can be an integer Number or a hex String
//                    vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid, 16);
//                    pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid, 16);
//                    //            String driver = opts.has("driver") ? (String) opts.opt("driver") : "CdcAcmSerialDriver";
//                } else {
//                    vid = 0;
//                    pid = 0;
//                }
//
//                // get device list from USB manager
//                HashMap<String, UsbDevice> deviceList = _usbManager.getDeviceList();
//                for (
//                        UsbDevice device : deviceList.values()) {
//                    Log.e(TAG, "VID: " + device.getVendorId());
//                    Log.e(TAG, "PID: " + device.getProductId());
//                    Log.e(TAG, "___");
//                    // iterate over devices
//                    if (
//                            device.getVendorId() == vid
//                                    && device.getProductId() == pid
//                    ) {
//                        // get first matching device
//                        _usbDevice = device;
//                        break;
//                    }
//                }
//
//                if (_usbDevice == null) {
//                    // No matching USB device found
//                    Log.e(TAG, "No matching USB device found!");
//                    return;
//                } else {
//                        _usbManager.requestPermission(_usbDevice, _permissionIntent);
////                        callbackContext.sendPluginResult("ASBCASDDASDAS123");
//                        callbackContext.success("Permission requested");
////                        Thread.sleep(5000);
////                        openConnection(opts, callbackContext);
////                        Thread.sleep(2000);
////                        write("X\nget_status\n", callbackContext);
////                        Thread.sleep(2000);
////                        write("start_usb", callbackContext);
////                        Thread.sleep(3000);
////                        write("W", callbackContext);
////                        Thread.sleep(1000);
////                        write("W", callbackContext);
////                        Thread.sleep(1000);
////                        write("W", callbackContext);
////                        Thread.sleep(1000);
////                        write("W", callbackContext);
////                        Thread.sleep(1000);
////                        write("M", callbackContext);
//                        return;
//                        //
//                }
//            }
//        });
//    }
//
//    public void openConnection(final JSONObject opts, final CallbackContext callbackContext) {
//        cordova.getThreadPool().execute(new Runnable() {
//            public void run() {
////                callbackContext.sendPluginResult("ASBCASDDASDAS");
//                callbackContext.success("Connection opened");
////                PluginResult result = new PluginResult(PluginResult.Status.OK);
////                result.setKeepCallback(true);
////                callbackContext.sendPluginResult(result);
//                boolean bReturn = false;
//                _bAccessAllowed = true;
//
//                //logger..debug("openConnection start ");
//
//                Log.e(TAG, "openConnection: ." + _usbDevice);
//                try {
//                    if (_usbDevice != null) {
//                        try {
//                            _usbDeviceConnection = _usbManager.openDevice(_usbDevice);
//                        } catch (IllegalArgumentException e) {
//                            //                    showAlert("Kontakt zum Scanner verloren!");
//                            Log.e(TAG, "openConnection: could not open device.");
//                            _usbDeviceConnection = null;
//                            return;
//                        } catch (Exception e) {
//                            Log.e(TAG, "openConnection: could not open device here either.");
//                            return;
//                        }
//
//                        if (_bAccessAllowed) {
//
//                            //For CY7C65213 - Interface 1 has the Read-Write-Endpoints:
//                            UsbInterface usbInterface = _usbDevice.getInterface(1);
//
//                            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
//                                UsbEndpoint ep = usbInterface.getEndpoint(i);
//                                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
//                                    _usbEndpointIn = ep;
//                                } else {
//                                    _usbEndpointOut = ep;
//                                }
//                            }
//
//                            _usbDeviceConnection.claimInterface(usbInterface, true); //Force must be true
//
//                            bReturn = (_usbDeviceConnection != null && _usbEndpointIn != null && _usbEndpointOut != null);
//
//                            setParameters(); //Try to set Baudrate.....
//                        }
//                    }
//                } catch (
//                        NullPointerException e) {
//                    // Hier landet man, wenn die Verbindung zum Scanner fehlschlägt!
//                    // Dabei entsteht in einem anderen Thread eine IllegalArgumentException
//                    // Hier jedoch kommt nur eine NullPointerException an.
//                    Log.e(TAG, "openConnection Error: " + e.toString());
//                    _usbDeviceConnection = null;
//                    bReturn = false;
//                } catch (
//                        Exception e) {
//                    Log.e(TAG, "openConnection Unknown Error: " + e.toString());
//                    _usbDeviceConnection = null;
//                    bReturn = false;
//                }
//
//                isConnected();
//
//                return;
//            }
//        });
//    }
//
//
//
//    /**
//     * Close the USB connection.
//     */
//    public void closeConnection(final CallbackContext callbackContext) {
//        cordova.getThreadPool().execute(new Runnable() {
//            public void run() {
//                if (_usbDevice != null) {
//                    // USB device there
//                    if (_usbDeviceConnection != null) {
//                        // connection active
//                        _usbDeviceConnection.close();
//                        _usbDeviceConnection = null;
//                    }
//                }
//            }
//        });
//    }
//
//    private void write(String cmd, final CallbackContext callbackContext) {
//        cordova.getThreadPool().execute(new Runnable() {
//            public synchronized void run() {
//                Log.e(TAG, "Command: " + cmd);
//                //        byte[] _command = "m".getBytes(StandardCharsets.UTF_8);
//                byte[] _receiveData = {};
////                _receiveData = {};
////                Arrays.fill(_receiveData, null);
//                int TIMEOUT = 600;
//                byte[] _command = cmd.getBytes(StandardCharsets.UTF_8);
//                _bAccessAllowed = true;
//                String output_type = "";
//                switch (cmd) {
//                    case "M": {
//                        _receiveData = new byte[5773 * 33];
//                        output_type = "M";
//                        break;
//                    }
//                    case "W": {
//                        _receiveData = new byte[44];
//                        output_type = "W";
//                        break;
//                    }
//                    default: {
//                        _receiveData = new byte[1024];
//                        output_type = "";
//                    }
//                }
//
//                if (
//
//                        isConnected()) {
//                    int numBytesWritten = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, TIMEOUT);
//
//                    if (numBytesWritten <= 0) {
//                        Log.e(TAG, "HandPatternMatchingTaskUSB: USB write error!");
//                        //                return false;
//                        return;
//                    }
//
//                    if (cmd == "X\n") {
//                        return;
//                    }
//
//                    try {
//                        Thread.sleep(100);
//                    } catch (Exception e) {
//                    }
////                    byte[] tempData = new byte[1000];
//                    boolean bDataExists = true;
//                    int index = 0;
//                    int count;
//
////                    ByteBuffer buf = ByteBuffer.wrap();
//                    final UsbRequest request = new UsbRequest();
//                    request.initialize(_usbDeviceConnection, _usbEndpointIn);
//                    try {
//                        Thread.sleep(100);
//                    } catch (Exception e) {
//                    }
//                    final ByteBuffer buf = ByteBuffer.wrap(_receiveData);
//
//                    while (bDataExists) {
//
//                        boolean a = request.queue(buf);
//                        final UsbRequest b = _usbDeviceConnection.requestWait();
//                        final int nread = buf.position();
//                        if (nread == 0) {
//                            break;
//                        }
////                        byte[] tempData = buf.array();
//
////                        count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 1000);
//
////                        count = _usbRequest.queue(buffer, 64);
//                        int length = buf.position();
//                        Log.e(TAG, "" + a + " " + b + " " + length);
//                        //                String s = new String(tempData);
//                        //                Log.e(TAG, "" + Arrays.toString(tempData));
//
//                        if (length <= 0) {
//                            bDataExists = false;
//                        } else {
//                            if ((index + length) < _receiveData.length) {
////                                System.arraycopy(tempData, 0, _receiveData, index, length);
//                                String s;
//                                s = new String(_receiveData, StandardCharsets.UTF_8);
//                                Log.e(TAG, "" + s + " " + _receiveData.length);
//                                index += length;
//                            } else {
//                                bDataExists = false;
//                            }
//                        }
//                    }
//
//                    String s;
//                    switch (output_type) {
//                        case "W": {
//                            float fLow = ByteBuffer.wrap(Arrays.copyOfRange(_receiveData, 2, 6)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
//                            float fHigh = ByteBuffer.wrap(Arrays.copyOfRange(_receiveData, 10, 14)).order(ByteOrder.LITTLE_ENDIAN).getFloat();
//                            float[] values = {fLow, fHigh};
//                            s = Arrays.toString(values);
//                            break;
//                        }
//                        case "M": {
//                            byte[] encoded = Base64.getEncoder().encode(_receiveData);
//                            //                    println(new String(encoded));   // Outputs "SGVsbG8="
//                            s = new String(encoded);
//                            Log.e(TAG, "" + s.length());
//                            break;
//                        }
//                        default: {
//                            s = new String(_receiveData, StandardCharsets.UTF_8);
//                            break;
//                        }
//                    }
//                    Log.e(TAG, "" + s);
//                    //            callbackContext.success(s);
//                    //            return index > 0;
//                    callbackContext.success(s);
//                    return;
//                } else {
//                    //            return false;
//                    callbackContext.error("Task failed.");
//                    return;
//                }
//            }
//        });
//    }
//
//    private void setParameters() {
//        int stopBitsByte = 0;
//        int parityBitesByte = 0;
//        int baudRate = BAUDRATE;
//        byte[] msg = {
//                (byte) (baudRate & 0xff),
//                (byte) ((baudRate >> 8) & 0xff),
//                (byte) ((baudRate >> 16) & 0xff),
//                (byte) ((baudRate >> 24) & 0xff),
//                (byte) stopBitsByte,
//                (byte) parityBitesByte,
//                (byte) 8};
//
//        sendAcmControlMessage(0x20, 0, msg);
//    }
//
//    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
//    private int sendAcmControlMessage(int request, int value, byte[] buf) {
//        return _usbDeviceConnection.controlTransfer(
//                (0x01 << 5) | 0x01, request, value, 0, buf, buf != null ? buf.length : 0, 5000);
//    }
//
//    public Boolean isConnected() {
//        Log.e(TAG, "_bAccessAllowed: " + _bAccessAllowed);
//        Log.e(TAG, "_usbDeviceConnection: " + _usbDeviceConnection);
//        Log.e(TAG, "_usbEndpointIn: " + _usbEndpointIn);
//        Log.e(TAG, "_usbEndpointOut: " + _usbEndpointOut);
//        return (_bAccessAllowed &&
//                (_usbDeviceConnection != null) &&
//                (_usbEndpointIn != null) &&
//                (_usbEndpointOut != null));
//    }
//
//
////
////    /**
////     * Open the USB-Connection
////     *
////     * @return yes or no
////     */
////    public Boolean openConnection() {
////        boolean bReturn = false;
////
////        //logger..debug("openConnection start ");
////
////        try {
////            if (_usbDevice != null) {
////                try {
////                    _usbDeviceConnection = _usbManager.openDevice(_usbDevice);
////                } catch (IllegalArgumentException e) {
////                    showAlert("Kontakt zum Scanner verloren!");
////                    Log.e(TAG, "openConnection: could not open device.");
////                    _usbDeviceConnection = null;
////                    return false;
////                } catch (Exception e) {
////                    return false;
////                }
////
////                if (_bAccessAllowed) {
////
////                    //For CY7C65213 - Interface 1 has the Read-Write-Endpoints:
////                    UsbInterface usbInterface = _usbDevice.getInterface(1);
////
////                    for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
////                        UsbEndpoint ep = usbInterface.getEndpoint(i);
////                        if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
////                            _usbEndpointIn = ep;
////                        } else {
////                            _usbEndpointOut = ep;
////                        }
////                    }
////
////                    _usbDeviceConnection.claimInterface(usbInterface, true); //Force must be true
////
////                    bReturn = (_usbDeviceConnection != null && _usbEndpointIn != null && _usbEndpointOut != null);
////
////                    setParameters(); //Try to set Baudrate.....
////                }
////            }
////        } catch (NullPointerException e) {
////            // Hier landet man, wenn die Verbindung zum Scanner fehlschlägt!
////            // Dabei entsteht in einem anderen Thread eine IllegalArgumentException
////            // Hier jedoch kommt nur eine NullPointerException an.
////            Log.e(TAG, "openConnection Error: " + e.toString());
////            _usbDeviceConnection = null;
////            bReturn = false;
////        } catch (Exception e) {
////            Log.e(TAG, "openConnection Unknown Error: " + e.toString());
////            _usbDeviceConnection = null;
////            bReturn = false;
////        }
////
////        return bReturn;
////    }
////
////    /**
////     * Set Control-Parameters (Baud StoppBits..) for USB --- CDC/ACM-Driver
////     */
////    private void setParameters() {
////
////        int stopBitsByte = 0;
////        int parityBitesByte = 0;
////        int baudRate = BAUDRATE;
////        byte[] msg = {
////                (byte) (baudRate & 0xff),
////                (byte) ((baudRate >> 8) & 0xff),
////                (byte) ((baudRate >> 16) & 0xff),
////                (byte) ((baudRate >> 24) & 0xff),
////                (byte) stopBitsByte,
////                (byte) parityBitesByte,
////                (byte) 8};
////
////        sendAcmControlMessage(0x20, 0, msg);
////    }
////
////    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
////    private int sendAcmControlMessage(int request, int value, byte[] buf) {
////        return _usbDeviceConnection.controlTransfer(
////                (0x01 << 5) | 0x01, request, value, 0, buf, buf != null ? buf.length : 0, 5000);
////    }
////
////    /**
////     * Test for connection with the scanner device.
////     *
////     * @return True, if the device is connected.
////     */
////    public Boolean isConnected() {
////        return (_bAccessAllowed &&
////                (_usbDeviceConnection != null) &&
////                (_usbEndpointIn != null) &&
////                (_usbEndpointOut != null));
////    }
////
////    //--------------------------------------------------------------------------------------------
////    //CheckSensorFunction
////
////    public Boolean checkSensorFunction(byte[] command, CheckSensorFunctionListener e, int timeout) {
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            return false;
////        }
////
////        if (isConnected()) {
////            _deviceEventListener = e;
////
////            _commTask = new CheckSensorFunctionTaskUSB(command, timeout);
////            ((CheckSensorFunctionTaskUSB) _commTask).execute();
////
////            return true;
////        } else {
////            return false;
////        }
////    }
////
////    private void onCheckSensorFunctionUSB(Boolean bOK, byte[] result) {
////        ((CheckSensorFunctionListener) _deviceEventListener).onCheckSensorFunctionListener(bOK, result);
////    }
////
////    public boolean permissionGranted() {
////        return _bAccessAllowed;
////    }
////
////    //--------------------------------------------------------------------------------------------
////
////    //------------------------------------------------------------------------------------------------
////
////    @Override
////    public void onDestroy() {
////        try {
////            BiozoomApp.getContext().unregisterReceiver(_usbBroadcastReceiver);
////        } catch (IllegalArgumentException ignored) {
////        }
////    }
////
////    @Override
////    public int getNumberOfHRVPackets() {
////        return -1;
////    }
////
////    /**
////     * The measurement of the "health-value"
////     *
////     * @param command = M
////     * @param e       The Listener for the results
////     * @return True, if device is connected and the command has been started.
////     */
////
////    public Boolean startMeasureDerma(byte[] command, MeasureDermaListener e, int timeout) {
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            return false;
////        }
////
////        if (isConnected()) {
////            _deviceEventListener = e;
////            _commTask = new MeasureDermaTaskUSB(command, timeout);
////            ((MeasureDermaTaskUSB) _commTask).executeOnExecutor(MeasureDermaTaskUSB.THREAD_POOL_EXECUTOR);
////
////            return true;
////        } else {
////            return false;
////        }
////    }
////
////    /**
////     * Calls the listener routine
////     *
////     * @param bOk          Communication result.
////     * @param data         Read bytes.
////     * @param numReadBytes Length of read bytes.
////     */
////    private void onStartMeasureDerma(Boolean bOk, byte[] data, int numReadBytes) {
////        MeasureDermaListener e = (MeasureDermaListener) _deviceEventListener;
////
////        if (bOk) {
////            Log.d(TAG, "onStartMeasureDerma read: " + numReadBytes);
////            byte[] dataBlock = new byte[numReadBytes];
////            System.arraycopy(data, 0, dataBlock, 0, numReadBytes);
////
////            e.onMeasureDermaListener(true, dataBlock, numReadBytes);
////        } else {
////            e.onMeasureDermaListener(false, null, 0);
////        }
////    }
////
////    //--------------------------------------------------------------------------------------------
////
////    /**
////     * Get the serial-number and the license of the device
////     *
////     * @param command = I
////     * @param e       The listener
////     * @return True, if the device is connected and the command has been started.
////     */
////    public Boolean getSerial(byte[] command, GetSerialListener e) {
////        // SEND License COMMAND TO  THE USB DEVICE;
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            return false;
////        }
////
////        _deviceEventListener = e;
////
////        _commTask = new GetSerialTaskUSB(command).execute();
////
////        return true;
////    }
////
////    private void onGetSerialUSB(byte[] result) {
////
////        StringBuilder sSerialNumber = new StringBuilder();
////        int license = 0;
////        short firmwareVersion = 0;
////        byte[] identBlock = null;
////
////        if (result != null) {
////            identBlock = result;
////            int serialCRC = calculateCRC(result, 2, 13);
////
////            if (serialCRC == 0) {
////                ByteBuffer bf = ByteBuffer.wrap(result);
////
////                // Length is coded Big Endian.
////                bf.order(ByteOrder.BIG_ENDIAN);
////                bf.getShort();  // Discard length bytes (2)
////
////                bf.order(ByteOrder.LITTLE_ENDIAN);
////                for (int i = 0; i < 6; i++) {
////                    sSerialNumber.append((char) (bf.get()));  //Erste 6 Bytes als Character
////                }
////                for (int i = 6; i < 13; i++) {
////                    sSerialNumber.append(String.format("%02X", bf.get())); //Restliche 7 Bytes als Hex-Zahlen
////                }
////
////                bf.get();   // 1 Byte allowed users -> discard
////
////                for (int i = 0; i < 6; ++i) {
////                    bf.get(); //MAC-Adresse
////                }
////
////                firmwareVersion = bf.getShort();
////
////                license = (bf.get() & 0xFF); //=laufnumnmer...
////            }
////        }
////
////        ((GetSerialListener) _deviceEventListener).onGetSerialNumber(sSerialNumber.toString(), license, firmwareVersion, identBlock);
////    }
////
////    @SuppressLint({"CommitPrefEdits", "ApplySharedPref"})
////    @Override
////    public void onGetSerialNumber(String serialNumber, int license, short firmwareVersion, byte[] identBlock) {
////        Log.d(TAG, "onGetSerialNumber " + serialNumber.length() + ", " + serialNumber);
////        serialNumber = serialNumber.trim();
////        if (serialNumber.length() == 20 /*&& license > 0*/) { //Alles ok
////            SharedPreferences pSerial = BiozoomApp.getContext().getSharedPreferences(HT_Configuration.PREFS_SYSTEM_INFORMATION, 0);
////            SharedPreferences.Editor editor = pSerial.edit();
////            editor.putString(HT_Configuration.PREF_SERIALNUMBER, serialNumber);
////            editor.putInt(HT_Configuration.PREF_FIRMWARE_VERSION, firmwareVersion);
////            editor.putInt(HT_Configuration.PREF_LICENCE, license);
////            editor.commit();    /// Immediate write to the shared preferences.
////            HT_Configuration.serial = serialNumber;
////            HT_Configuration.getInstance().setGlobalVariable(HT_Configuration.GLOBAL_SERIAL_NUMBER, serialNumber);
////            HT_Configuration.getInstance().setGlobalVariable(HT_Configuration.GLOBAL_FIRMWARE_VERSION, String.valueOf(firmwareVersion));
////            HT_Configuration.getInstance().setGlobalVariable(HT_Configuration.GLOBAL_APP_VERSION, BuildConfig.VERSION_NAME);
////        } else {
////            // Serial number has wrong size. Notify the rest.
////            Log.d(TAG, "onGetSerialNumberLocal: called with wrong-sized serial number \"" + serialNumber + "\".");
////            HT_Configuration.MISSING_DEVICE = true;
////        }
////
////        //save identBlock into config
////        if (identBlock != null) {
////            HT_Configuration.identBlock = identBlock;
////        }
////
////        Intent updateIntent = new Intent(GetSerialListener.GOT_SERIAL_NUMBER);
////        updateIntent.putExtra("serialNumber", serialNumber);
////        updateIntent.putExtra("license", license);
////        updateIntent.putExtra("firmwareVersion", firmwareVersion);
////        updateIntent.putExtra("identBlock", identBlock);
////        BiozoomApp.getContext().sendBroadcast(updateIntent);
////    }
////
////    /**
////     * The measurement of the "health-value"
////     *
////     * @param command = M
////     * @param e       The Listener for the results
////     * @return True, if device is connected and the command has been started.
////     */
////
////    public Boolean startMeasureAox(byte[] command, MeasureAoxListener e) {
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            return false;
////        }
////
////        if (isConnected()) {
////            _deviceEventListener = e;
////            _commTask = new MeasureAoxTaskUSB(command);
////            ((MeasureAoxTaskUSB) _commTask).execute();
////
////            return true;
////        } else {
////            return false;
////        }
////    }
////
//////------------------------------------------------------------------------------------------------
////
////    /**
////     * Calls the listener routine
////     *
////     * @param bOk          Communication result.
////     * @param data         Read bytes.
////     * @param numReadBytes Length of read bytes.
////     */
////    private void onStartMeasureAox(Boolean bOk, byte[] data, int numReadBytes) {
////        MeasureAoxListener e = (MeasureAoxListener) _deviceEventListener;
////
////        if (bOk) {
////            Log.d(TAG, "onStartMeasureAox read: " + numReadBytes);
////            e.onMeasureAoxListener(true, data, data.length);
////        } else {
////            e.onMeasureAoxListener(false, null, 0);
////        }
////    }
////
////
////    /**
////     * The measurement of the "health-value"
////     *
////     * @param command = M
////     * @param e       The Listener for the results
////     * @return True, if device is connected and the command has been started.
////     */
////
////    public Boolean startMeasureHrv(byte[] command, MeasureHrvListener e, int timeout, int duration) {
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            return false;
////        }
////
////        if (isConnected()) {
////            _deviceEventListener = e;
////            _commTask = new MeasureHrvTaskUSB(command, timeout, duration);
////            ((MeasureHrvTaskUSB) _commTask).execute();
////
////            return true;
////        } else {
////            return false;
////        }
////    }
////
////    /**
////     * Calls the listener routine
////     *
////     * @param bOk          Communication result.
////     * @param data         Read bytes.
////     * @param numReadBytes Length of read bytes.
////     */
////    @SuppressWarnings("SameParameterValue")
////    private void onStartMeasureHrv(Boolean bOk, byte[] data, int numReadBytes) {
////        MeasureHrvListener e = (MeasureHrvListener) _deviceEventListener;
////
////        if (bOk) {
////            Log.d(TAG, "onStartMeasureHrv read: " + numReadBytes);
////            e.onMeasureHrvListener(true, data, data.length);
////        } else {
////            e.onMeasureHrvListener(false, null, 0);
////        }
////    }
////
////    /**
////     * Calls the listener routine
////     *
////     * @param bOk          Communication result.
////     * @param data         Read bytes.
////     * @param numReadBytes Length of read bytes.
////     */
////    @SuppressWarnings("SameParameterValue")
////    private void onStartMeasureHrv(Boolean bOk, byte[] data, int numReadBytes, long totalMeasurementTime) {
////        MeasureHrvListener e = (MeasureHrvListener) _deviceEventListener;
////
////        if (bOk) {
////            Log.d(TAG, "onStartMeasureHrv read: " + numReadBytes);
////            e.onMeasureHrvListener(true, data, data.length, totalMeasurementTime);
////        } else {
////            e.onMeasureHrvListener(false, null, 0, 0);
////        }
////    }
////
////    /**
////     * Positioning of the hand over the device.
////     *
////     * @param command   the command to be sent to the device.
////     * @param eListener the listener for the hand pattern matching
////     * @return True, if the device is connected and the command has been started.
////     */
////    public Boolean startHandPatternMatching(byte[] command, HandPatternMatchingListener eListener, float[] limits) {
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            // Another task is running at the moment.
////            return false;
////        }
////
////        if (isConnected()) {
////            _deviceEventListener = eListener;
////
////            _commTask = new HandPatternMatchingTaskUSB(command, limits);
////            ((HandPatternMatchingTaskUSB) _commTask).executeOnExecutor(HandPatternMatchingTaskUSB.THREAD_POOL_EXECUTOR);
////
////            return true;
////        } else {
////            return false;
////        }
////    }
////
////    private void onStartHandPatternMatchingUSB(Boolean bOk, byte[] result) {
////
////        final int NumberOfFloats = 6;
////        float[] values = null;
////        byte[] handrecognition_block = null;
////        HandPatternMatchingListener e = (HandPatternMatchingListener) _deviceEventListener;
////
////        if (bOk && result != null) {
////            ByteBuffer bf = ByteBuffer.wrap(result);
////            bf.order(ByteOrder.BIG_ENDIAN);
////            int length = bf.getShort();
////            handrecognition_block = new byte[length + 2];
////            System.arraycopy(result, 0, handrecognition_block, 0, result.length <= length + 2 ? result.length : length + 2);
////            bf.order(ByteOrder.LITTLE_ENDIAN);
////            if (length >= NumberOfFloats * 4) { //there must(!) be 6 floats with 4 bytes per float
////                values = new float[NumberOfFloats];
////                int n = 0;
////                while (n < NumberOfFloats) {
////                    values[n] = bf.getFloat();
////                    Log.d(TAG, "onStartHandPatternMatchingUSB: float[" + n + "] = " + values[n]);
////                    ++n;
////                }
////            }
////        }
////
////        e.onHandPatternMatching(bOk, values, handrecognition_block);
////    }
////
//////--------------------------------------------------------------------------------------------
////
////    /**
////     * Transfer license and update to the device
////     *
////     * @param commandMap = U
////     * @param updateFile A file handle to the update file.
////     * @param listener   the listener for the success
////     * @return True, if the device is connected and the command has been sent.
////     */
////    public Boolean transferUpdate(HashMap<String, byte[]> commandMap, File updateFile, TransferUpdateListener listener) {
////        if (_commTask != null && _commTask.getStatus() == AsyncTask.Status.RUNNING) {
////            if (_commTask instanceof TransferUpdateTaskUSB) {
////                Log.d(TAG, "transferUpdate: UpdateTask still running.");
////            } else {
////                Log.e(TAG, "transferUpdate: Another communication task is currently active: " + _commTask.getClass().toString());
////                listener.onTransferUpdate(false);
////            }
////            return false;
////        }
////
////        this.openConnection();
////        if (isConnected()) {
////            _deviceEventListener = listener;
////            _commTask = new TransferUpdateTaskUSB(commandMap, updateFile).execute();
////
////            return true;
////        } else {
////            listener.onError();
////            Log.e(TAG, "transferUpdate: scanner device not connected.");
////            return false;
////        }
////    }
////
////    private void onTransferUpdateUSB(Boolean bOk) {
////        ((TransferUpdateListener) _deviceEventListener).onTransferUpdate(bOk);
////    }
////
////    //For Debugging:
////    @SuppressWarnings("SameParameterValue")
////    private void showAlert(String s) {
////        AlertDialog alert = new AlertDialog.Builder(BiozoomApp.getContext()).setMessage(s).create();
////        alert.show();
////    }
////
//////--------------------------------------------------------------------------------------------
////
////
////    @SuppressLint("StaticFieldLeak")
////    private class CheckSensorFunctionTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        long _timeout;
////        byte[] _command;
////        byte[] _receiveData;
////
////        CheckSensorFunctionTaskUSB(byte[] command, int timeout) {
////            _command = command;
////            _timeout = timeout * 1000;
////            _receiveData = new byte[1];
////        }
////
////        protected Boolean doInBackground(Void... Void) {
////            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////
////            if (isConnected()) {
////                byte[] tempData = new byte[10];
////                int count;
////
////                // flush buffer before sending command
////                _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 100);
////                tempData[0] = 0;
////                //send command
////                int numBytesWrite = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, 2000);
////
////                long start_time = System.currentTimeMillis();
////
////                _receiveData[0] = 0;
////
////                while (true) {
////                    //retrieve result
////                    count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 100);
////                    if (count > 0) {
////                        _receiveData[0] = tempData[0];
////                        break;
////                    }
////
////                    //check timeout
////                    long diff = System.currentTimeMillis() - start_time;
////                    if (diff > _timeout) {
////                        _usbDeviceConnection.bulkTransfer(_usbEndpointOut, "X".getBytes(Charset.forName("UTF-8")), 1, 2000);
////                        try {
////                            Thread.sleep(100);
////                        } catch (InterruptedException e) {
////                            e.printStackTrace();
////                        }
////                        break;
////                    }
////                }
////                return numBytesWrite > 0;    // Return true, if the command could be sent sucessfully.
////            } else {
////                return false;
////            }
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            if (!bOK) {
////                _receiveData[0] = 0;
////            }
////            onCheckSensorFunctionUSB(bOK, _receiveData);
////        }
////    }
////
////
////    /**
////     * The AsyncTask for measuring
////     */
////    @SuppressLint("StaticFieldLeak")
////    private class MeasureDermaTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        byte[] _command;
////        byte[] _receiveData;
////        int _numBytesRead;
////        int _timeout;
////
////        MeasureDermaTaskUSB(byte[] command, int timeout) {
////            _command = command;
////            _numBytesRead = 0;
////            _timeout = timeout * 1000;
////            _receiveData = new byte[36864]; // 18 Blocks with 2048 Bytes (bit too large)
////        }
////
////        //Here only one(!) List of URLs:
////        protected Boolean doInBackground(Void... Void) {
////            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////            if (isConnected()) {
////                long start_time = System.currentTimeMillis();
////                int count;
////                byte[] tempData = new byte[256];
////                _numBytesRead = 0;
////
////                //send command to device
////                int numBytesWrite = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, TIMEOUT);
////
////                //receive data -> first time until timeout
////                count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, _timeout);
////                if (count > 0) {
////                    System.arraycopy(tempData, 0, _receiveData, _numBytesRead, count);
////                } else {
////                    Log.d(TAG, "MeasureDermaTaskUSB: Timeout first read.");
////                }
////                _numBytesRead += count;
////
////                while (true) {
////                    count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 200);
////
////                    //data available
////                    if (count > 0) {
////                        System.arraycopy(tempData, 0, _receiveData, _numBytesRead, count);
////                        _numBytesRead += count;
////                    } else {
////                        return (numBytesWrite > 0 && _numBytesRead > 0);
////                    }
////
////                    //timeout reached?
////                    long diff = System.currentTimeMillis() - start_time;
////                    if (diff > _timeout) {
////                        _usbDeviceConnection.bulkTransfer(_usbEndpointOut, "X".getBytes(Charset.forName("UTF-8")), _command.length, 2000);
////                        try {
////                            Thread.sleep(100);
////                        } catch (InterruptedException e) {
////                            e.printStackTrace();
////                        }
////                        break;
////                    }
////                }
////            }
////
////            return false;
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            if (bOK) {
////                onStartMeasureDerma(true, _receiveData, _numBytesRead);
////            } else {
////                onStartMeasureDerma(false, null, 0);
////                Log.e(TAG, "MeasureDermaTaskUSB " + "Nothing received");
////            }
////        }
////    }
////
////
////    /**
////     * Tries to get the serial number of the scanner until success!
////     */
////    @SuppressLint("StaticFieldLeak")
////    private class GetSerialTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        byte[] _command;
////        byte[] _receiveData;
////        CommunicationProtocol _commProtocol;
////        int _numBytesRead;
////
////        GetSerialTaskUSB(byte[] command) {
////            _command = command;
////            _receiveData = new byte[32]; //normally 28 would be enough...
////            _commProtocol = CommunicationProtocol.getInstance();
////            _numBytesRead = 0;
////        }
////
////        protected Boolean doInBackground(Void... Void) {
////
////            /// Wait for permission
////            while (!_commProtocol.permissionGranted()) {
////                try {
////                    Thread.sleep(250);
////                } catch (InterruptedException e) {
////                    e.printStackTrace();
////                }
////            }
////            if (_usbDeviceConnection == null || _usbEndpointIn == null || _usbEndpointOut == null) {
////                Log.e(TAG, "GetSerialTaskUSB: doInBackground: _usbDeviceConnection == null or _usbEndpoint... == null");
////            }
////            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////
////            if (openConnection()) {
////                byte[] tempData = new byte[32];
////                int count;
////                int numBytesWrite;
////
////                do {
////                    // Flush buffer before writing command.
////                    _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 100);
////                    numBytesWrite = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, TIMEOUT);
////
////                    while (true) {
////
////                        count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, TIMEOUT);
////                        //data available
////                        if (count != -1) {
////                            try {
////                                System.arraycopy(tempData, 0, _receiveData, _numBytesRead, count);
////                            } catch (ArrayIndexOutOfBoundsException e) {
////                                _numBytesRead = 0;
////                                break;
////                            }
////                            _numBytesRead += count;
////                            if (count >= 28) {
////                                break;
////                            }
////                        } else {
////                            break;
////                        }
////                    }
////                } while (calculateCRC(_receiveData, 2, _numBytesRead - 2) != 0);
////                return (numBytesWrite > 0 && _numBytesRead > 0);
////            } else {
////                return false;
////            }
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            if (bOK) {
////                onGetSerialUSB(_receiveData);
////            } else {
////                HT_Configuration.MISSING_DEVICE = true;
////                onGetSerialUSB(null);
////            }
////        }
////    }
////
//////------------------------------------------------------------------------------------
//////------------------------------------------------------------------------------------
////
////
////    /**
////     * The AsyncTask for measuring
////     */
////    @SuppressLint("StaticFieldLeak")
////    private class MeasureHrvTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        int _timeout;
////        int _duration;
////        byte[] _command;
////        ByteArrayOutputStream _byteArrayOutputStream;
////        boolean _maySendAbort = true;
////
////        MeasureHrvTaskUSB(byte[] command, int timeout, int duration) {
////            _command = command;
////            _timeout = timeout * 1000;
////            _duration = duration * 1000;
////
////            _byteArrayOutputStream = new ByteArrayOutputStream(
////                    _protocol == Protocol.CLASSIC ?
////                            13400 * duration :
////                            33 * (550 * duration));
////
////        }
////
////        /**
////         * Transfers the data from the scanner.
////         *
////         * @return True, if data could be read.
////         */
////        protected Boolean doInBackground(Void... Void) {
////            int threadPriority = Process.getThreadPriority(0);
////            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
////            try {
////                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////                if (isConnected()) {
////                    int count;
////                    byte[] tempData = new byte[64];
////                    int numBytesWritten = 0;
////
////                    for (byte a_command : _command) {
////                        tempData[0] = a_command;
////                        // send byte to device
////                        numBytesWritten = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, tempData, 1, TIMEOUT);
////
////                        // after each byte, sleep for 10ms
////                        try {
////                            Thread.sleep(10);
////                        } catch (InterruptedException e) {
////                            e.printStackTrace();
////                        }
////                    }
////
////                    long start_time = System.currentTimeMillis() - 10;
////
////                    // receive data -> first time until timeout
////                    count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, _timeout);
////                    if (count > 0) {
////                        _byteArrayOutputStream.write(tempData, 0, count);
////                    } else {
////                        Log.d(TAG, "MeasureHrvTaskUSB: Timeout first read.");
////                    }
////
////                    while (true) {
////                        count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 200);
////
////                        // data available
////                        if (count > 0) {
////                            _byteArrayOutputStream.write(tempData, 0, count);
////                        } else {
////                            _totalMeasurementTime = System.currentTimeMillis() - start_time - 200;
////                            return (numBytesWritten > 0 && _byteArrayOutputStream.size() > 0);
////                        }
////
////                        // timeout reached?
////                        long diff = System.currentTimeMillis() - start_time;
////
////                        if (diff > _duration) {
////                            if (_maySendAbort) {
////                                _usbDeviceConnection.bulkTransfer(_usbEndpointOut, "X".getBytes(Charset.forName("UTF-8")), 1, 2000);
////                                try {
////                                    Thread.sleep(10);
////                                } catch (InterruptedException e) {
////                                    e.printStackTrace();
////                                }
////                                _maySendAbort = false;
////                            } else if (diff > _timeout) {
////                                return (numBytesWritten > 0 && _byteArrayOutputStream.size() > 0);
////                            }
////                        }
////
////                    }
////                }
////
////                return false;
////            } finally {
////                android.os.Process.setThreadPriority(threadPriority);
////            }
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            if (bOK) {
////                onStartMeasureHrv(true, _byteArrayOutputStream.toByteArray(), _byteArrayOutputStream.size(), _totalMeasurementTime);
////            } else {
////                onStartMeasureHrv(false, null, 0);
////                Log.e(TAG, "MeasureHrvTaskUSB " + "Nothing received");
////            }
////        }
////    }
////
////
////    /**
////     * The AsyncTask for measuring
////     */
////    @SuppressLint("StaticFieldLeak")
////    private class MeasureAoxTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        byte[] _command;
////        ByteArrayOutputStream _byteArrayOutputStream;
////
////        MeasureAoxTaskUSB(byte[] command) {
////            _command = command;
////            _byteArrayOutputStream = new ByteArrayOutputStream(MAX_MEASUREMENT_BYTES);
////        }
////
////        /**
////         * Transfers the data from the scanner.
////         *
////         * @return True, if data could be read.
////         */
////        protected Boolean doInBackground(Void... Void) {
////            int threadPriority = Process.getThreadPriority(0);
////            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
////            try {
////                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////                if (isConnected()) {
////                    int count;
////                    byte[] tempData = new byte[64];
////
////                    //send command to device
////                    int numBytesWrite = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, TIMEOUT);
////
////                    //receive data -> first time until timeout
////                    count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 6000);
////                    if (count > 0) {
////                        _byteArrayOutputStream.write(tempData, 0, count);
////                    } else {
////                        Log.d(TAG, "MeasureAoxTaskUSB: Timeout first read.");
////                    }
////
////                    while (true) {
////                        count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 1000);
////
////                        //data available
////                        if (count > 0) {
////                            _byteArrayOutputStream.write(tempData, 0, count);
////                        } else {
////                            return (numBytesWrite > 0 && _byteArrayOutputStream.size() > 0);
////                        }
////                    }
////                }
////
////                return false;
////            } finally {
////                android.os.Process.setThreadPriority(threadPriority);
////            }
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            if (bOK) {
////                onStartMeasureAox(true, _byteArrayOutputStream.toByteArray(), _byteArrayOutputStream.size());
////            } else {
////                onStartMeasureAox(false, null, 0);
////                Log.e(TAG, "MeasureAoxTaskUSB " + "Nothing received");
////            }
////        }
////    }
////
////
////    /**
////     * The AsyncTask-class for the hand positioning
////     */
////    @SuppressLint("StaticFieldLeak")
////    private class HandPatternMatchingTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        byte[] _command;
////        byte[] _receiveData;
////        float[] _limits;
////
////        HandPatternMatchingTaskUSB(byte[] command, float[] limits) {
////            _command = command;
////            _receiveData = new byte[256]; //normally 44 would be enough...
////            _limits = limits; //Achtung nur Referenz!
////        }
////
////        protected Boolean doInBackground(Void... Void) {
////            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////            return measureHandPattern();
////        }
////
////        private Boolean measureHandPattern() {
////
////            if (isConnected()) {
////                int numBytesWritten = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, TIMEOUT);
////
////                if (numBytesWritten <= 0) {
////                    Log.e(TAG, "HandPatternMatchingTaskUSB: USB write error!");
////                    return false;
////                }
////
////                byte[] tempData = new byte[64];
////                boolean bDataExists = true;
////                int index = 0;
////                int count;
////
////                while (bDataExists) {
////
////                    count = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, tempData, tempData.length, 1000);
////
////                    if (count <= 0) {
////                        bDataExists = false;
////                    } else {
////                        if ((index + count) < _receiveData.length) {
////                            System.arraycopy(tempData, 0, _receiveData, index, count);
////                            index += count;
////                        } else {
////                            bDataExists = false;
////                        }
////                    }
////                }
////
////                return index > 0;
////            } else {
////                return false;
////            }
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            if (bOK) {
////                onStartHandPatternMatchingUSB(true, _receiveData);
////            } else {
////                onStartHandPatternMatchingUSB(false, null);
////            }
////        }
////    }
////
////
////    /**
////     * The AsyncTask-class to firmware-update the device
////     */
////    @SuppressLint("StaticFieldLeak")
////    @SuppressWarnings("TryFinallyCanBeTryWithResources")
////    private class TransferUpdateTaskUSB extends AsyncTask<Void, Void, Boolean> {
////
////        File _updateFile;
////        byte[] _receiveData;
////        byte[] _command;
////        byte[] _commandAnswer;
////        byte[] _ack;
////        private String TAG = "TransferUpdateTaskUSB";
////        byte[] _tmp_data;
////
////        TransferUpdateTaskUSB(HashMap<String, byte[]> commandMap, File updateFile) {
////            _command = commandMap.get("transferUpdate");
////            _commandAnswer = commandMap.get("transferUpdateAnswer");
////            _ack = commandMap.get("Ack");
////            _receiveData = new byte[1];
////            _updateFile = updateFile;
////            _tmp_data = new byte[16];
////        }
////
////        private Boolean sendUpdate(int counter) {
////            if (counter > 3) {
////                return sendUpdate(3);
////            } else if (counter < 0) {
////                return false;
////            }
////
////            int numBytesWritten;
////            int numBytesRead;
////
////            while (_usbDeviceConnection.bulkTransfer(_usbEndpointIn, _tmp_data, _tmp_data.length, TIMEOUT) > 0) {
////                Log.d(TAG, "sendUpdate: flushing.");
////            }
////
////            numBytesWritten = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, _command, _command.length, TIMEOUT);
////            numBytesRead = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, _receiveData, _receiveData.length, TIMEOUT);
////
////            if (numBytesRead < 1 || _receiveData[0] != _commandAnswer[0] || numBytesWritten < 0) { //There must be a B!
////                Log.e(TAG, "sendUpdate: unexpected answer from scanner: \"" + _receiveData[0] + "\" or write command failed: " + numBytesWritten + " Bytes written.");
////                return sendUpdate(counter - 1);
////            }
////
////            try {
////                Thread.sleep(1000); // Wait 1 second
////            } catch (InterruptedException e) {
////                Log.e(TAG, "sendUpdate: wait 1 second was interrupted.");
////            }
////
////            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////            InputStream in = null;
////            byte[] ack = new byte[1];
////            byte[] data = new byte[1024];   /// 1024 == size of receive buffer
////            try {
////                try {
////                    in = new BufferedInputStream(new FileInputStream(_updateFile));
////
////                    // Loop over all blocks in update file
////                    while (true) {
////                        int readBytes = in.read(data, 0, 2);
////                        if (readBytes < 0) {
////                            // No more data in file
////                            break;
////                        }
////
////                        int length = (data[0] << 8) + data[1];
////
////                        if (in.read(data, 2, length) != length) {
////                            Log.e(TAG, "sendUpdate: error while reading file.");
////                            return sendUpdate(counter - 1);
////                        }
////
////                        int bytesToWrite = length;
////                        do {
////                            /// Transfer data in blocks
////                            numBytesWritten = _usbDeviceConnection.bulkTransfer(_usbEndpointOut, data, length + 2, 1000);
////                            bytesToWrite -= numBytesWritten;
////                        } while (bytesToWrite > 0 && numBytesWritten >= 0);
////
////                        if (numBytesWritten < 0) {
////                            Log.d(TAG, "sendUpdate: error during transfer to scanner. Restarting.");
////                            return sendUpdate(counter - 1);
////                        }
////
////                        ack[0] = 0;
////
////                        numBytesRead = _usbDeviceConnection.bulkTransfer(_usbEndpointIn, ack, ack.length, 3000);//3Sek
////
////                        if (numBytesRead == 1 && ack[0] == _ack[0]) {
////                            Log.d(TAG, "sendUpdate: packet successfully written. Length: " + length);
////                        } else {
////                            Log.d(TAG, "sendUpdate: error during transfer to scanner. No ACK received. Restarting.");
////                            return sendUpdate(counter - 1);
////                        }
////
////                    }
////                } finally {
////                    if (in != null) {
////                        in.close();
////                    }
////                }
////            } catch (FileNotFoundException e) {
////                Log.e(TAG, "sendUpdate: could not open \"" + _updateFile.getName() + "\" for reading.", e);
////                return sendUpdate(counter - 1);
////            } catch (IOException e) {
////                Log.e(TAG, "sendUpdate: could read from or close \"" + _updateFile.getName() + "\".", e);
////                return sendUpdate(counter - 1);
////            }
////
////            return true;
////        }
////
////        protected Boolean doInBackground(Void... Void) {
////            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
////            if (!isConnected()) {
////                return false;
////            }
////            return sendUpdate(3);
////        }
////
////        protected void onPostExecute(Boolean bOK) {
////            Log.i(TAG, "onPostExecute: " + bOK);
////            onTransferUpdateUSB(bOK);
////        }
////
////    }
//}
//
//
