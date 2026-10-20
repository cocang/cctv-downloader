package com.chouchang.cctvdl;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 前台服务：有任务在下载时保持进程处于前台优先级，切到后台或锁屏后下载继续进行，
 * 并在通知栏显示总进度。所有任务结束后自动停止。
 */
public final class DownloadService extends Service {
  static final String EXTRA_ACTIVE = "active";
  static final String EXTRA_PERCENT = "percent";
  static final String EXTRA_TITLE = "title";

  private PowerManager.WakeLock wake;
  private boolean foreground;

  public static void update(Context ctx, int active, int percent, String title) {
    Intent i = new Intent(ctx, DownloadService.class);
    i.putExtra(EXTRA_ACTIVE, active);
    i.putExtra(EXTRA_PERCENT, percent);
    i.putExtra(EXTRA_TITLE, title);
    try {
      if (active > 0) ctx.startForegroundService(i);
      else ctx.startService(i);
    } catch (RuntimeException ignored) {
      // 后台限制等情况下无法启动；下载本身仍在进行
    }
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    int active = intent == null ? 0 : intent.getIntExtra(EXTRA_ACTIVE, 0);
    int percent = intent == null ? 0 : intent.getIntExtra(EXTRA_PERCENT, 0);
    String title = intent == null ? null : intent.getStringExtra(EXTRA_TITLE);
    if (active <= 0) {
      if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
      foreground = false;
      releaseWake();
      stopSelf();
      return START_NOT_STICKY;
    }
    // 每次 startForegroundService 之后都要调用 startForeground（重复调用只会更新通知）
    try {
      startAsForeground(build(active, percent, title));
    } catch (RuntimeException e) {
      // Android 12+ 后台限制：无法进入前台时直接结束服务，下载在应用内继续
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!foreground) { foreground = true; acquireWake(); }
    return START_NOT_STICKY;
  }

  private void startAsForeground(Notification n) {
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(Notifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    } else {
      startForeground(Notifications.ID_PROGRESS, n);
    }
  }

  private Notification build(int active, int percent, String title) {
    Notifications.ensureChannels(this);
    Notification.Builder b = new Notification.Builder(this, Notifications.CH_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setColor(0xFFF2A900)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setContentIntent(Notifications.openApp(this, 1, "jobs"))
        .setContentTitle(active > 1 ? "正在处理 " + active + " 个任务" : (title == null || title.isEmpty() ? "正在下载" : title))
        .setContentText(percent < 0 ? "正在录制直播" : percent + "%")
        .setProgress(100, Math.max(0, Math.min(100, percent)), percent < 0);
    if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    return b.build();
  }

  private void acquireWake() {
    if (wake != null) return;
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctvdl:download");
    wake.setReferenceCounted(false);
    wake.acquire(6 * 60 * 60 * 1000L);
  }

  private void releaseWake() {
    if (wake != null && wake.isHeld()) wake.release();
    wake = null;
  }

  @Override public void onTimeout(int startId, int fgsType) {
    // Android 15：dataSync 前台服务每日时长用尽
    stopForeground(STOP_FOREGROUND_REMOVE);
    foreground = false;
    releaseWake();
    stopSelf();
  }

  @Override public void onDestroy() {
    releaseWake();
    super.onDestroy();
  }
}
