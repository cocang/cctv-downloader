package com.chouchang.cctvdl;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本机回环服务（只监听 127.0.0.1，纯 Java，可在桌面 JVM 上单测）。
 *
 * 界面和下载引擎运行在 WebView 里，复用扩展中已验证的 h5e 解密 / TS 处理 / MP4 封装代码。
 * WebView 页面不能跨域读取央视网接口，也不能直接写公共存储，因此由这里提供：
 *
 *   GET  /...              静态页面（assets/web）
 *   GET  /p?t=&u=URL       代理请求央视网（桌面浏览器 UA，跟随跳转，流式回传，补上 CORS 头）
 *   POST /f/open?t=&mime=  请求体为 meta JSON；新建输出文件，返回 {"id","name","path"}，或 {"exists":true,…}
 *   POST /f/w?t=&id=       追加写入请求体
 *   POST /f/commit?t=&id=  完成写入，文件出现在系统「下载」目录，返回 {"uri","name","path"}
 *   POST /f/abort?t=&id=   放弃并删除
 *
 * 同时监听多个端口：浏览器对同一来源最多 6 条 HTTP/1.1 连接，不同上游主机分散到不同端口，
 * 切片下载可以保持与扩展一致的并发度。除静态页面外的请求都必须带随机令牌 t，防止设备上其他应用调用。
 */
public final class LocalServer {

  /** 静态资源来源（Android：AssetManager；桌面测试：目录） */
  public interface Assets {
    InputStream open(String path) throws IOException;
  }

  /** 输出文件的落地方式（Android：MediaStore / 公共下载目录；桌面测试：普通目录） */
  public interface Sink {
    /** meta：JSON 文本（标题、清晰度、剧集…），由实现按保存设置决定位置与文件名 */
    Output create(String mime, String meta) throws IOException;
  }

  public interface Output {
    OutputStream stream();
    /** 关闭并公开文件，返回描述信息（uri / name / path） */
    Map<String, String> commit() throws IOException;
    void abort();
    /** 「跳过同名文件」时返回已有文件的描述（uri / name / path / size），否则为 null */
    default Map<String, String> existing() { return null; }
    /** 新文件的预定描述（name / path），可为 null */
    default Map<String, String> target() { return null; }
  }

  public static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

