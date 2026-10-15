package top.frankfurtlin.dshmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the official DSH web UI against a user-supplied server.
 *
 * Auth flow: the WebView loads the server root; the dsh-passwords gateway answers with a 302 to
 * /gateway/login when there is no session. The login form is the real one, so password policy,
 * rate limiting and CSRF keep working. On success the gateway sets the `dsh_gateway_token`
 * HttpOnly cookie; we copy it into encrypted storage so the next cold start can seed the cookie
 * jar before the first request — that is what makes "open the app and you are already logged in"
 * work.
 */
public class MainActivity extends AppCompatActivity {
  private static final String TAG = "MainActivity";
  public static final String COOKIE_NAME = "dsh_gateway_token";
  private static final int REQ_FILE_CHOOSER = 1001;
  private static final int REQ_CAMERA = 1002;
  private static final int REQ_NOTIFICATIONS = 1003;
  private static final int REQ_PICK_TREE = 1004;

  private WebView web;
  private ProgressBar progress;
  private View errorView;
  private TextView errorText;
  private Button errorRetry;
  private Button errorSettings;

  private Settings settings;
  private CredentialStore creds;
  private Endpoint endpoint;
  private final ExecutorService io = Executors.newSingleThreadExecutor();

  private ValueCallback<Uri[]> filePathCallback;
  private Uri cameraUri;
  private boolean keepAwake;
  private boolean bridgeRunning;
  /** Why the bridge refused to start, if it did; surfaced in the 手机桥 dialog instead of a silent return. */
  private String bridgeError;
  private boolean tokenChanged;
  private String pendingLoginNext = "/";
  private long backPressedAt = 0L;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    settings = new Settings(this);
    creds = new CredentialStore(this);

    endpoint = settings.getEndpoint();
    if (endpoint == null) {
      startActivity(new Intent(this, SetupActivity.class));
      finish();
      return;
    }

