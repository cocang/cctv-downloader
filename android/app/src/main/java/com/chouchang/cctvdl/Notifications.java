package com.chouchang.cctvdl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** 通知渠道与「下载完成」通知 */
public final class Notifications {
  static final String CH_PROGRESS = "progress";
  static final String CH_DONE = "done";
  static final int ID_PROGRESS = 1;

  private Notifications() {}

  public static void ensureChannels(Context ctx) {
    NotificationManager nm = ctx.getSystemService(NotificationManager.class);
    NotificationChannel p = new NotificationChannel(CH_PROGRESS, "下载进度", NotificationManager.IMPORTANCE_LOW);
    p.setDescription("正在下载的视频");
    p.setShowBadge(false);
    NotificationChannel d = new NotificationChannel(CH_DONE, "下载完成", NotificationManager.IMPORTANCE_DEFAULT);
    d.setDescription("视频下载完成或失败时提醒");
    nm.createNotificationChannel(p);
    nm.createNotificationChannel(d);
  }

  public static boolean allowed(Context ctx) {
    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    return nm.areNotificationsEnabled();
  }

  public static Intent viewIntent(String uri) {
    Intent v = new Intent(Intent.ACTION_VIEW);
    v.setDataAndType(Uri.parse(uri), "video/mp4");
    v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    return v;
  }

  static PendingIntent openApp(Context ctx, int req, String tab) {
    Intent i = new Intent(ctx, MainActivity.class);
    i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (tab != null) i.putExtra("tab", tab);
    return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** 下载完成 / 失败通知。成功时点按直接用系统播放器打开。 */
  public static void done(Context ctx, String jobId, String title, String text, String uri, boolean ok) {
    ensureChannels(ctx);
    int nid = 1000 + (jobId.hashCode() & 0x7fffff);
    Notification.Builder b = new Notification.Builder(ctx, CH_DONE);
    b.setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setColor(0xFFF2A900)
        .setCategory(ok ? Notification.CATEGORY_STATUS : Notification.CATEGORY_ERROR);
    if (ok && uri != null && !uri.isEmpty()) {
      PendingIntent play = PendingIntent.getActivity(ctx, nid, viewIntent(uri),
          PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      b.setContentIntent(play);
      b.addAction(new Notification.Action.Builder(null, "播放", play).build());
      b.addAction(new Notification.Action.Builder(null, "查看下载", openApp(ctx, nid + 1, "jobs")).build());
    } else {
      b.setContentIntent(openApp(ctx, nid, "jobs"));
    }
    NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    try { nm.notify(nid, b.build()); } catch (SecurityException ignored) { /* 未授予通知权限 */ }
  }
}
