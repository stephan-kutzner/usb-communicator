package com.biozoom.serial;

class Constants {

    // values have to be globally unique
    static final String INTENT_ACTION_GRANT_USB = "GRANT_USB";
    static final String INTENT_ACTION_DISCONNECT = "Disconnect";
    static final String NOTIFICATION_CHANNEL = "Channel";
    static final String INTENT_CLASS_MAIN_ACTIVITY = "MainActivity";

    // values have to be unique within each app
    static final int NOTIFY_MANAGER_START_FOREGROUND_SERVICE = 1001;

    private Constants() {}
}
