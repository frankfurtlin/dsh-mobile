package top.frankfurtlin.dshmobile;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Normalizes and validates the server address the user types in.
 *
 * Pure JDK code on purpose (no {@code android.net.Uri}): the whole login flow
 * hangs off these URLs, so they are covered by JVM unit tests
 * ({@code app/src/test/java/top/frankfurtlin/dshmobile/EndpointTest.java}).
 */
public final class Endpoint {
  public final String base;      // e.g. https://dsh.example.com  (no trailing slash)
  public final boolean secure;   // https
  public final String host;

  private Endpoint(String base, boolean secure, String host) {
    this.base = base;
    this.secure = secure;
    this.host = host;
  }

  /** `scheme:rest` for inputs typed without `//` (localhost:3080, javascript:alert(1)). */
  private static final java.util.regex.Pattern SCHEME_LIKE =
      java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*):(.*)$");
  /** Host characters: ASCII hostnames, IPv6 brackets and IDN labels. */
  private static final java.util.regex.Pattern HOSTISH =
      java.util.regex.Pattern.compile("^\\[?[\\p{L}\\p{N}._:\\-]+]?$");

  /** Returns null when the input cannot be understood as a server address. */
  public static Endpoint parse(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    if (!s.contains("://")) {
      java.util.regex.Matcher m = SCHEME_LIKE.matcher(s);
      // `localhost:3080` is host:port, but `javascript:alert(1)` is a scheme we must refuse.
      if (m.matches() && !m.group(2).matches("\\d.*")) return null;
      s = "https://" + s;                                // default to https
    }

    URI uri;
    try {
      uri = new URI(s);
    } catch (URISyntaxException e) {
      return null;
    }
    String scheme = uri.getScheme();
    if (scheme == null) return null;
    scheme = scheme.toLowerCase();
    if (!scheme.equals("https") && !scheme.equals("http")) return null;
    String host = authorityHost(uri);
    if (host == null || !HOSTISH.matcher(host).matches()) return null;
    int port = uri.getPort();
    if (port != -1 && (port < 1 || port > 65535)) return null;

    String base = scheme + "://" + host + (port != -1 ? ":" + port : "");
    return new Endpoint(base, scheme.equals("https"), host);
  }

  /**
   * {@link URI#getHost()} is null for hosts Java refuses to canonicalise
   * (non-ASCII/IDN names). Fall back to the raw authority so those still work.
   */
  private static String authorityHost(URI uri) {
    String host = uri.getHost();
    if (host != null) return host;
    String authority = uri.getRawAuthority();
    if (authority == null) return null;
    int at = authority.lastIndexOf('@');
    if (at >= 0) authority = authority.substring(at + 1);
    if (authority.startsWith("[")) {                       // [ipv6]:port
      int end = authority.indexOf(']');
      return end > 0 ? authority.substring(0, end + 1) : null;
    }
    int colon = authority.indexOf(':');
    return colon >= 0 ? authority.substring(0, colon) : authority;
  }

  /** Browser-style normalization used for display and comparisons. */
  public String display() {
    return base;
  }

  /** The DSH password-gateway login page; `next` is where the gateway returns after login. */
  public String loginUrl(String next) {
    return base + "/gateway/login?next=" + percentEncode(next == null ? "/" : next);
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
    URI u;
    try {
      u = new URI(url);
    } catch (URISyntaxException e) {
      return true; // unparseable (javascript:, about:, data: …) stays internal
    }
    String h = u.getHost();
    if (h == null) h = authorityHost(u);
    if (h == null) return true; // about:blank, data:, javascript:
    return h.equalsIgnoreCase(host);
  }

  /** RFC 3986 percent-encoding for a query value (matches what browsers send). */
  static String percentEncode(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~';
      if (unreserved) {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
           .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return out.toString();
  }
}
