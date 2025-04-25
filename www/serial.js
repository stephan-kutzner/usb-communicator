const serial = {
  requestPermission: function (successCallback, errorCallback) {
//    if (typeof opts === "function") {
//      //user did not pass opts
//      errorCallback = successCallback;
//      successCallback = opts;
//      opts = {};
//    }
    cordova.exec(
      successCallback,
      errorCallback,
      "Serial",
      "requestPermission",
      [],
//      [{ opts: opts }],
    );
  },
  open: function (opts, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "openSerial", [
      { opts: opts },
    ]);
  },
  write: function (command, version, id, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "writeSerial", [
      { command: command,
        version: version,
        id: id
       },
    ]);
  },
  update: function (command, successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "updateScanner", [
      { command: command,
       },
    ]);
  },
  updateProgress: function (successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "updateScannerProgress", []);
  },
  close: function (successCallback, errorCallback) {
    cordova.exec(successCallback, errorCallback, "Serial", "closeSerial", []);
  },
  registerReadCallback: function (successCallback, errorCallback) {
    cordova.exec(
      successCallback,
      errorCallback,
      "Serial",
      "registerReadCallback",
      [],
    );
  },
};
module.exports = serial;