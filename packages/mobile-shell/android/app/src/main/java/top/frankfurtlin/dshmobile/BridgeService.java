package top.frankfurtlin.dshmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Keeps one authenticated WebSocket to the phone bridge open so the DSH agent
 * can reach this phone. Runs as a foreground service: the phone must stay
 * reachable while the app is backgrounded.
 */
public class BridgeService extends Service {
  /** Start or restart the connection (re-reads endpoint and token). */
  public static final String ACTION_START = "top.frankfurtlin.dshmobile.bridge.START";
  /** Stop connecting and end the service. */
  public static final String ACTION_STOP = "top.frankfurtlin.dshmobile.bridge.STOP";

  private static final String TAG = "DshBridge";
  private static final String CHANNEL_STATUS = "dsh_bridge_status";
  private static final String CHANNEL_AGENT = "dsh_agent_messages";
  private static final int STATUS_NOTIFICATION_ID = 71;
  private static final long[] BACKOFF_MS = { 2_000, 4_000, 8_000, 15_000, 30_000, 60_000 };

  private final Handler main = new Handler(Looper.getMainLooper());
  private OkHttpClient http;
  private WebSocket socket;
  private int attempt = 0;
  private boolean running = false;

  /** Last status line; the web app reads it through `DshMobile.bridgeStatus()`. */
  public static volatile String status = "未启动";

  @Override
  public android.os.IBinder onBind(Intent intent) {
    return null; // No local binding: the service is driven by start/stop intents.
  }

