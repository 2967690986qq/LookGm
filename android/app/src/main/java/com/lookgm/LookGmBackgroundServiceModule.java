package com.lookgm;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

public class LookGmBackgroundServiceModule extends ReactContextBaseJavaModule {

  private static final String MODULE_NAME = "LookGmBackgroundService";
  private static boolean serviceRunning = false;

  public LookGmBackgroundServiceModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  @ReactMethod
  public void startService(String status, Promise promise) {
    try {
      Context ctx = getReactApplicationContext();
      Intent serviceIntent = new Intent(ctx, LookGmBackgroundService.class);
      serviceIntent.setAction(LookGmBackgroundService.ACTION_START);
      if (status != null && !status.isEmpty()) {
        serviceIntent.putExtra(LookGmBackgroundService.EXTRA_STATUS, status);
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(serviceIntent);
      } else {
        ctx.startService(serviceIntent);
      }

      serviceRunning = true;
      WritableMap map = Arguments.createMap();
      map.putBoolean("running", true);
      sendEvent("BackgroundService:onStateChange", map);
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void stopService(Promise promise) {
    try {
      Context ctx = getReactApplicationContext();
      Intent serviceIntent = new Intent(ctx, LookGmBackgroundService.class);
      serviceIntent.setAction(LookGmBackgroundService.ACTION_STOP);
      ctx.startService(serviceIntent);
      ctx.stopService(serviceIntent);

      serviceRunning = false;
      WritableMap map = Arguments.createMap();
      map.putBoolean("running", false);
      sendEvent("BackgroundService:onStateChange", map);
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void updateStatus(String status, Promise promise) {
    try {
      Context ctx = getReactApplicationContext();
      Intent serviceIntent = new Intent(ctx, LookGmBackgroundService.class);
      serviceIntent.setAction(LookGmBackgroundService.ACTION_UPDATE_STATUS);
      serviceIntent.putExtra(LookGmBackgroundService.EXTRA_STATUS, status);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ctx.startForegroundService(serviceIntent);
      } else {
        ctx.startService(serviceIntent);
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void isRunning(Promise promise) {
    promise.resolve(serviceRunning);
  }

  private void sendEvent(String eventName, WritableMap params) {
    try {
      getReactApplicationContext()
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
          .emit(eventName, params == null ? Arguments.createMap() : params);
    } catch (Exception ignored) {
    }
  }
}
