package top.frankfurtlin.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the endpoint logic every login/health/logout URL is built from.
 * Runs with {@code ./gradlew testDebugUnitTest} (no device or emulator needed).
 */
public class EndpointTest {

  private static Endpoint ok(String raw) {
    Endpoint e = Endpoint.parse(raw);
    assertNotNull("expected a valid endpoint for: " + raw, e);
    return e;
  }

  @Test public void rejectsUnusableInput() {
    assertNull(Endpoint.parse(null));
    assertNull(Endpoint.parse(""));
    assertNull(Endpoint.parse("   "));
    assertNull(Endpoint.parse("https://"));               // no host
    assertNull(Endpoint.parse("not a url"));              // space => not a URI
    assertNull(Endpoint.parse("ftp://host"));             // wrong scheme
    assertNull(Endpoint.parse("mailto:me@example.com"));  // scheme without //
    assertNull(Endpoint.parse("javascript:alert(1)"));    // must not become https://javascript
    assertNull(Endpoint.parse("https://host:99999"));     // port out of range
    assertNull(Endpoint.parse("https://host:0"));
  }

  @Test public void defaultsToHttpsAndStripsPaths() {
    Endpoint bare = ok("example.com");
    assertEquals("https://example.com", bare.base);
    assertTrue(bare.secure);
    assertEquals("example.com", bare.host);

    Endpoint messy = ok("  https://example.com/  ");
    assertEquals("https://example.com", messy.base);

    Endpoint deep = ok("https://example.com/some/path?x=1#frag");
    assertEquals("https://example.com", deep.base);
  }

  @Test public void keepsExplicitSchemeAndPort() {
    Endpoint http = ok("http://192.168.1.5:8443/path?x=1#f");
    assertEquals("http://192.168.1.5:8443", http.base);
    assertFalse(http.secure);

    assertEquals("https://localhost:3080", ok("localhost:3080").base);   // host:port, no scheme
    assertEquals("https://[::1]:8443", ok("https://[::1]:8443/x").base); // ipv6 literal
    assertEquals("https://host", ok("https://user:pass@host/x").base);   // userinfo dropped
  }

  @Test public void acceptsInternationalHostnames() {
    Endpoint idn = ok("https://例子.测试");
    assertEquals("例子.测试", idn.host);
    assertEquals("https://例子.测试", idn.base);
  }

  @Test public void buildsGatewayUrls() {
    Endpoint e = ok("https://example.com");
    assertEquals("https://example.com/gateway/login?next=%2F", e.loginUrl("/"));
    assertEquals("https://example.com/gateway/login?next=%2F", e.loginUrl(null));
    assertEquals("https://example.com/gateway/login?next=%2Fx%20y%3Fz%3D1", e.loginUrl("/x y?z=1"));
    assertEquals("https://example.com/gateway/healthz", e.healthUrl());
    assertEquals("https://example.com/gateway/readyz", e.readyUrl());
    assertEquals("https://example.com/gateway/logout", e.logoutUrl());
  }

  @Test public void mapsBridgeUrlToWebSocketScheme() {
    assertEquals("wss://example.com/bridge/ws", ok("https://example.com").bridgeUrl());
    assertEquals("ws://192.168.1.5:8443/bridge/ws", ok("http://192.168.1.5:8443").bridgeUrl());
  }

  @Test public void ownsOnlyItsOwnHost() {
    Endpoint e = ok("https://example.com");
    assertTrue(e.owns("https://example.com/"));
    assertTrue(e.owns("https://EXAMPLE.com/chat"));
    assertFalse(e.owns("https://evil.example.com/"));
    assertFalse(e.owns("https://sub.example.com/"));
    assertFalse(e.owns(null));
  }

  @Test public void keepsNonHttpUrlsInsideTheWebView() {
    Endpoint e = ok("https://example.com");
    assertTrue(e.owns("about:blank"));
    assertTrue(e.owns("javascript:void(0)"));
    assertTrue(e.owns("data:text/plain,hi"));
    assertTrue(e.owns("/relative/path"));   // no host => same document
  }

  @Test public void percentEncodesQueryValues() {
    assertEquals("%2F", Endpoint.percentEncode("/"));
    assertEquals("abc-._~", Endpoint.percentEncode("abc-._~"));
    assertEquals("a%20b", Endpoint.percentEncode("a b"));
    assertEquals("%E4%B8%AD", Endpoint.percentEncode("中"));
  }
}