  @Override
  public void onCreate() {
    super.onCreate();
    createChannels();
    Notification status = statusNotification("正在连接手机桥…");
    // The platform refuses some foreground service types unless the app holds a prerequisite
    // permission and throws from right here — historically that took the whole app process down
    // (SecurityException from ActivityThread.handleCreateService). Degrade instead: keep running
    // as a plain started service, say why in the notification, and let the WebSocket connect.
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(STATUS_NOTIFICATION_ID, status, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      } else {
        startForeground(STATUS_NOTIFICATION_ID, status);
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "startForeground refused; running without the foreground promotion", e);
      updateStatus("前台服务被系统拒绝（" + e.getClass().getSimpleName() + "）：App 一退到后台就可能被断开");
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? ACTION_START : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      running = false;
      closeSocket();
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }
    running = true;
    attempt = 0;
    closeSocket();
    connect();
    return START_STICKY;
  }

  @Override
  public void onDestroy() {
    running = false;
    closeSocket();
    super.onDestroy();
  }

  // ----- connection --------------------------------------------------------

  private void connect() {
    Settings settings = new Settings(this);
    Endpoint endpoint = settings.getEndpoint();
    if (endpoint == null) {
      updateStatus("未配置服务器");
      stopSelf();
      return;
    }
    String token = new CredentialStore(this).load(endpoint.host);
    if (token == null || token.isEmpty()) {
      updateStatus("未登录，桥未连接");
      stopSelf();
      return;
    }

    if (http == null) {
      http = new OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build();
    }

    Request request = new Request.Builder()
      .url(endpoint.bridgeUrl())
      .header("Cookie", "dsh_gateway_token=" + token)
      .header("Origin", endpoint.base)
      .build();
    Log.i(TAG, "connecting to " + endpoint.bridgeUrl());
    socket = http.newWebSocket(request, listener);
  }

  private final WebSocketListener listener = new WebSocketListener() {
    @Override
    public void onOpen(WebSocket webSocket, okhttp3.Response response) {
      attempt = 0;
      Log.i(TAG, "connected");
      updateStatus("已连接手机桥");
      sendHello(webSocket);
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      try {
        JSONObject msg = new JSONObject(text);
        String type = msg.optString("t");
        if ("welcome".equals(type)) {
          Log.i(TAG, "bridge accepted device " + msg.optString("id"));
          return;
        }
        if ("call".equals(type)) {
          handleCall(webSocket, msg.getString("id"), msg.optString("op"), msg.optJSONObject("args"));
        }
      } catch (Exception e) {
        Log.w(TAG, "bad message from bridge", e);
      }
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, okhttp3.Response response) {
      Log.w(TAG, "connection failed: " + t);
      String why = response != null ? " (" + response.code() + ")" : "";
      updateStatus("连接失败，稍后重试" + why);
      scheduleReconnect();
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      Log.i(TAG, "closed: " + code + " " + reason);
      updateStatus("连接已断开，重连中");
      scheduleReconnect();
    }
  };

  private void sendHello(WebSocket webSocket) {
    try {
      Settings settings = new Settings(this);
      JSONObject hello = new JSONObject();
      hello.put("t", "hello");
      JSONObject device = new JSONObject();
      device.put("id", settings.getDeviceId());
      device.put("name", Build.MODEL);
      device.put("model", Build.MANUFACTURER + " " + Build.MODEL);
      hello.put("device", device);
      hello.put("caps", new JSONArray(CAPS));
      webSocket.send(hello.toString());
    } catch (Exception e) {
      Log.w(TAG, "hello failed", e);
    }
  }

  private void scheduleReconnect() {
    if (!running) return;
    long delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    attempt += 1;
    main.removeCallbacks(reconnect);
    main.postDelayed(reconnect, delay);
  }

  private final Runnable reconnect = new Runnable() {
    @Override
    public void run() {
      if (running) connect();
    }
  };

  private void closeSocket() {
    main.removeCallbacks(reconnect);
    if (socket != null) {
      socket.cancel();
      socket = null;
    }
  }

  // ----- operations --------------------------------------------------------

  /** Operations this phone answers today; sent in `hello`. */
  private static final String[] CAPS = { "ping", "info", "shares", "clipboard.get", "clipboard.set", "notify", "open", "files.save", "files.open", "fs.roots", "fs.list", "fs.read", "fs.write", "fs.mkdir", "apps.list", "apps.launch", "a11y.status", "a11y.tree", "a11y.screenshot", "a11y.tap", "a11y.swipe", "a11y.global" };

  /** Public folder the agent writes into (must match {@link Files}). */
  private static final String FOLDER = "DSH";

  /** MediaStore content URI of a file the agent saved into Downloads/DSH. */
  private android.net.Uri findInDownloads(String name) {
    if (name == null || name.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
    String[] projection = { MediaStore.Downloads._ID };
    String selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
    String[] selectionArgs = { name, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%" };
    try (android.database.Cursor cursor = getContentResolver().query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs,
        MediaStore.Downloads.DATE_ADDED + " DESC")) {
      if (cursor != null && cursor.moveToFirst()) {
        return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
      }
    } catch (Exception e) {
      Log.w(TAG, "findInDownloads failed: " + name, e);
    }
    return null;
  }

  /** FileProvider URI for a path this app may share; null when not shareable. */
  private android.net.Uri uriForPath(String path) {
    try {
      java.io.File file = new java.io.File(path);
      if (!file.isFile()) return null;
      return androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
    } catch (Exception e) {
      Log.w(TAG, "uriForPath failed: " + path, e);
      return null;
    }
  }

  private void handleCall(WebSocket webSocket, String id, String op, JSONObject args) {
    try {
      switch (op) {
        case "ping": {
          JSONObject data = new JSONObject();
          data.put("pong", true);
          data.put("at", System.currentTimeMillis());
          reply(webSocket, id, true, data, null);
          return;
        }
        case "info": {
          Settings settings = new Settings(this);
          JSONObject data = new JSONObject();
          data.put("id", settings.getDeviceId());
          data.put("model", Build.MANUFACTURER + " " + Build.MODEL);
          data.put("caps", new JSONArray(CAPS));
          reply(webSocket, id, true, data, null);
          return;
        }
        case "shares": {
          int limit = args == null ? 20 : args.optInt("limit", 20);
          JSONArray all = new JSONArray(Inbox.toJson(this));
          JSONArray items = new JSONArray();
          for (int i = 0; i < all.length() && i < limit; i++) items.put(all.get(i));
          JSONObject data = new JSONObject();
          data.put("items", items);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "clipboard.get": {
          JSONObject data = new JSONObject();
          data.put("text", readClipboard());
          reply(webSocket, id, true, data, null);
          return;
        }
        case "clipboard.set": {
          final String text = args == null ? "" : args.optString("text", "");
          main.post(new Runnable() {
            @Override
            public void run() {
              writeClipboard(text);
            }
          });
          JSONObject data = new JSONObject();
          data.put("ok", true);
          data.put("bytes", text.length());
          reply(webSocket, id, true, data, null);
          return;
        }
        case "notify": {
          final String title = args == null ? "DSH" : args.optString("title", "DSH");
          final String text = args == null ? "" : args.optString("text", "");
          main.post(new Runnable() {
            @Override
            public void run() {
              postAgentNotification(title, text);
            }
          });
          JSONObject data = new JSONObject();
          data.put("ok", true);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "open": {
          final String url = args == null ? null : args.optString("url", null);
          if (url == null) {
            reply(webSocket, id, false, null, "open needs a url");
            return;
          }
          main.post(new Runnable() {
            @Override
            public void run() {
              try {
                Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
              } catch (Exception e) {
                Log.w(TAG, "open failed: " + url, e);
              }
            }
          });
          JSONObject data = new JSONObject();
          data.put("ok", true);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "files.save": {
          String name = args == null ? null : args.optString("name", null);
          String mime = args == null ? null : args.optString("mime", null);
          String dest = args == null ? null : args.optString("dest", null);
          byte[] bytes = null;
          if (args != null && args.has("data")) {
            bytes = decodeBase64(args.optString("data", ""));
          } else if (args != null && args.has("url")) {
            String url = args.optString("url", "");
            bytes = fetchBytes(url);
            if (name == null || name.isEmpty()) name = guessName(url);
          } else if (args != null && args.has("path")) {
            String path = args.optString("path", "");
            bytes = readFile(path);
            if (name == null || name.isEmpty()) name = new java.io.File(path).getName();
          }
          if (bytes == null) {
            reply(webSocket, id, false, null, "files.save needs one of data / url / path");
            return;
          }
          if (bytes.length > MAX_BYTES) {
            reply(webSocket, id, false, null, "payload too large: " + bytes.length + " bytes");
            return;
          }
          if (name == null || name.isEmpty()) name = "dsh-file";
          if (mime == null || mime.isEmpty()) mime = guessMime(name);
          Files.Saved saved = Files.save(this, name, mime, dest, bytes);
          reply(webSocket, id, true, saved.toJson(), null);
          return;
        }
        case "files.open": {
          String uri = args == null ? null : args.optString("uri", null);
          String name = args == null ? null : args.optString("name", null);
          String path = args == null ? null : args.optString("path", null);
          String mimeArg = args == null ? null : args.optString("mime", null);
          boolean hasUri = uri != null && !uri.isEmpty();
          boolean hasName = name != null && !name.isEmpty();
          boolean hasPath = path != null && !path.isEmpty();
          if (!hasUri && !hasName && !hasPath) {
            reply(webSocket, id, false, null, "files.open needs one of uri / name / path");
            return;
          }
          final String label = hasUri ? uri : (hasPath ? path : name);
          android.net.Uri target = null;
          if (hasUri) {
            target = android.net.Uri.parse(uri);
          } else if (hasPath) {
            target = uriForPath(path);
            if (target == null) target = findInDownloads(new java.io.File(path).getName());
          } else {
            target = findInDownloads(name);
          }
          if (target == null) {
            reply(webSocket, id, false, null, "no such file on this phone: " + label + " (looked in Downloads/" + FOLDER + ")");
            return;
          }
          final android.net.Uri targetUri = target;
          final String targetMime = (mimeArg != null && !mimeArg.isEmpty()) ? mimeArg : guessMime(label);
          main.post(new Runnable() {
            @Override
            public void run() {
              try {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(targetUri, targetMime);
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
              } catch (Exception e) {
                Log.w(TAG, "files.open failed: " + targetUri, e);
              }
            }
          });
          JSONObject data = new JSONObject();
          data.put("ok", true);
          data.put("uri", targetUri.toString());
          data.put("mime", targetMime);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "fs.roots": {
          reply(webSocket, id, true, fileRoots(), null);
          return;
        }
        case "fs.list": {
          reply(webSocket, id, true, fileBrowser().list(opt(args, "path"), opt(args, "uri")), null);
          return;
        }
        case "fs.read": {
          reply(webSocket, id, true, fileBrowser().read(opt(args, "path"), opt(args, "uri")), null);
          return;
        }
        case "fs.write": {
          JSONObject data = fileBrowser().write(opt(args, "path"), opt(args, "uri"), opt(args, "name"),
              opt(args, "mime"), args == null ? null : args.optString("data", null), opt(args, "encoding"));
          reply(webSocket, id, true, data, null);
          return;
        }
        case "fs.mkdir": {
          reply(webSocket, id, true, fileBrowser().mkdir(opt(args, "path"), opt(args, "uri"), opt(args, "name")), null);
          return;
        }
        case "apps.list": {
          JSONObject data = new JSONObject();
          data.put("apps", launcherApps());
          reply(webSocket, id, true, data, null);
          return;
        }
        case "apps.launch": {
          String pkg = args == null ? null : args.optString("package", null);
          if (pkg == null || pkg.isEmpty()) {
            reply(webSocket, id, false, null, "apps.launch needs a package");
            return;
          }
          launchPackage(pkg);
          JSONObject data = new JSONObject();
          data.put("ok", true);
          data.put("package", pkg);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "a11y.status": {
          JSONObject data = new JSONObject();
          data.put("service", A11yService.ready());
          data.put("allowed", new Settings(this).a11yEnabled());
          data.put("sdk", Build.VERSION.SDK_INT);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "a11y.tree": {
          requireA11y();
          int maxNodes = args == null ? 400 : args.optInt("max_nodes", 400);
          JSONObject data = new JSONObject();
          data.put("tree", new JSONObject(A11yService.instance.tree(Math.max(1, Math.min(maxNodes, 2000)))));
          reply(webSocket, id, true, data, null);
          return;
        }
        case "a11y.screenshot": {
          requireA11y();
          int maxBytes = args == null ? 0 : args.optInt("max_bytes", 0);
          JSONObject data = new JSONObject(A11yService.instance.screenshot(maxBytes));
          reply(webSocket, id, true, data, null);
          return;
        }
        case "a11y.tap": {
          requireA11y();
          int x = args == null ? 0 : args.optInt("x", 0);
          int y = args == null ? 0 : args.optInt("y", 0);
          boolean ok = A11yService.instance.tap(x, y);
          JSONObject data = new JSONObject();
          data.put("ok", ok);
          data.put("x", x);
          data.put("y", y);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "a11y.swipe": {
          requireA11y();
          int x1 = args == null ? 0 : args.optInt("x1", 0);
          int y1 = args == null ? 0 : args.optInt("y1", 0);
          int x2 = args == null ? 0 : args.optInt("x2", 0);
          int y2 = args == null ? 0 : args.optInt("y2", 0);
          int duration = args == null ? 300 : args.optInt("duration_ms", 300);
          boolean ok = A11yService.instance.swipe(x1, y1, x2, y2, duration);
          JSONObject data = new JSONObject();
          data.put("ok", ok);
          reply(webSocket, id, true, data, null);
          return;
        }
        case "a11y.global": {
          requireA11y();
          String action = args == null ? null : args.optString("action", null);
          boolean ok = A11yService.instance.global(action);
          JSONObject data = new JSONObject();
          data.put("ok", ok);
          data.put("action", action);
          reply(webSocket, id, true, data, null);
          return;
        }
        default:
          reply(webSocket, id, false, null, "unknown op " + op);
      }
    } catch (Exception e) {
      Log.w(TAG, "op " + op + " failed", e);
      reply(webSocket, id, false, null, String.valueOf(e.getMessage()));
    }
  }

  private void reply(WebSocket webSocket, String id, boolean ok, JSONObject data, String error) {
    try {
      JSONObject res = new JSONObject();
      res.put("t", "res");
      res.put("id", id);
      res.put("ok", ok);
      if (data != null) res.put("data", data);
      if (error != null) res.put("error", error);
      webSocket.send(res.toString());
    } catch (Exception e) {
      Log.w(TAG, "reply failed", e);
    }
  }

  // ----- apps and accessibility --------------------------------------------

  /** Bridge operations over the folders the user has opened to the agent. */
  private FileBrowser fileBrowser() {
    return new FileBrowser(this);
  }

  private JSONObject fileRoots() throws Exception {
    JSONObject data = new JSONObject();
    data.put("roots", fileBrowser().roots());
    String shared = new Settings(this).getFilesRoot();
    data.put("shared", shared == null ? JSONObject.NULL : shared);
    return data;
  }

  /** Trim an optional string argument; blank and an explicit JSON null both mean "absent". */
  private static String opt(JSONObject args, String key) {
    if (args == null) return null;
    String value = args.optString(key, null);
    if (value == null || value.isEmpty() || value.equals("null")) return null;
    return value;
  }

  /** Launcher apps only: package visibility is scoped by the manifest `<queries>` block. */
  private JSONArray launcherApps() {
    android.content.pm.PackageManager pm = getPackageManager();
    android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
      .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
    java.util.List<android.content.pm.ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
    java.util.TreeMap<String, String> byPackage = new java.util.TreeMap<>();
    for (android.content.pm.ResolveInfo info : resolved) {
      if (info.activityInfo == null) continue;
      String pkg = info.activityInfo.packageName;
      if (byPackage.containsKey(pkg)) continue;
      CharSequence label = info.loadLabel(pm);
      byPackage.put(pkg, label == null ? pkg : label.toString());
    }
    JSONArray out = new JSONArray();
    for (java.util.Map.Entry<String, String> entry : byPackage.entrySet()) {
      JSONObject app = new JSONObject();
      try {
        app.put("package", entry.getKey());
        app.put("label", entry.getValue());
      } catch (Exception ignored) {
        // Plain string fields cannot fail; the loop keeps its shape for the compiler.
      }
      out.put(app);
    }
    return out;
  }

  private void launchPackage(String pkg) {
    android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
    if (intent == null) throw new IllegalStateException("no launchable activity for " + pkg);
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivity(intent);
  }

  /** Automation is refused unless the user switched it on in the app AND in Android settings. */
  private void requireA11y() {
    if (!new Settings(this).a11yEnabled()) throw new IllegalStateException("accessibility automation is switched off in DSH Mobile");
    if (!A11yService.ready()) throw new IllegalStateException("the Android accessibility service is not enabled for DSH Mobile");
  }

  // ----- payload sources for files.save ------------------------------------

  /** Largest payload accepted by `files.save`. */
  private static final int MAX_BYTES = 32 * 1024 * 1024;

  /** Accepts raw base64 and `data:` URLs, which is what web-side callers hand over. */
  private static byte[] decodeBase64(String value) {
    String s = value == null ? "" : value.trim();
    int comma = s.indexOf(',');
    if (s.startsWith("data:") && comma > 0) s = s.substring(comma + 1);
    return android.util.Base64.decode(s, android.util.Base64.DEFAULT);
  }

  /** Downloads a file from the DSH server, authenticated with the stored gateway token. */
  private byte[] fetchBytes(String url) throws Exception {
    OkHttpClient client = new OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .build();
    Request.Builder req = new Request.Builder().url(url);
    Endpoint endpoint = new Settings(this).getEndpoint();
    if (endpoint != null) {
      String token = new CredentialStore(this).load(endpoint.host);
      if (token != null && !token.isEmpty()) req.header("Cookie", "dsh_gateway_token=" + token);
    }
    okhttp3.Response response = client.newCall(req.build()).execute();
    try {
      if (!response.isSuccessful()) throw new IllegalStateException("download failed: HTTP " + response.code());
      if (response.body() == null) return new byte[0];
      byte[] bytes = response.body().bytes();
      if (bytes.length > MAX_BYTES) throw new IllegalStateException("download too large: " + bytes.length + " bytes");
      return bytes;
    } finally {
      response.close();
    }
  }

  private static byte[] readFile(String path) throws Exception {
    java.io.File file = new java.io.File(path);
    if (!file.exists()) throw new java.io.FileNotFoundException(path);
    if (file.length() > MAX_BYTES) throw new IllegalStateException("file too large: " + file.length() + " bytes");
    byte[] bytes = new byte[(int) file.length()];
    java.io.FileInputStream in = new java.io.FileInputStream(file);
    try {
      int off = 0;
      while (off < bytes.length) {
        int n = in.read(bytes, off, bytes.length - off);
        if (n < 0) break;
        off += n;
      }
      return bytes;
    } finally {
      in.close();
    }
  }

  private static String guessName(String url) {
    String tail = android.net.Uri.parse(url).getLastPathSegment();
    if (tail == null || tail.isEmpty()) return "dsh-file";
    return tail;
  }

  private static String guessMime(String name) {
    return Files.guessMime(name);
  }

  private String readClipboard() {
    try {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm == null || cm.getPrimaryClip() == null) return "";
      ClipData clip = cm.getPrimaryClip();
      if (clip.getItemCount() == 0) return "";
      CharSequence text = clip.getItemAt(0).getText();
      return text == null ? "" : text.toString();
    } catch (Exception e) {
      Log.w(TAG, "clipboard read failed", e);
      return "";
    }
  }

  private void writeClipboard(String text) {
    try {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm == null) return;
      cm.setPrimaryClip(ClipData.newPlainText("DSH", text));
    } catch (Exception e) {
      Log.w(TAG, "clipboard write failed", e);
    }
  }

  // ----- notifications -----------------------------------------------------

  private void createChannels() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    NotificationManager nm = getSystemService(NotificationManager.class);
    NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "手机桥状态", NotificationManager.IMPORTANCE_LOW);
    status.setDescription("DSH 与本机手机桥的连接状态");
    nm.createNotificationChannel(status);
    NotificationChannel agent = new NotificationChannel(CHANNEL_AGENT, "DSH 通知", NotificationManager.IMPORTANCE_DEFAULT);
    agent.setDescription("DSH 在手机上主动弹出的提醒");
    nm.createNotificationChannel(agent);
  }

  private Notification statusNotification(String text) {
    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder = new Notification.Builder(this, CHANNEL_STATUS);
    } else {
      builder = new Notification.Builder(this);
    }
    return builder
      .setContentTitle("DSH 手机桥")
      .setContentText(text)
      .setSmallIcon(R.drawable.ic_stat_dsh)
      .setOngoing(true)
      .build();
  }

  private void updateStatus(String text) {
    status = text;
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null) nm.notify(STATUS_NOTIFICATION_ID, statusNotification(text));
  }

  private void postAgentNotification(String title, String text) {
    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      builder = new Notification.Builder(this, CHANNEL_AGENT);
    } else {
      builder = new Notification.Builder(this);
    }
    Notification notification = builder
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(R.drawable.ic_stat_dsh)
      .build();
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null) nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), notification);
  }
}
