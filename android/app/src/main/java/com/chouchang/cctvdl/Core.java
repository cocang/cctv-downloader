package com.chouchang.cctvdl;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 进程级单例：本机服务 + 状态文件。界面重建时下载不受影响。 */
public final class Core {
  private static final String TAG = "CCTVDL";
  private static LocalServer server;

  private Core() {}

  public static synchronized LocalServer server(Context ctx) throws IOException {
    if (server == null) {
      final Context app = ctx.getApplicationContext();
      // 套接字操作放到后台线程完成，避免主线程网络限制（StrictMode / NetworkOnMainThreadException）
      final LocalServer[] made = new LocalServer[1];
      final Throwable[] err = new Throwable[1];
      Thread t = new Thread(() -> {
        try {
          made[0] = new LocalServer(path -> openAsset(app, path), new com.chouchang.cctvdl.save.SaveStore(app), 4);
          made[0].start();
        } catch (Throwable e) {
          err[0] = e;
        }
      }, "cctv-bind");
      t.start();
      try { t.join(5000); } catch (InterruptedException ignored) {}
      if (made[0] == null) {
        throw new IOException(err[0] == null ? "本机服务启动超时" : String.valueOf(err[0].getMessage()), err[0]);
      }
      server = made[0];
      new Thread(() -> purgePending(app), "cctv-purge").start();
    }
    return server;
  }

  private static InputStream openAsset(Context ctx, String path) throws IOException {
    return ctx.getAssets().open("web/" + path);
  }

  /** 清理上次进程意外结束时留下的半成品（只会看到本应用自己创建的条目） */
  private static void purgePending(Context ctx) {
    if (Build.VERSION.SDK_INT < 30) return;
    ContentResolver cr = ctx.getContentResolver();
    Bundle args = new Bundle();
    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
    for (Uri base : new Uri[] { MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI }) {
      try (Cursor c = cr.query(base, new String[] { MediaStore.MediaColumns._ID }, args, null)) {
        if (c == null) continue;
        while (c.moveToNext()) {
          try { cr.delete(Uri.withAppendedPath(base, Long.toString(c.getLong(0))), null, null); }
          catch (RuntimeException ignored) {}
        }
      } catch (RuntimeException e) {
        Log.w(TAG, "purge pending failed", e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 状态（设置与下载记录），JSON 文本，原子写入
  // ---------------------------------------------------------------------------
  private static final Object LOCK = new Object();

  public static String loadState(Context ctx) {
    synchronized (LOCK) {
      File f = new File(ctx.getFilesDir(), "state.json");
      if (!f.exists()) return "{}";
      try (FileInputStream in = new FileInputStream(f)) {
        byte[] b = new byte[(int) f.length()];
        int o = 0, n;
        while (o < b.length && (n = in.read(b, o, b.length - o)) > 0) o += n;
        return new String(b, 0, o, StandardCharsets.UTF_8);
      } catch (IOException e) {
        return "{}";
      }
    }
  }

  public static void saveState(Context ctx, String json) {
    synchronized (LOCK) {
      File dir = ctx.getFilesDir();
      File tmp = new File(dir, "state.json.tmp");
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(json.getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      } catch (IOException e) {
        Log.w(TAG, "save state failed", e);
        return;
      }
      //noinspection ResultOfMethodCallIgnored
      tmp.renameTo(new File(dir, "state.json"));
    }
  }
}
