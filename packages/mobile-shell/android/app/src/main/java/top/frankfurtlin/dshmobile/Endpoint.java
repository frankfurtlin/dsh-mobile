package top.frankfurtlin.dshmobile;

import android.net.Uri;

/** Normalizes and validates the server address the user types in. */
public final class Endpoint {
  public final String base;      // e.g. https://dsh.example.com  (no trailing slash)
  public final boolean secure;   // https
  public final String host;

  private Endpoint(String base, boolean secure, String host) {
    this.base = base;
    this.secure = secure;
    this.host = host;
  }

  /** Returns null when the input cannot be understood as a server address. */
  public static Endpoint parse(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    if (!s.contains("://")) s = "https://" + s;          // default to https
    Uri uri = Uri.parse(s);
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) return null;
    String host = uri.getHost();
    if (host == null || host.isEmpty()) return null;

    String base = scheme + "://" + host + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
    return new Endpoint(base, scheme.equals("https"), host);
  }

  /** Browser-style normalization used for display and comparisons. */
  public String display() {
    return base;
  }

  /** The DSH password-gateway login page; `next` is where the gateway returns after login. */
  public String loginUrl(String next) {
    return base + "/gateway/login?next=" + android.net.Uri.encode(next == null ? "/" : next);
  }

  public String healthUrl() {
    return base + "/gateway/healthz";
  }

  public String readyUrl() {
    return base + "/gateway/readyz";
  }

  public String logoutUrl() {
    return base + "/gateway/logout";
  }

  /** Phone WebSocket endpoint published by Caddy in front of the bridge. */
  public String bridgeUrl() {
    return base.replaceFirst("^http", "ws") + "/bridge/ws";
  }

  /** True when the URL belongs to this server (keep it inside the app WebView). */
  public boolean owns(String url) {
    if (url == null) return false;
    Uri u = Uri.parse(url);
    String h = u.getHost();
    if (h == null) return true; // about:blank, data:, javascript:
    return h.equalsIgnoreCase(host);
  }
}
