package com.lookgm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.FrameLayout;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.reflect.Method;

public class LookGmFloatingWindowModule extends ReactContextBaseJavaModule {

  private static final String MODULE_NAME = "LookGmFloatingWindow";
  private static final int REQ_OVERLAY = 1001;

  private WindowManager windowManager;
  private View floatingView;
  private boolean isShowing = false;
  private boolean isMinimized = false;
  private WindowManager.LayoutParams params;
  private String currentScore = "0.0";
  private String currentGrade = "暂无评级";
  private String currentStatus = "LookGm";

  public LookGmFloatingWindowModule(ReactApplicationContext reactContext) {
    super(reactContext);
    windowManager = (WindowManager) reactContext.getSystemService(Context.WINDOW_SERVICE);
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  @ReactMethod
  public void canDrawOverlays(Promise promise) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        promise.resolve(Settings.canDrawOverlays(getReactApplicationContext()));
      } else {
        promise.resolve(true);
      }
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void requestOverlayPermission(Promise promise) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
          && !Settings.canDrawOverlays(getReactApplicationContext())) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getReactApplicationContext().getPackageName()));
        Activity activity = getCurrentActivity();
        if (activity != null) {
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          activity.startActivityForResult(intent, REQ_OVERLAY);
        }
        promise.resolve(false);
      } else {
        promise.resolve(true);
      }
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void show(Promise promise) {
    try {
      if (isShowing && floatingView != null) {
        promise.resolve(true);
        return;
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
          && !Settings.canDrawOverlays(getReactApplicationContext())) {
        promise.resolve(false);
        return;
      }

      Context ctx = getReactApplicationContext();
      LayoutInflater inflater = LayoutInflater.from(ctx);
      floatingView = inflater.inflate(R.layout.floating_window, null);

      int type;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
      } else {
        type = WindowManager.LayoutParams.TYPE_PHONE;
      }

      params = new WindowManager.LayoutParams(
          WindowManager.LayoutParams.WRAP_CONTENT,
          WindowManager.LayoutParams.WRAP_CONTENT,
          type,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
              | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
          PixelFormat.TRANSLUCENT);

      params.gravity = Gravity.TOP | Gravity.END;
      params.x = 20;
      params.y = 200;

      updateFloatingView(floatingView);
      setupTouchAndClick(floatingView);

      windowManager.addView(floatingView, params);
      isShowing = true;
      isMinimized = false;
      sendEvent("FloatingWindow:onShow", null);
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void hide(Promise promise) {
    try {
      if (isShowing && floatingView != null && windowManager != null) {
        windowManager.removeViewImmediate(floatingView);
        floatingView = null;
        isShowing = false;
        isMinimized = false;
        sendEvent("FloatingWindow:onHide", null);
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void minimize(Promise promise) {
    try {
      if (!isShowing || floatingView == null) {
        promise.resolve(false);
        return;
      }

      View minimized = floatingView.findViewById(R.id.floating_minimized);
      View expanded = floatingView.findViewById(R.id.floating_expanded);
      if (minimized != null && expanded != null) {
        minimized.setVisibility(View.VISIBLE);
        expanded.setVisibility(View.GONE);
        isMinimized = true;
        sendEvent("FloatingWindow:onMinimize", null);
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void expand(Promise promise) {
    try {
      if (!isShowing || floatingView == null) {
        promise.resolve(false);
        return;
      }

      View minimized = floatingView.findViewById(R.id.floating_minimized);
      View expanded = floatingView.findViewById(R.id.floating_expanded);
      if (minimized != null && expanded != null) {
        minimized.setVisibility(View.GONE);
        expanded.setVisibility(View.VISIBLE);
        isMinimized = false;
        sendEvent("FloatingWindow:onExpand", null);
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void updateScore(String score, String grade, Promise promise) {
    try {
      this.currentScore = score;
      this.currentGrade = grade;
      if (isShowing && floatingView != null) {
        updateFloatingView(floatingView);
        // 同时更新收起状态的圆形按钮
        TextView miniScore = (TextView) floatingView.findViewById(R.id.floating_mini_score);
        if (miniScore != null) {
          miniScore.setText(score);
        }
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void updateStatus(String status, Promise promise) {
    try {
      this.currentStatus = status;
      if (isShowing && floatingView != null) {
        updateFloatingView(floatingView);
      }
      promise.resolve(true);
    } catch (Exception e) {
      promise.reject("ERROR", e);
    }
  }

  @ReactMethod
  public void isShowing(Promise promise) {
    promise.resolve(isShowing);
  }

  @ReactMethod
  public void isMinimized(Promise promise) {
    promise.resolve(isMinimized);
  }

  private void updateFloatingView(View view) {
    try {
      TextView scoreView = (TextView) view.findViewById(R.id.floating_score);
      TextView gradeView = (TextView) view.findViewById(R.id.floating_grade);
      TextView statusView = (TextView) view.findViewById(R.id.floating_status);

      if (scoreView != null) {
        scoreView.setText(currentScore);
      }
      if (gradeView != null) {
        gradeView.setText(currentGrade);
      }
      if (statusView != null) {
        statusView.setText(currentStatus);
      }
    } catch (Exception ignored) {
    }
  }

  private void setupTouchAndClick(View view) {
    // 收起状态点击：展开窗口
    final View minimized = view.findViewById(R.id.floating_minimized);
    if (minimized != null) {
      minimized.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          expand(null);
        }
      });
    }

    // 收起按钮：收起窗口
    final Button collapseBtn = (Button) view.findViewById(R.id.floating_collapse);
    if (collapseBtn != null) {
      collapseBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          minimize(null);
        }
      });
    }

    // 关闭按钮：完全隐藏
    final Button closeBtn = (Button) view.findViewById(R.id.floating_close);
    if (closeBtn != null) {
      closeBtn.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          hide(null);
        }
      });
    }

    // 拖动和点击展开
    final View expanded = view.findViewById(R.id.floating_expanded);
    final View header = view.findViewById(R.id.floating_header);
    if (header != null && expanded != null) {
      header.setOnTouchListener(new View.OnTouchListener() {
        private int initialX, initialY;
        private float initialTouchX, initialTouchY;
        private boolean isDragging = false;
        private long startTime;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
          switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
              initialX = params.x;
              initialY = params.y;
              initialTouchX = event.getRawX();
              initialTouchY = event.getRawY();
              isDragging = false;
              startTime = System.currentTimeMillis();
              return true;
            case MotionEvent.ACTION_MOVE:
              int dx = (int) (event.getRawX() - initialTouchX);
              int dy = (int) (event.getRawY() - initialTouchY);
              if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                isDragging = true;
                params.x = initialX + dx;
                params.y = initialY + dy;
                try {
                  windowManager.updateViewLayout(floatingView, params);
                } catch (Exception ignored) {
                }
              }
              return true;
            case MotionEvent.ACTION_UP:
              if (!isDragging && (System.currentTimeMillis() - startTime) < 300) {
                // 单击收起窗口
                minimize(null);
              }
              return true;
          }
          return false;
        }
      });
    }
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
