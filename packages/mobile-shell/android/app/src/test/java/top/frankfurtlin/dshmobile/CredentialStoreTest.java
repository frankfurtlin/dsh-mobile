package top.frankfurtlin.dshmobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The sealed-blob format behind the stored gateway token.
 *
 * These run on the JVM with a plain AES key, so they cannot reproduce AndroidKeyStore's
 * "Caller-provided IV not permitted" policy — the acceptance harness asserts that against the
 * built APK and the source instead. What is covered here is the part that was broken and silent:
 * which IV ends up in the blob, and what happens to a blob that is truncated or tampered with.
 *
 * Runs with {@code ./gradlew testDebugUnitTest} (no device or emulator needed).
 */
public class CredentialStoreTest {

  private static final SecretKey KEY = new SecretKeySpec(new byte[32], "AES");
  private static final SecretKey OTHER_KEY = new SecretKeySpec(new byte[] { 1, 2, 3 }, "AES");
  private static final int IV_BYTES = 12;
  private static final int GCM_TAG_BYTES = 16;

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test public void roundTripsAToken() throws Exception {
    String token = "dsh_gateway_token-value.with/chars+and=padding";
    byte[] blob = CredentialStore.seal(KEY, utf8(token));
    assertArrayEquals(utf8(token), CredentialStore.open(KEY, blob));
  }

  @Test public void roundTripsEmptyAndBinaryPayloads() throws Exception {
    assertArrayEquals(new byte[0], CredentialStore.open(KEY, CredentialStore.seal(KEY, new byte[0])));
    byte[] binary = { 0, -1, 127, -128, 42 };
    assertArrayEquals(binary, CredentialStore.open(KEY, CredentialStore.seal(KEY, binary)));
  }

  /**
   * A caller-supplied IV would make the blob deterministic. The cipher generating it is the whole
   * point: AndroidKeyStore refuses anything else, and that refusal used to be swallowed.
   */
  @Test public void generatesAFreshIvEveryTime() throws Exception {
    byte[] first = CredentialStore.seal(KEY, utf8("same token"));
    byte[] second = CredentialStore.seal(KEY, utf8("same token"));
    assertFalse("two seals produced identical blobs, so the IV is not per-message", java.util.Arrays.equals(first, second));
    assertArrayEquals(utf8("same token"), CredentialStore.open(KEY, first));
    assertArrayEquals(utf8("same token"), CredentialStore.open(KEY, second));
  }

  /** Layout is `IV || ciphertext || tag`, which is what `open` walks back. */
  @Test public void blobLayoutIsIvThenCiphertextAndTag() throws Exception {
    assertEquals(IV_BYTES + GCM_TAG_BYTES, CredentialStore.seal(KEY, new byte[0]).length);
    assertEquals(IV_BYTES + GCM_TAG_BYTES + 4, CredentialStore.seal(KEY, utf8("abcd")).length);
  }

  @Test public void rejectsATamperedCiphertext() throws Exception {
    byte[] blob = CredentialStore.seal(KEY, utf8("token"));
    blob[blob.length - 1] ^= 0x01; // flip a tag bit
    assertThrows(GeneralSecurityException.class, () -> CredentialStore.open(KEY, blob));
  }

  @Test public void rejectsATamperedIv() throws Exception {
    byte[] blob = CredentialStore.seal(KEY, utf8("token"));
    blob[0] ^= 0x01;
    assertThrows(GeneralSecurityException.class, () -> CredentialStore.open(KEY, blob));
  }

  @Test public void rejectsABlobWithoutCiphertext() {
    assertThrows(GeneralSecurityException.class, () -> CredentialStore.open(KEY, new byte[0]));
    assertThrows(GeneralSecurityException.class, () -> CredentialStore.open(KEY, new byte[IV_BYTES]));
    assertThrows(GeneralSecurityException.class, () -> CredentialStore.open(KEY, null));
  }

  @Test public void rejectsAKeyThatDidNotSealTheBlob() throws Exception {
    byte[] blob = CredentialStore.seal(KEY, utf8("token"));
    assertThrows(GeneralSecurityException.class, () -> CredentialStore.open(OTHER_KEY, blob));
  }
}
