package top.frankfurtlin.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stores the gateway session token encrypted at rest.
 *
 * The token itself is the `dsh_gateway_token` HttpOnly cookie issued by dsh-passwords.
 * It is a bearer credential: leaking it is equivalent to leaking the password, so it is
 * sealed with an AndroidKeyStore AES-GCM key that never leaves the device's secure hardware.
 */
public final class CredentialStore {
  private static final String KS = "AndroidKeyStore";
  private static final String ALIAS = "dsh_mobile_gateway_token";
  private static final String PREFS = "dsh_mobile_creds";
  private static final String K_TOKEN = "token";
  private static final String K_HOST = "host";
  private static final String K_SAVED_AT = "saved_at";
  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 12;

  private final SharedPreferences prefs;

  public CredentialStore(Context ctx) {
    this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  // ----- token -------------------------------------------------------------

  public synchronized void save(String host, String token) {
    try {
      String sealed = encrypt(token);
      if (sealed == null) return;
      prefs.edit()
          .putString(K_TOKEN, sealed)
          .putString(K_HOST, host)
          .putLong(K_SAVED_AT, System.currentTimeMillis())
          .apply();
    } catch (Exception e) {
      android.util.Log.w("CredentialStore", "save failed: " + e);
    }
  }

  /** Returns the saved token for `host`, or null when there is none / the host changed. */
  public String load(String host) {
    String sealed = prefs.getString(K_TOKEN, null);
    if (sealed == null) return null;
    String savedHost = prefs.getString(K_HOST, null);
    if (savedHost != null && host != null && !savedHost.equalsIgnoreCase(host)) return null;
    try {
      return decrypt(sealed);
    } catch (Exception e) {
      android.util.Log.w("CredentialStore", "decrypt failed, dropping: " + e);
      clear();
      return null;
    }
  }

  public long savedAt() {
    return prefs.getLong(K_SAVED_AT, 0L);
  }

  public synchronized void clear() {
    prefs.edit().remove(K_TOKEN).remove(K_HOST).remove(K_SAVED_AT).apply();
  }

  // ----- crypto ------------------------------------------------------------

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance(KS);
    ks.load(null);
    if (ks.containsAlias(ALIAS)) {
      KeyStore.Entry entry = ks.getEntry(ALIAS, null);
      if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }
    KeyGenerator kg = KeyGenerator.getInstance("AES", KS);
    kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes("GCM")
        .setEncryptionPaddings("NoPadding")
        .setKeySize(256)
        .build());
    return kg.generateKey();
  }

  private String encrypt(String plain) {
    try {
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      byte[] iv = new byte[IV_BYTES];
      new SecureRandom().nextBytes(iv);
      c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
      byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return Base64.encodeToString(out, Base64.NO_WRAP);
    } catch (Exception e) {
      android.util.Log.w("CredentialStore", "encrypt failed: " + e);
      return null;
    }
  }

  private String decrypt(String sealed) throws Exception {
    byte[] all = Base64.decode(sealed, Base64.NO_WRAP);
    if (all.length <= IV_BYTES) throw new IllegalStateException("short blob");
    byte[] iv = new byte[IV_BYTES];
    byte[] ct = new byte[all.length - IV_BYTES];
    System.arraycopy(all, 0, iv, 0, IV_BYTES);
    System.arraycopy(all, IV_BYTES, ct, 0, ct.length);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
    return new String(c.doFinal(ct), StandardCharsets.UTF_8);
  }
}
