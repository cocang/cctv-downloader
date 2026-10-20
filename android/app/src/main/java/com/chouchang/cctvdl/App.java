package com.chouchang.cctvdl;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃记录：未捕获的异常写入 files/crash.txt，下次打开应用时显示出来，方便复制给开发者。
 */
public final class App extends Application {
  static final String CRASH_FILE = "crash.txt";

  @Override public void onCreate() {
    super.onCreate();
    final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
      try { record(this, t, e); } catch (Throwable ignored) {}
      if (prev != null) prev.uncaughtException(t, e);
      else { android.os.Process.killProcess(android.os.Process.myPid()); System.exit(10); }
    });
  }

  static void record(Context ctx, Thread t, Throwable e) throws Exception {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    String ver = "?";
    try {
      PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
      ver = pi.versionName;
    } catch (Exception ignored) {}
    pw.println("时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
    pw.println("应用：" + ver + "  系统：Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    pw.println("设备：" + Build.MANUFACTURER + " " + Build.MODEL);
    pw.println("线程：" + (t == null ? "?" : t.getName()));
    pw.println();
    e.printStackTrace(pw);
    pw.flush();
    try (FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_FILE))) {
      out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
    }
  }

  /** 读取并删除上次的崩溃记录；没有则返回 null */
  static String takeCrash(Context ctx) {
    File f = new File(ctx.getFilesDir(), CRASH_FILE);
    if (!f.exists()) return null;
    try (FileInputStream in = new FileInputStream(f)) {
      byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
      int o = 0, n;
      while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
      return new String(b, 0, o, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    } finally {
      //noinspection ResultOfMethodCallIgnored
      f.delete();
    }
  }
}