    setContentView(R.layout.activity_main);
    androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle("DSH");
      getSupportActionBar().setSubtitle(endpoint.host);
    }

    applySystemBars();
    requestNotificationPermissionIfNeeded();

    progress = findViewById(R.id.progress);
    errorView = findViewById(R.id.error_view);
    errorText = findViewById(R.id.error_text);
    errorRetry = findViewById(R.id.error_retry);
    errorSettings = findViewById(R.id.error_settings);

    errorRetry.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        showWeb();
        web.reload();
      }
    });
    errorSettings.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        startActivity(new Intent(MainActivity.this, SetupActivity.class));
      }
    });

    configureWebView();

    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    cm.setAcceptThirdPartyCookies(web, true);

    String token = creds.load(endpoint.host);
    if (token != null) seedCookie(token);

    String start = settings.getLastUrl();
    if (start == null || !endpoint.owns(start)) start = endpoint.base + "/";
    web.loadUrl(start);
    startBridge(false);
  }

  // ----- WebView setup -----------------------------------------------------

  private void configureWebView() {
    web = findViewById(R.id.webview);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setUseWideViewPort(false);
    s.setLoadWithOverviewMode(false);
    s.setSupportZoom(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    s.setUserAgentString(s.getUserAgentString() + " DSHMobile/1.0");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    }

    web.addJavascriptInterface(new JsBridge(), "DshMobile");

    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (isExternal(url)) {
          openExternal(url);
          return true;
        }
        return false;
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        progress.setVisibility(View.GONE);
        settings.setLastUrl(url);
        captureToken(url);
        if (tokenChanged) {
          tokenChanged = false;
          startBridge(true);
        }
      }

      @Override
      public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
        if (!req.isForMainFrame()) return;
        int code = resp.getStatusCode();
        if (code == 401 || code == 403) {
          Log.i(TAG, "main frame returned " + code + " -> back to login");
          onUnauthorized();
        }
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
        if (!req.isForMainFrame()) return;
        showError(getString(R.string.error_load_failed, String.valueOf(err.getErrorCode()),
            String.valueOf(err.getDescription())));
      }

      @Override
      public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
        if (settings.allowInsecure()) {
          Log.w(TAG, "accepting SSL error because allowInsecure=on: " + error);
          handler.proceed();
        } else {
          handler.cancel();
          showError(getString(R.string.error_ssl));
        }
      }
    });

    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onProgressChanged(WebView view, int newProgress) {
        progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
        progress.setProgress(newProgress);
      }

      @Override
      public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
        filePathCallback = cb;
        if (wantsCamera(params)) {
          try {
            cameraUri = createCaptureUri();
            Intent take = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            take.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            take.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(take, REQ_CAMERA);
            return true;
          } catch (Exception e) {
            Log.w(TAG, "camera capture unavailable, falling back to the file picker", e);
            cameraUri = null;
          }
        }
        try {
          Intent intent = params.createIntent();
          intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
          startActivityForResult(intent, REQ_FILE_CHOOSER);
          return true;
        } catch (ActivityNotFoundException e) {
          filePathCallback = null;
          Toast.makeText(MainActivity.this, R.string.error_no_file_picker, Toast.LENGTH_SHORT).show();
          return false;
        }
      }
    });

    web.setDownloadListener(new DownloadListener() {
      @Override
      public void onDownloadStart(String url, String userAgent, String disposition,
                                  String mimeType, long contentLength) {
        try {
          DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
          String cookies = CookieManager.getInstance().getCookie(url);
          if (cookies != null) req.addRequestHeader("Cookie", cookies);
          if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
          req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
          req.setMimeType(mimeType);
          req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
              "DSH/" + guessFileName(url, disposition, mimeType));
          req.allowScanningByMediaScanner();
          DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
          if (dm != null) {
            dm.enqueue(req);
            Toast.makeText(MainActivity.this, R.string.download_started, Toast.LENGTH_SHORT).show();
          }
        } catch (Exception e) {
          Log.w(TAG, "download failed", e);
          Toast.makeText(MainActivity.this, R.string.error_download, Toast.LENGTH_SHORT).show();
        }
      }
    });
  }

  // ----- phone capabilities ------------------------------------------------

  /** True when the page asked for a camera capture (e.g. <input type="file" capture accept="image/*">). */
  private static boolean wantsCamera(WebChromeClient.FileChooserParams params) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
    if (!params.isCaptureEnabled()) return false;
    String[] accept = params.getAcceptTypes();
    if (accept == null || accept.length == 0) return false;
    for (String t : accept) {
      if (t != null && (t.startsWith("image/") || t.equals("*/*"))) return true;
    }
    return false;
  }

  private Uri createCaptureUri() throws Exception {
    File dir = new File(getCacheDir(), "images");
    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("no image dir");
    File f = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
    return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
  }

  /**
   * Edge-to-edge with hand-applied insets: the toolbar keeps clear of the status bar / display
   * cutout and the page keeps clear of the gesture bar, instead of hiding content behind them.
   */
  /** Offer the agent a folder: pick one, see the current one, or forget it. */
  private void showFilesDialog() {
    final String current = settings.getFilesRoot();
    AlertDialog.Builder builder = new AlertDialog.Builder(this)
      .setTitle(R.string.action_pick_folder)
      .setMessage(current == null
        ? getString(R.string.folder_none)
        : getString(R.string.folder_current, folderLabel(current)))
      .setPositiveButton(R.string.folder_pick, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          launchFolderPicker();
        }
      })
      .setNegativeButton(R.string.a11y_close, null);
    if (current != null) {
      builder.setNeutralButton(R.string.folder_clear, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          settings.setFilesRoot(null);
          Toast.makeText(MainActivity.this, R.string.folder_cleared, Toast.LENGTH_SHORT).show();
        }
      });
    }
    builder.show();
  }

  private void launchFolderPicker() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
      | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    try {
      startActivityForResult(intent, REQ_PICK_TREE);
    } catch (Exception e) {
      Log.w(TAG, "no folder picker on this device", e);
      Toast.makeText(this, R.string.folder_pick_failed, Toast.LENGTH_LONG).show();
    }
  }

  /** The system picker always returns a tree URI; keep the grant across restarts. */
  private void onFolderPicked(int resultCode, Intent data) {
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
    Uri tree = data.getData();
    int granted = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    if (granted == 0) granted = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    try {
      getContentResolver().takePersistableUriPermission(tree, granted);
      settings.setFilesRoot(tree.toString());
      Toast.makeText(this, getString(R.string.folder_picked, folderLabel(tree.toString())), Toast.LENGTH_LONG).show();
    } catch (Exception e) {
      Log.w(TAG, "cannot keep the folder grant", e);
      Toast.makeText(this, R.string.folder_pick_failed, Toast.LENGTH_LONG).show();
    }
  }

  private static String folderLabel(String treeUri) {
    try {
      String id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(treeUri));
      int colon = id.indexOf(':');
      String tail = colon >= 0 ? id.substring(colon + 1) : id;
      int slash = tail.lastIndexOf('/');
      String name = slash >= 0 ? tail.substring(slash + 1) : tail;
      return name.isEmpty() ? tail : name;
    } catch (Exception e) {
      return treeUri;
    }
  }

  /** Accessibility automation needs both the system service and this app's own switch. */
  private void showA11yDialog() {
    final boolean serviceOn = A11yService.ready();
    final boolean allowed = settings.a11yEnabled();
    String status = serviceOn ? getString(R.string.a11y_status_on) : getString(R.string.a11y_status_off);
    String toggle = allowed ? getString(R.string.a11y_allow_off) : getString(R.string.a11y_allow_on);
    new AlertDialog.Builder(this)
      .setTitle(R.string.a11y_dialog_title)
      .setMessage(status + "\n\n" + getString(R.string.a11y_help))
      .setItems(new String[] { toggle, getString(R.string.a11y_open_settings) }, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          if (which == 0) {
            settings.setA11yEnabled(!allowed);
            Toast.makeText(MainActivity.this, !allowed ? R.string.a11y_allow_on : R.string.a11y_allow_off, Toast.LENGTH_SHORT).show();
            return;
          }
          try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
          } catch (Exception e) {
            Log.w(TAG, "cannot open accessibility settings", e);
          }
        }
      })
      .setNegativeButton(R.string.a11y_close, null)
      .show();
  }

  /**
   * Phone-bridge state plus the two things that decide whether it connects: the foreground
   * service and the credential the service authenticates with. Both used to fail without a word.
   */
  private void showBridgeDialog() {
    String token = endpoint == null ? null : creds.load(endpoint.host);
    StringBuilder message = new StringBuilder();
    message.append(getString(R.string.bridge_status, BridgeService.status));
    if (endpoint != null) message.append("\n").append(getString(R.string.bridge_server, endpoint.host));
    message.append("\n").append(token == null || token.isEmpty()
        ? getString(R.string.bridge_cred_missing)
        : getString(R.string.bridge_cred_ok));
    message.append("\n").append(webToken() == null
        ? getString(R.string.bridge_cookie_missing)
        : getString(R.string.bridge_cookie_ok));
    if (bridgeError != null) message.append("\n\n").append(bridgeError);
    if (CredentialStore.lastError != null) {
      message.append("\n").append(getString(R.string.bridge_store_error, CredentialStore.lastError));
    }
    message.append("\n\n").append(getString(R.string.bridge_help));

    final boolean running = bridgeRunning;
    // Buttons rather than a list: the message is long, and a list item can end up scrolled out of
    // an alert dialog's content area, which is exactly how 「重新获取凭证」 became unreachable.
    new AlertDialog.Builder(this)
      .setTitle(R.string.bridge_dialog_title)
      .setMessage(message.toString())
      .setPositiveButton(running ? R.string.bridge_stop : R.string.bridge_start, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          if (running) {
            stopBridge();
            Toast.makeText(MainActivity.this, R.string.bridge_stopped, Toast.LENGTH_SHORT).show();
            return;
          }
          startBridge(true);
          if (bridgeRunning) {
            Toast.makeText(MainActivity.this, R.string.bridge_started, Toast.LENGTH_SHORT).show();
          } else if (bridgeError != null) {
            Toast.makeText(MainActivity.this, bridgeError, Toast.LENGTH_LONG).show();
          }
        }
      })
      .setNeutralButton(R.string.bridge_recapture, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          recaptureCredential();
        }
      })
      .setNegativeButton(R.string.a11y_close, null)
      .show();
  }

  /**
   * Re-seeds the credential from the WebView cookie jar.
   *
   * The WebView and {@link CredentialStore} keep separate copies of the session token, so the app
   * can look logged in (page loads fine) while the bridge has nothing to authenticate with.
   */
  private void recaptureCredential() {
    if (endpoint == null) return;
    captureToken(web == null ? null : web.getUrl());
    if (tokenChanged) {
      tokenChanged = false;
      startBridge(true);
      Toast.makeText(this, R.string.bridge_recaptured, Toast.LENGTH_SHORT).show();
      return;
    }
    String token = creds.load(endpoint.host);
    boolean present = token != null && !token.isEmpty();
    Toast.makeText(this, present ? R.string.bridge_cred_ok : R.string.bridge_recapture_failed,
        Toast.LENGTH_LONG).show();
  }

  private void applySystemBars() {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    final View toolbar = findViewById(R.id.toolbar);
    final View container = findViewById(R.id.web_container);
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
        new androidx.core.view.OnApplyWindowInsetsListener() {
          @Override
          public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
            Insets bar = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
            toolbar.setPadding(toolbar.getPaddingLeft(), bar.top, toolbar.getPaddingRight(),
                toolbar.getPaddingBottom());
            container.setPadding(0, 0, 0, bar.bottom);
            return insets;
          }
        });
  }

  private void requestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      return;
    }
    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
  }

  /** Long streaming answers are easy to lose to screen timeout; the user opts in explicitly. */
  private void setKeepAwake(boolean on) {
    keepAwake = on;
    if (on) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      Toast.makeText(this, R.string.keep_awake_on, Toast.LENGTH_SHORT).show();
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      Toast.makeText(this, R.string.keep_awake_off, Toast.LENGTH_SHORT).show();
    }
  }

  private void showInbox() {
    final List<Inbox.Item> items = Inbox.list(this);
    if (items.isEmpty()) {
      Toast.makeText(this, R.string.inbox_empty, Toast.LENGTH_SHORT).show();
      return;
    }
    String[] labels = new String[items.size()];
    for (int i = 0; i < items.size(); i++) {
      Inbox.Item it = items.get(i);
      labels[i] = it.name + (it.mime == null || it.mime.isEmpty() ? "" : " — " + it.mime);
    }
    new AlertDialog.Builder(this)
        .setTitle(R.string.inbox_title)
        .setItems(labels, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            openInboxItem(items.get(which));
          }
        })
        .setNegativeButton(R.string.inbox_clear, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            Inbox.clear(MainActivity.this);
            invalidateOptionsMenu();
          }
        })
        .show();
  }

  private void openInboxItem(Inbox.Item item) {
    if (item.text != null) {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dsh", item.text));
      Toast.makeText(this, R.string.inbox_copied, Toast.LENGTH_SHORT).show();
      return;
    }
    if (item.path == null) return;
    File f = new File(item.path);
    Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
    Intent i = new Intent(Intent.ACTION_VIEW);
    i.setDataAndType(uri, item.mime == null || item.mime.isEmpty() ? "*/*" : item.mime);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      startActivity(i);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, R.string.error_open_file, Toast.LENGTH_SHORT).show();
    }
  }

  // ----- credential handling ----------------------------------------------

  /**
   * Starts (or restarts after a token change) the bridge connection the agent uses to reach this phone.
   *
   * Every refusal records why in {@link #bridgeError}. A silent return here is indistinguishable
   * from a foreground service that died, which is what made "the app works but /healthz says no
   * phone" impossible to diagnose from inside the app.
   */
  private void startBridge(boolean restart) {
    if (endpoint == null) return; // MainActivity finishes without an endpoint; nothing to report
    String token = creds.load(endpoint.host);
    if (token == null || token.isEmpty()) { // not logged in yet
      bridgeError = getString(R.string.bridge_no_credential);
      return;
    }
    if (bridgeRunning && !restart) return;
    Intent intent = new Intent(this, BridgeService.class);
    intent.setAction(BridgeService.ACTION_START);
    try {
      androidx.core.content.ContextCompat.startForegroundService(this, intent);
      bridgeRunning = true;
      bridgeError = null;
    } catch (Exception e) {
      bridgeError = getString(R.string.bridge_start_failed, String.valueOf(e.getMessage()));
      Toast.makeText(this, bridgeError, Toast.LENGTH_LONG).show();
      Log.w(TAG, "bridge start failed", e);
    }
  }

  private void stopBridge() {
    bridgeRunning = false;
    try {
      startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
    } catch (Exception e) {
      Log.w(TAG, "bridge stop failed", e);
    }
  }

  private void seedCookie(String token) {
    CookieManager cm = CookieManager.getInstance();
    cm.setCookie(endpoint.base, COOKIE_NAME + "=" + token + "; Path=/");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush();
  }

  /**
   * Cookies visible for this server. The gateway scopes its CSRF cookie to /gateway, while the
   * session token is scoped to /, so both prefixes have to be asked for explicitly.
   */
  private String cookieJar() {
    CookieManager cm = CookieManager.getInstance();
    String root = cm.getCookie(endpoint.base);
    String gateway = cm.getCookie(endpoint.base + "/gateway/");
    if (root == null) return gateway;
    if (gateway == null) return root;
    return root + "; " + gateway;
  }

  /**
   * The gateway token exactly as the WebView's cookie jar has it, or null when the jar is empty.
   *
   * HttpOnly cookies are readable through {@link CookieManager}, which is what makes "copy the
   * session out of the browser" possible at all.
   *
   * @return the token, or null
   */
  private String webToken() {
    if (endpoint == null) return null;
    String jar = cookieJar();
    if (jar == null) return null;
    for (String part : jar.split(";")) {
      String kv = part.trim();
      int eq = kv.indexOf('=');
      if (eq <= 0) continue;
      if (COOKIE_NAME.equals(kv.substring(0, eq).trim())) {
        String token = kv.substring(eq + 1).trim();
        return token.isEmpty() ? null : token;
      }
    }
    return null;
  }

  /** Copy the gateway token out of the cookie jar into encrypted storage. */
  private void captureToken(String url) {
    if (endpoint == null) return;
    String token = webToken();
    if (token == null) return;
    String known = creds.load(endpoint.host);
    if (!token.equals(known)) {
      Log.i(TAG, "captured gateway token for " + endpoint.host);
      creds.save(endpoint.host, token);
      tokenChanged = true;
    }
  }

  private void onUnauthorized() {
    creds.clear();
    CookieManager cm = CookieManager.getInstance();
    cm.setCookie(endpoint.base, COOKIE_NAME + "=; Path=/; Max-Age=0");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush();
    settings.setLastUrl(null);
    stopBridge();
    showWeb();
    web.loadUrl(endpoint.loginUrl(pendingLoginNext));
  }

  /** Best effort: ask the server to revoke the token too. Local state is cleared either way. */
  private void requestServerLogout() {
    final String url = endpoint.logoutUrl();
    io.execute(new Runnable() {
      @Override
      public void run() {
        HttpURLConnection c = null;
        try {
          final String jar = cookieJar();
          c = (HttpURLConnection) new URL(url).openConnection();
          c.setRequestMethod("POST");
          c.setConnectTimeout(8000);
          c.setReadTimeout(8000);
          c.setDoOutput(true);
          if (jar != null) c.setRequestProperty("Cookie", jar);
          // The gateway rejects cross-origin writes; a same-origin header keeps it happy.
          c.setRequestProperty("Origin", endpoint.base);
          c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
          String csrf = findCsrf(jar);
          OutputStream os = c.getOutputStream();
          PrintWriter pw = new PrintWriter(os);
          pw.print("csrf=" + (csrf == null ? "" : csrf));
          pw.flush();
          pw.close();
          Log.i(TAG, "server logout -> " + c.getResponseCode());
        } catch (Exception e) {
          Log.w(TAG, "server logout failed (local state already cleared)", e);
        } finally {
          if (c != null) c.disconnect();
        }
      }
    });
  }

  private static String findCsrf(String jar) {
    if (jar == null) return null;
    for (String part : jar.split(";")) {
      String kv = part.trim();
      int eq = kv.indexOf('=');
      if (eq <= 0) continue;
      if (kv.substring(0, eq).trim().toLowerCase().contains("csrf")) return kv.substring(eq + 1).trim();
    }
    return null;
  }

  // ----- UI helpers --------------------------------------------------------

  private void showError(String msg) {
    errorText.setText(msg);
    errorView.setVisibility(View.VISIBLE);
    web.setVisibility(View.GONE);
    progress.setVisibility(View.GONE);
  }

  private void showWeb() {
    errorView.setVisibility(View.GONE);
    web.setVisibility(View.VISIBLE);
  }

  private boolean isExternal(String url) {
    if (url == null) return false;
    String lower = url.toLowerCase();
    if (lower.startsWith("intent://") || lower.startsWith("market://") || lower.startsWith("tel:")
        || lower.startsWith("mailto:") || lower.startsWith("geo:") || lower.startsWith("sms:")) {
      return true;
    }
    if (endpoint == null) return false;
    return !endpoint.owns(url);
  }

  private void openExternal(String url) {
    try {
      Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      i.addCategory(Intent.CATEGORY_BROWSABLE);
      startActivity(i);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, R.string.error_no_browser, Toast.LENGTH_SHORT).show();
    }
  }

  private static String guessFileName(String url, String disposition, String mimeType) {
    String name = null;
    if (disposition != null) {
      int i = disposition.indexOf("filename=");
      if (i >= 0) name = disposition.substring(i + 9).replace("\"", "").trim();
    }
    if (name == null) name = Uri.parse(url).getLastPathSegment();
    if (name == null || name.isEmpty()) name = "download";
    return name.replace('/', '_');
  }

  // ----- menu / keys / lifecycle ------------------------------------------

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem inbox = menu.findItem(R.id.action_inbox);
    if (inbox != null) {
      int n = Inbox.list(this).size();
      inbox.setVisible(n > 0);
      inbox.setTitle(getString(R.string.action_inbox, String.valueOf(n)));
    }
    MenuItem awake = menu.findItem(R.id.action_keep_awake);
    if (awake != null) awake.setChecked(keepAwake);
    MenuItem bridge = menu.findItem(R.id.action_bridge);
    // The service status is the only in-app signal that the agent's side of the bridge is alive.
    if (bridge != null) bridge.setTitle(getString(R.string.action_bridge) + " · " + BridgeService.status);
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_refresh) {
      showWeb();
      web.reload();
      return true;
    }
    if (id == R.id.action_keep_awake) {
      setKeepAwake(!keepAwake);
      item.setChecked(keepAwake);
      return true;
    }
    if (id == R.id.action_inbox) {
      showInbox();
      return true;
    }
    if (id == R.id.action_bridge) {
      showBridgeDialog();
      return true;
    }
    if (id == R.id.action_a11y) {
      showA11yDialog();
      return true;
    }
    if (id == R.id.action_pick_folder) {
      showFilesDialog();
      return true;
    }
    if (id == R.id.action_change_server) {
      startActivity(new Intent(this, SetupActivity.class));
      return true;
    }
    if (id == R.id.action_logout) {
      creds.clear();
      settings.setLastUrl(null);
      stopBridge();
      requestServerLogout();
      CookieManager cm = CookieManager.getInstance();
      cm.setCookie(endpoint.base, COOKIE_NAME + "=; Path=/; Max-Age=0");
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush();
      showWeb();
      web.loadUrl(endpoint.loginUrl("/"));
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
      web.goBack();
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      if (System.currentTimeMillis() - backPressedAt < 2000) return super.onKeyDown(keyCode, event);
      backPressedAt = System.currentTimeMillis();
      Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_PICK_TREE) {
      onFolderPicked(resultCode, data);
      return;
    }
    if (requestCode == REQ_CAMERA && filePathCallback != null) {
      Uri[] res = (resultCode == Activity.RESULT_OK && cameraUri != null) ? new Uri[]{cameraUri} : null;
      filePathCallback.onReceiveValue(res);
      filePathCallback = null;
      cameraUri = null;
      return;
    }
    if (requestCode != REQ_FILE_CHOOSER || filePathCallback == null) return;
    Uri[] results = null;
    if (resultCode == Activity.RESULT_OK && data != null) {
      ClipData clip = data.getClipData();
      if (clip != null) {
        results = new Uri[clip.getItemCount()];
        for (int i = 0; i < clip.getItemCount(); i++) results[i] = clip.getItemAt(i).getUri();
      } else if (data.getData() != null) {
        results = new Uri[]{data.getData()};
      }
    }
    filePathCallback.onReceiveValue(results);
    filePathCallback = null;
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (web != null) {
      web.onPause();
      captureToken(web.getUrl());
    }
    CookieManager.getInstance().flush();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (web != null) web.onResume();
    // A share from another app lands in the inbox while we were backgrounded.
    invalidateOptionsMenu();
  }

  @Override
  protected void onDestroy() {
    if (web != null) {
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }

  /** Minimal native surface exposed to the web app; grows in later phases. */
  private final class JsBridge {
    @JavascriptInterface
    public String getEndpoint() {
      return endpoint == null ? "" : endpoint.base;
    }

    @JavascriptInterface
    public String getAppVersion() {
      try {
        return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
      } catch (Exception e) {
        return "unknown";
      }
    }

    @JavascriptInterface
    public void openExternal(String url) {
      MainActivity.this.openExternal(url);
    }

    @JavascriptInterface
    public void copyText(String text) {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("dsh", text));
    }

    /** Read the system clipboard, so a paste button in the page can offer it. */
    @JavascriptInterface
    public String getClipboard() {
      ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (cm == null || cm.getPrimaryClip() == null) return "";
      ClipData data = cm.getPrimaryClip();
      if (data.getItemCount() == 0) return "";
      CharSequence text = data.getItemAt(0).getText();
      return text == null ? "" : text.toString();
    }

    /** Keep the screen on during long streaming answers; the bridge thread must hop to the UI one. */
    /** Saves base64 content into the phone's public Downloads/DSH folder. Returns JSON with uri/size. */
    @JavascriptInterface
    public String saveBase64(final String name, final String mime, final String base64) {
      try {
        byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        Files.Saved saved = Files.save(MainActivity.this, name, mime, "downloads", bytes);
        return saved.toJson().toString();
      } catch (Exception e) {
        Log.w(TAG, "saveBase64 failed", e);
        return "{\"error\":\"" + e.getMessage() + "\"}";
      }
    }

    @JavascriptInterface
    public void setKeepAwake(final boolean on) {
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          MainActivity.this.setKeepAwake(on);
        }
      });
    }

    @JavascriptInterface
    public boolean keepAwake() {
      return MainActivity.this.keepAwake;
    }

    /** JSON array of items shared into the app from other apps. */
    @JavascriptInterface
    public String shares() {
      return Inbox.toJson(MainActivity.this);
    }

    /** Folder the agent may browse, or null when the user has not picked one. */
    @JavascriptInterface
    public String filesRoot() {
      return MainActivity.this.settings.getFilesRoot();
    }

    /** Show the system folder picker so the web page can offer a button. */
    @JavascriptInterface
    public void pickFilesRoot() {
      runOnUiThread(new Runnable() {
        @Override
        public void run() {
          launchFolderPicker();
        }
      });
    }

    /** Human readable name of the shared folder, or an empty string. */
    @JavascriptInterface
    public String filesRootLabel() {
      String root = MainActivity.this.settings.getFilesRoot();
      return root == null ? "" : folderLabel(root);
    }
  }
}
