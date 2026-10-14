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
  private static final String K_A11Y = "a11y_enabled";
  private static final String K_FILES_ROOT = "files_root";

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

  /** Local switch: even with the system service on, the agent only automates when this is on. */
  public boolean a11yEnabled() {
    return prefs.getBoolean(K_A11Y, false);
  }

  public void setA11yEnabled(boolean v) {
    prefs.edit().putBoolean(K_A11Y, v).apply();
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

  /**
   * Folder the user handed to the agent through the system folder picker.
   *
   * @return the persisted tree URI, or {@code null} when the user has not picked one yet
   */
  public String getFilesRoot() {
    return prefs.getString(K_FILES_ROOT, null);
  }

  /**
   * @param uri tree URI granted with a persistable permission, or {@code null} to forget it
   */
  public void setFilesRoot(String uri) {
    if (uri == null) {
      prefs.edit().remove(K_FILES_ROOT).apply();
    } else {
      prefs.edit().putString(K_FILES_ROOT, uri).apply();
    }
  }

  public void clear() {
    prefs.edit().remove(K_ENDPOINT).remove(K_LAST_URL).apply();
  }
}
