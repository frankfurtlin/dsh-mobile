package top.frankfurtlin.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

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

  /**
   * Last failure, for the UI to show. Sealing a token can fail for reasons the user cannot guess
   * at (a broken AndroidKeyStore provider, for one), and a silently empty store looks exactly
   * like "never logged in" — which sends debugging down the wrong path.
   */
  public static volatile String lastError;

  private final SharedPreferences prefs;

  public CredentialStore(Context ctx) {
    this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  // ----- token -------------------------------------------------------------

  public synchronized void save(String host, String token) {
    try {
      String sealed = encrypt(token);
      if (sealed == null) return; // encrypt() already recorded why
      prefs.edit()
          .putString(K_TOKEN, sealed)
          .putString(K_HOST, host)
          .putLong(K_SAVED_AT, System.currentTimeMillis())
          .apply();
      lastError = null;
    } catch (Exception e) {
      lastError = "save: " + e;
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
      String token = decrypt(sealed);
      lastError = null;
      return token;
    } catch (Exception e) {
      lastError = "decrypt: " + e;
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
      byte[] blob = seal(key(), plain.getBytes(StandardCharsets.UTF_8));
      return Base64.encodeToString(blob, Base64.NO_WRAP);
    } catch (Exception e) {
      lastError = "encrypt: " + e;
      android.util.Log.w("CredentialStore", "encrypt failed: " + e);
      return null;
    }
  }

  private String decrypt(String sealed) throws Exception {
    return new String(open(key(), Base64.decode(sealed, Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  // ----- blob format --------------------------------------------------------
  // Kept free of android.* calls on purpose: CredentialStoreTest drives these two on the JVM.

  /**
   * Seals `plain` into `IV || ciphertext`.
   *
   * The IV has to come from the cipher, never from us: AndroidKeyStore keys default to
   * `setRandomizedEncryptionRequired(true)`, so `init(ENCRYPT_MODE, key, GCMParameterSpec)` throws
   * `InvalidAlgorithmParameterException: Caller-provided IV not permitted`. That is precisely how
   * the credential store used to end up permanently empty while every call looked successful.
   *
   * @param key AES-GCM key
   * @param plain plaintext
   * @return IV followed by ciphertext and tag
   * @throws GeneralSecurityException when the provider refuses the request
   */
  static byte[] seal(SecretKey key, byte[] plain) throws GeneralSecurityException {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, key);
    byte[] iv = c.getIV();
    if (iv == null || iv.length != IV_BYTES) {
      // Writing an IV we cannot read back would produce an undecryptable blob; fail loudly instead.
      throw new GeneralSecurityException("unexpected GCM IV length " + (iv == null ? -1 : iv.length));
    }
    byte[] ct = c.doFinal(plain);
    byte[] out = new byte[iv.length + ct.length];
    System.arraycopy(iv, 0, out, 0, iv.length);
    System.arraycopy(ct, 0, out, iv.length, ct.length);
    return out;
  }

  /**
   * Opens a blob produced by {@link #seal}.
   *
   * @param key the key the blob was sealed with
   * @param blob IV followed by ciphertext and tag
   * @return the plaintext
   * @throws GeneralSecurityException when the blob is too short or the tag does not verify
   */
  static byte[] open(SecretKey key, byte[] blob) throws GeneralSecurityException {
    if (blob == null || blob.length <= IV_BYTES) throw new GeneralSecurityException("sealed blob has no ciphertext");
    byte[] iv = new byte[IV_BYTES];
    byte[] ct = new byte[blob.length - IV_BYTES];
    System.arraycopy(blob, 0, iv, 0, IV_BYTES);
    System.arraycopy(blob, IV_BYTES, ct, 0, ct.length);
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
    return c.doFinal(ct);
  }
}
