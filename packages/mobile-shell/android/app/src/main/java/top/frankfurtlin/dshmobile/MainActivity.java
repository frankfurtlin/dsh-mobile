package top.frankfurtlin.dshmobile;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
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

  // ----- credential handling ----------------------------------------------

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

  /** Copy the gateway token out of the cookie jar (HttpOnly cookies are readable here). */
  private void captureToken(String url) {
    if (endpoint == null) return;
    String jar = cookieJar();
    if (jar == null) return;
    String token = null;
    for (String part : jar.split(";")) {
      String kv = part.trim();
      int eq = kv.indexOf('=');
      if (eq <= 0) continue;
      if (COOKIE_NAME.equals(kv.substring(0, eq).trim())) {
        token = kv.substring(eq + 1).trim();
        break;
      }
    }
    if (token != null && !token.isEmpty()) {
      String known = creds.load(endpoint.host);
      if (!token.equals(known)) {
        Log.i(TAG, "captured gateway token for " + endpoint.host);
        creds.save(endpoint.host, token);
      }
    }
  }

  private void onUnauthorized() {
    creds.clear();
    CookieManager cm = CookieManager.getInstance();
    cm.setCookie(endpoint.base, COOKIE_NAME + "=; Path=/; Max-Age=0");
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush();
    settings.setLastUrl(null);
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
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_refresh) {
      showWeb();
      web.reload();
      return true;
    }
    if (id == R.id.action_change_server) {
      startActivity(new Intent(this, SetupActivity.class));
      return true;
    }
    if (id == R.id.action_logout) {
      creds.clear();
      settings.setLastUrl(null);
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
  }
}