  private final Assets assets;
  private final Sink sink;
  private final String token;
  private final ServerSocket[] sockets;
  private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "cctv-local");
    t.setDaemon(true);
    return t;
  });
  private final Map<String, Output> outputs = new ConcurrentHashMap<>();
  private final AtomicLong seq = new AtomicLong();
  private volatile boolean running;

  public LocalServer(Assets assets, Sink sink, int portCount) throws IOException {
    this.assets = assets;
    this.sink = sink;
    byte[] b = new byte[18];
    new SecureRandom().nextBytes(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
    this.token = sb.toString();
    InetAddress lo = InetAddress.getByName("127.0.0.1");
    sockets = new ServerSocket[portCount];
    for (int i = 0; i < portCount; i++) sockets[i] = new ServerSocket(0, 64, lo);
    System.setProperty("http.keepAlive", "true");
    System.setProperty("http.maxConnections", "24");
  }

  public String token() { return token; }

  public int[] ports() {
    int[] p = new int[sockets.length];
    for (int i = 0; i < p.length; i++) p[i] = sockets[i].getLocalPort();
    return p;
  }

  /** 页面入口地址：令牌与端口放在 hash 中，不会出现在任何请求里 */
  public String entryUrl() {
    StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(sockets[0].getLocalPort())
        .append("/engine.html#t=").append(token).append("&p=");
    int[] p = ports();
    for (int i = 0; i < p.length; i++) { if (i > 0) sb.append(','); sb.append(p[i]); }
    return sb.toString();
  }

  public synchronized void start() {
    if (running) return;
    running = true;
    for (ServerSocket ss : sockets) {
      Thread t = new Thread(() -> acceptLoop(ss), "cctv-accept-" + ss.getLocalPort());
      t.setDaemon(true);
      t.start();
    }
  }

  public synchronized void stop() {
    running = false;
    for (ServerSocket ss : sockets) { try { ss.close(); } catch (IOException ignored) {} }
    for (Output o : outputs.values()) o.abort();
    outputs.clear();
    pool.shutdownNow();
  }

  private void acceptLoop(ServerSocket ss) {
    while (running) {
      try {
        Socket s = ss.accept();
        pool.execute(() -> serve(s));
      } catch (IOException e) {
        if (!running) return;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // HTTP/1.1（keep-alive）
  // ---------------------------------------------------------------------------
  private static final class Request {
    String method, path, origin = "";
    final Map<String, String> query = new LinkedHashMap<>();
    final Map<String, String> headers = new LinkedHashMap<>();
    long contentLength;
    boolean close;
    String q(String k) { String v = query.get(k); return v == null ? "" : v; }
  }

  private void serve(Socket s) {
    try (Socket sock = s) {
      sock.setSoTimeout(65000);
      sock.setTcpNoDelay(true);
      BufferedInputStream in = new BufferedInputStream(sock.getInputStream(), 65536);
      OutputStream out = sock.getOutputStream();
      while (running) {
        Request req = readRequest(in);
        if (req == null) return;
        BodyStream body = new BodyStream(in, req.contentLength);
        handle(req, body, out);
        body.drain();
        out.flush();
        if (req.close) return;
      }
    } catch (IOException ignored) {
      // 客户端断开（例如页面中止了请求）
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(128);
    int c;
    while ((c = in.read()) != -1) {
      if (c == '\n') break;
      if (c != '\r') b.write(c);
      if (b.size() > 16384) throw new IOException("header too long");
    }
    if (c == -1 && b.size() == 0) return null;
    return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
  }

  private static Request readRequest(InputStream in) throws IOException {
    String line;
    do { line = readLine(in); if (line == null) return null; } while (line.isEmpty());
    String[] parts = line.split(" ");
    if (parts.length < 2) return null;
    Request r = new Request();
    r.method = parts[0].toUpperCase(Locale.ROOT);
    String target = parts[1];
    int qi = target.indexOf('?');
    r.path = qi >= 0 ? target.substring(0, qi) : target;
    if (qi >= 0) {
      for (String kv : target.substring(qi + 1).split("&")) {
        if (kv.isEmpty()) continue;
        int e = kv.indexOf('=');
        String k = e >= 0 ? kv.substring(0, e) : kv;
        String v = e >= 0 ? kv.substring(e + 1) : "";
        r.query.put(dec(k), dec(v));
      }
    }
    String h;
    while ((h = readLine(in)) != null && !h.isEmpty()) {
      int c = h.indexOf(':');
      if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
    }
    String cl = r.headers.get("content-length");
    r.contentLength = cl == null ? 0 : Long.parseLong(cl);
    r.close = "close".equalsIgnoreCase(r.headers.get("connection"));
    String o = r.headers.get("origin");
    if (o != null && o.startsWith("http://127.0.0.1:")) r.origin = o;
    return r;
  }

  private static String dec(String s) {
    try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
  }

  /** 只读取声明长度的请求体，读完后剩余部分丢弃，保证 keep-alive 连接对齐 */
  private static final class BodyStream extends InputStream {
    private final InputStream in;
    private long left;
    BodyStream(InputStream in, long len) { this.in = in; this.left = len; }
    @Override public int read() throws IOException {
      if (left <= 0) return -1;
      int c = in.read();
      if (c >= 0) left--;
      return c;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) return -1;
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      if (n < 0) throw new IOException("unexpected eof");
      return n;
    }
    void drain() throws IOException {
      byte[] buf = new byte[8192];
      while (left > 0 && read(buf, 0, buf.length) > 0) { /* skip */ }
    }
  }

  /** 分块传输编码（上游未给出长度时使用） */
  private static final class ChunkedOutputStream extends FilterOutputStream {
    ChunkedOutputStream(OutputStream out) { super(out); }
    @Override public void write(int b) throws IOException { write(new byte[] { (byte) b }, 0, 1); }
    @Override public void write(byte[] b, int off, int len) throws IOException {
      if (len == 0) return;
      out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
      out.write(b, off, len);
      out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }
    void finish() throws IOException { out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); }
  }

  private static String reason(int code) {
    switch (code) {
      case 200: return "OK";
      case 204: return "No Content";
      case 400: return "Bad Request";
      case 403: return "Forbidden";
      case 404: return "Not Found";
      case 500: return "Internal Server Error";
      case 502: return "Bad Gateway";
      default: return "Status";
    }
  }

  private void writeHead(OutputStream out, Request req, int code, String type, long length, Map<String, String> extra)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
    if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
    if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n");
    else sb.append("Transfer-Encoding: chunked\r\n");
    sb.append("Cache-Control: no-store\r\n");
    if (!req.origin.isEmpty()) {
      sb.append("Access-Control-Allow-Origin: ").append(req.origin).append("\r\n");
      sb.append("Access-Control-Allow-Credentials: true\r\n");
      sb.append("Access-Control-Expose-Headers: Content-Length, Content-Type, X-Final-Url\r\n");
      sb.append("Vary: Origin\r\n");
    }
    if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) {
      sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    }
    sb.append(req.close ? "Connection: close\r\n" : "Connection: keep-alive\r\n");
    sb.append("\r\n");
    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void sendBytes(OutputStream out, Request req, int code, String type, byte[] data) throws IOException {
    writeHead(out, req, code, type, data.length, null);
    if (!"HEAD".equals(req.method)) out.write(data);
  }

  private void sendJson(OutputStream out, Request req, int code, String json) throws IOException {
    sendBytes(out, req, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
  }

  private void sendError(OutputStream out, Request req, int code, String msg) throws IOException {
    sendJson(out, req, code, "{\"error\":" + jsonStr(msg) + "}");
  }

  public static String jsonStr(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  public static String jsonObj(Map<String, String> m) {
    StringBuilder sb = new StringBuilder("{");
    for (Map.Entry<String, String> e : m.entrySet()) {
      if (sb.length() > 1) sb.append(',');
      sb.append(jsonStr(e.getKey())).append(':').append(jsonStr(e.getValue()));
    }
    return sb.append('}').toString();
  }

  private void handle(Request req, BodyStream body, OutputStream out) throws IOException {
    if ("OPTIONS".equals(req.method)) {
      Map<String, String> h = new LinkedHashMap<>();
      h.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
      h.put("Access-Control-Allow-Headers", "Content-Type");
      h.put("Access-Control-Max-Age", "86400");
      writeHead(out, req, 204, null, 0, h);
      return;
    }
    String p = req.path;
    boolean api = p.equals("/p") || p.startsWith("/f/");
    if (api && !token.equals(req.q("t"))) { sendError(out, req, 403, "forbidden"); return; }
    try {
      if (p.equals("/p")) { proxy(req, out); return; }
      if (p.equals("/f/open") && "POST".equals(req.method)) {
        ByteArrayOutputStream mb = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int r;
        while ((r = body.read(tmp, 0, tmp.length)) > 0 && mb.size() < 65536) mb.write(tmp, 0, r);
        Output o = sink.create(req.q("mime"), new String(mb.toByteArray(), StandardCharsets.UTF_8));
        Map<String, String> ex = o.existing();
        if (ex != null) {
          Map<String, String> m = new LinkedHashMap<>(ex);
          m.put("exists", "1");
          sendJson(out, req, 200, jsonObj(m).replace("\"exists\":\"1\"", "\"exists\":true"));
          return;
        }
        String id = Long.toString(seq.incrementAndGet(), 36) + Long.toString(System.nanoTime() & 0xffffff, 36);
        outputs.put(id, o);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        Map<String, String> tg = o.target();
        if (tg != null) m.putAll(tg);
        sendJson(out, req, 200, jsonObj(m));
        return;
      }
      if (p.equals("/f/w") && "POST".equals(req.method)) {
        Output o = outputs.get(req.q("id"));
        if (o == null) { sendError(out, req, 404, "no such file"); return; }
        byte[] buf = new byte[131072];
        int n;
        long total = 0;
        OutputStream os = o.stream();
        while ((n = body.read(buf, 0, buf.length)) > 0) { os.write(buf, 0, n); total += n; }
        sendJson(out, req, 200, "{\"written\":" + total + "}");
        return;
      }
      if (p.equals("/f/commit") && "POST".equals(req.method)) {
        Output o = outputs.remove(req.q("id"));
        if (o == null) { sendError(out, req, 404, "no such file"); return; }
        sendJson(out, req, 200, jsonObj(o.commit()));
        return;
      }
      if (p.equals("/f/abort") && "POST".equals(req.method)) {
        Output o = outputs.remove(req.q("id"));
        if (o != null) o.abort();
        sendJson(out, req, 200, "{}");
        return;
      }
      if ("GET".equals(req.method) || "HEAD".equals(req.method)) { serveAsset(req, out); return; }
      sendError(out, req, 404, "not found");
    } catch (SocketException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      sendError(out, req, 500, e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // 静态页面
  // ---------------------------------------------------------------------------
  private static String mime(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    if (p.endsWith(".html")) return "text/html; charset=utf-8";
    if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
    if (p.endsWith(".css")) return "text/css; charset=utf-8";
    if (p.endsWith(".svg")) return "image/svg+xml";
    if (p.endsWith(".png")) return "image/png";
    if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
    if (p.endsWith(".webp")) return "image/webp";
    if (p.endsWith(".json")) return "application/json";
    if (p.endsWith(".woff2")) return "font/woff2";
    return "application/octet-stream";
  }

  private void serveAsset(Request req, OutputStream out) throws IOException {
    String path = req.path.equals("/") ? "/engine.html" : req.path;
    path = dec(path);
    if (path.contains("..") || path.contains("\\")) { sendError(out, req, 400, "bad path"); return; }
    byte[] data;
    try (InputStream in = assets.open(path.substring(1))) {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
      data = b.toByteArray();
    } catch (IOException e) {
      sendError(out, req, 404, "not found");
      return;
    }
    sendBytes(out, req, 200, mime(path), data);
  }

  // ---------------------------------------------------------------------------
  // 代理
  // ---------------------------------------------------------------------------
  private static boolean allowedHost(String host) {
    // 只代理 http(s) 公网地址，拒绝本机 / 局域网，避免被用作跳板
    String h = host.toLowerCase(Locale.ROOT);
    if (h.isEmpty() || h.equals("localhost") || h.endsWith(".local")) return false;
    if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")
        || h.equals("0.0.0.0") || h.startsWith("[")) return false;
    if (h.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*")) return false;
    return true;
  }

  private void proxy(Request req, OutputStream out) throws IOException {
    String target = req.q("u");
    URL url;
    try { url = new URL(target); } catch (Exception e) { sendError(out, req, 400, "bad url"); return; }
    HttpURLConnection c = null;
    for (int hop = 0; hop < 6; hop++) {
      String proto = url.getProtocol();
      if (!("http".equals(proto) || "https".equals(proto)) || !allowedHost(url.getHost())) {
        sendError(out, req, 400, "blocked url");
        return;
      }
      c = (HttpURLConnection) url.openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(15000);
      c.setReadTimeout(25000);
      c.setUseCaches(false);
      c.setRequestProperty("User-Agent", UA);
      c.setRequestProperty("Accept", "*/*");
      c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
      String host = url.getHost();
      if (host.endsWith("cctv.com") || host.endsWith("cntv.cn") || host.endsWith("cctv.cn") || host.endsWith("cctvpic.com")) {
        c.setRequestProperty("Referer", "https://tv.cctv.com/");
      }
      // 直播接口需要的签名头（页面经 ?ak= 传入，避免跨域预检）
      String ak = req.q("ak");
      if (ak != null && !ak.isEmpty() && ak.length() < 128) c.setRequestProperty("auth-key", ak);
      int code;
      try {
        code = c.getResponseCode();
      } catch (IOException e) {
        c.disconnect();
        sendError(out, req, 502, e.getMessage() == null ? e.toString() : e.getMessage());
        return;
      }
      if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
        URL next = new URL(url, c.getHeaderField("Location"));
        c.disconnect();
        url = next;
        continue;
      }
      break;
    }
    if (c == null) { sendError(out, req, 502, "too many redirects"); return; }
    int code = c.getResponseCode();
    InputStream in = null;
    try {
      in = code >= 400 ? c.getErrorStream() : c.getInputStream();
      String type = c.getContentType();
      long len = c.getContentLengthLong();
      String enc = c.getContentEncoding();
      if (enc != null && !enc.equalsIgnoreCase("identity")) len = -1; // 已被透明解压
      if (in == null) len = 0;
      Map<String, String> extra = new LinkedHashMap<>();
      extra.put("X-Final-Url", url.toString());
      writeHead(out, req, code, type == null ? "application/octet-stream" : type, len, extra);
      if (in == null || "HEAD".equals(req.method)) return;
      byte[] buf = new byte[65536];
      int n;
      if (len >= 0) {
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      } else {
        ChunkedOutputStream co = new ChunkedOutputStream(out);
        while ((n = in.read(buf)) > 0) co.write(buf, 0, n);
        co.finish();
      }
    } catch (IOException e) {
      // 页面已中止（取消 / 对冲请求完成）或上游中断：断开两端
      c.disconnect();
      throw new SocketException("proxy aborted: " + e.getMessage());
    } finally {
      if (in != null) try { in.close(); } catch (IOException ignored) {}
    }
  }
}
