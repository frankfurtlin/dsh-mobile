package top.frankfurtlin.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret app settings: which server to use and how strict to be about TLS. */
public final class Settings {
  private static final String PREFS = "dsh_mobile_settings";
  private static final String K_ENDPOINT = "endpoint";
  private static final String K_ALLOW_INSECURE = "allow_insecure";
  private static final String K_LAST_URL = "last_url";
  private static final String K_DEVICE_ID = "device_id";

  private final SharedPreferences prefs;

  public Settings(Context ctx) {
    this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public String getEndpointRaw() {
    return prefs.getString(K_ENDPOINT, null);
  }

  public Endpoint getEndpoint() {
    return Endpoint.parse(getEndpointRaw());
  }

  public void setEndpoint(String raw) {
    prefs.edit().putString(K_ENDPOINT, raw).apply();
  }

  public boolean allowInsecure() {
    return prefs.getBoolean(K_ALLOW_INSECURE, false);
  }

  public void setAllowInsecure(boolean v) {
    prefs.edit().putBoolean(K_ALLOW_INSECURE, v).apply();
  }

  public String getLastUrl() {
    return prefs.getString(K_LAST_URL, null);
  }

  public void setLastUrl(String url) {
    prefs.edit().putString(K_LAST_URL, url).apply();
  }

  /** Stable identity this phone presents to the bridge; generated once. */
  public String getDeviceId() {
    String id = prefs.getString(K_DEVICE_ID, null);
    if (id == null) {
      id = java.util.UUID.randomUUID().toString();
      prefs.edit().putString(K_DEVICE_ID, id).apply();
    }
    return id;
  }

  public void clear() {
    prefs.edit().remove(K_ENDPOINT).remove(K_LAST_URL).apply();
  }
}
