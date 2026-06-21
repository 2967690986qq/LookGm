package com.lookgm;

import android.content.Context;
import android.os.Bundle;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.HashMap;
import java.util.Locale;

public class LookGmVoiceModule extends ReactContextBaseJavaModule {

  private static final String MODULE_NAME = "LookGmVoice";
  private TextToSpeech tts;
  private boolean ready = false;
  private boolean enabled = true;
  private float pitch = 1.0f;
  private float rate = 1.0f;
  private int utteranceCounter = 0;

  public LookGmVoiceModule(ReactApplicationContext reactContext) {
    super(reactContext);
    initTTS();
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  private void initTTS() {
    try {
      Context ctx = getReactApplicationContext();
      tts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
          if (status == TextToSpeech.SUCCESS) {
            try {
              Locale zh = new Locale("zh", "CN");
              int r = tts.setLanguage(zh);
              if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault());
              }
              tts.setPitch(pitch);
              tts.setSpeechRate(rate);
              ready = true;

              tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                  sendEvent("Voice:onStart", null);
                }
                @Override
                public void onDone(String utteranceId) {
                  sendEvent("Voice:onDone", null);
                }
                @Override
                public void onError(String utteranceId) {
                  sendEvent("Voice:onError", null);
                }
              });
            } catch (Exception ignored) {
            }
          }
        }
      });
    } catch (Exception ignored) {
    }
  }

  @ReactMethod
  public void speak(String text, Promise promise) {
    try {
      if (!enabled) {
        promise.resolve(false);
        return;
      }
      if (tts == null || !ready) {
        promise.resolve(false);
        return;
      }
      if (text == null || text.trim().isEmpty()) {
        promise.resolve(false);
        return;
      }

      String utteranceId = "lookgm_" + (utteranceCounter++);
      HashMap<String, String> params = new HashMap<>();
      params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
      params.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0");

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        Bundle bundle = new Bundle();
        bundle.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId);
      } else {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void stop(Promise promise) {
    try {
      if (tts != null) {
        tts.stop();
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void setEnabled(boolean flag, Promise promise) {
    enabled = flag;
    if (!flag && tts != null) {
      try {
        tts.stop();
      } catch (Exception ignored) {
      }
    }
    promise.resolve(true);
  }

  @ReactMethod
  public void setRate(double value, Promise promise) {
    rate = (float) value;
    if (tts != null) {
      try {
        tts.setSpeechRate(rate);
      } catch (Exception ignored) {
      }
    }
    promise.resolve(true);
  }

  @ReactMethod
  public void setPitch(double value, Promise promise) {
    pitch = (float) value;
    if (tts != null) {
      try {
        tts.setPitch(pitch);
      } catch (Exception ignored) {
      }
    }
    promise.resolve(true);
  }

  @ReactMethod
  public void isReady(Promise promise) {
    promise.resolve(ready && tts != null);
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
