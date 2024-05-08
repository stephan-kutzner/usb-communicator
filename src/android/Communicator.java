package com.biozoom.serial;

import java.io.File;
import java.util.HashMap;

/**
 * \author  Gareth Schmutz
 * \date    27.09.2015.
 */
public interface Communicator {

    String INTERFACE_READY = "com.biozoom.healthtracker.communicationComponent.Communicator.INTERFACE_READY";
    String INTERFACE_DISCONNECT = "com.biozoom.healthtracker.communicationComponent.Communicator.INTERFACE_DISCONNECT";
    String GET_SERIAL = "com.biozoom.healthtracker.communicationComponent.Communicator.GET_SERIAL";
//    enum Protocol{CLASSIC, STICK};
//
//    void setProtocol(Protocol protocol);
//
//    boolean requestPermissionForDevice();
//
//    void closeConnection();
//
//    Boolean openConnection();
//
//    Boolean isConnected();
//
//    Boolean getSerial(byte[] getSerials, GetSerialListener listener);
//
//    Boolean transferUpdate(HashMap<String, byte[]> commandMap, File updateFile, TransferUpdateListener listener);
//
//    Boolean checkSensorFunction(byte[] bCommand, CheckSensorFunctionListener listener, int timeout);
//
//    Boolean startHandPatternMatching(byte[] startHandPatternMatchings, HandPatternMatchingListener listener, float[] limits);
//
//    Boolean startMeasureAox(byte[] startMeasureAoxes, MeasureAoxListener measureListener);
//
//    Boolean startMeasureDerma(byte[] startMeasureDermas, MeasureDermaListener measureListener, int timeout);
//
//    Boolean startMeasureHrv(byte[] startMeasureHrvs, MeasureHrvListener measureListener, int timeout, int duration);
//
//    boolean permissionGranted();
//
//    void onDestroy();
//
//    int getNumberOfHRVPackets();
}
