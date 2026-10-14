package top.frankfurtlin.dshmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(STATUS_NOTIFICATION_ID, status, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    } else {
      startForeground(STATUS_NOTIFICATION_ID, status);
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
  private static final String[] CAPS = { "ping", "info", "shares", "clipboard.get", "clipboard.set", "notify", "open", "files.save", "files.open" };

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
    int dot = name.lastIndexOf('.');
    String ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    return mime == null ? "application/octet-stream" : mime;
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
