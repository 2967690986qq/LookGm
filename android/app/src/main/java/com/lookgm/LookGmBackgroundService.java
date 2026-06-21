package com.lookgm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class LookGmBackgroundService extends Service {

  private static final String CHANNEL_ID = "LookGmBackgroundChannel";
  private static final int NOTIFICATION_ID = 1001;
  public static final String ACTION_START = "com.lookgm.ACTION_START";
  public static final String ACTION_STOP = "com.lookgm.ACTION_STOP";
  public static final String ACTION_UPDATE_STATUS = "com.lookgm.ACTION_UPDATE_STATUS";
  public static final String EXTRA_STATUS = "status";

  private String currentStatus = "LookGm 正在监测";

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null) {
      String action = intent.getAction();
      if (ACTION_STOP.equals(action)) {
        stopForeground(true);
        stopSelf();
        return START_NOT_STICKY;
      } else if (ACTION_UPDATE_STATUS.equals(action)) {
        String status = intent.getStringExtra(EXTRA_STATUS);
        if (status != null && !status.isEmpty()) {
          currentStatus = status;
          startForeground(NOTIFICATION_ID, buildNotification(currentStatus));
        }
      } else if (ACTION_START.equals(action)) {
        String status = intent.getStringExtra(EXTRA_STATUS);
        if (status != null && !status.isEmpty()) {
          currentStatus = status;
        }
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus));
      }
    } else {
      startForeground(NOTIFICATION_ID, buildNotification(currentStatus));
    }
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
  }

  private Notification buildNotification(String statusText) {
    Intent notificationIntent = new Intent(this, MainActivity.class);
    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

    int flags;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    } else {
      flags = PendingIntent.FLAG_UPDATE_CURRENT;
    }
    PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

    int iconResId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
    if (iconResId == 0) {
      iconResId = android.R.drawable.ic_menu_info_details;
    }

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("LookGm")
        .setContentText(statusText)
        .setSmallIcon(iconResId)
        .setContentIntent(pendingIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setWhen(System.currentTimeMillis());

    return builder.build();
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "LookGm 后台监测",
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("游戏对局评分助手后台运行通知");
        channel.enableLights(false);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
      }
    }
  }
}
