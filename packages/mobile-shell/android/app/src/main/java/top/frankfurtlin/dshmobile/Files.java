package top.frankfurtlin.dshmobile;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Writes bytes into the phone's public collections through MediaStore.
 * No user dialog is involved, so the agent path (`files.save`) works unattended.
 */
public final class Files {
  private static final String TAG = "DshFiles";
  private static final String FOLDER = "DSH";

  private Files() {}

  /** Where a saved file landed: the content URI is what the phone and other apps open. */
  public static final class Saved {
    public final String uri;
    public final String name;
    public final String dest;
    public final int size;

    Saved(String uri, String name, String dest, int size) {
      this.uri = uri;
      this.name = name;
      this.dest = dest;
      this.size = size;
    }

    public JSONObject toJson() {
      JSONObject o = new JSONObject();
      try {
        o.put("uri", uri);
        o.put("name", name);
        o.put("dest", dest);
        o.put("size", size);
      } catch (Exception ignored) {
      }
      return o;
    }
  }

  /** Saves `bytes` into Downloads (or Pictures / Movies / Music / Documents) under a `DSH` folder. */
  public static Saved save(Context ctx, String name, String mime, String dest, byte[] bytes) throws Exception {
    String where = dest == null || dest.isEmpty() ? "downloads" : dest.toLowerCase();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return saveMediaStore(ctx, name, mime, where, bytes);
    return saveLegacy(ctx, name, where, bytes);
  }

  private static Saved saveMediaStore(Context ctx, String name, String mime, String dest, byte[] bytes) throws Exception {
    ContentValues cv = new ContentValues();
    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    if (mime != null && !mime.isEmpty()) cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(dest));
    cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

    Uri collection;
    switch (dest) {
      case "pictures":
        collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        break;
      case "movies":
        collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        break;
      case "audio":
        collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        break;
      case "documents":
        collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        break;
      default:
        collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        break;
    }

    Uri uri = ctx.getContentResolver().insert(collection, cv);
    if (uri == null) throw new IllegalStateException("MediaStore refused the insert");
    try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
      if (os == null) throw new IllegalStateException("cannot open output stream");
      os.write(bytes);
    }
    ContentValues done = new ContentValues();
    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
    ctx.getContentResolver().update(uri, done, null, null);
    Log.i(TAG, "saved " + bytes.length + " bytes to " + uri);
    return new Saved(uri.toString(), name, dest, bytes.length);
  }

  /** Below Q there is no RELATIVE_PATH or Downloads collection: use the app's external directory. */
  private static Saved saveLegacy(Context ctx, String name, String dest, byte[] bytes) throws Exception {
    File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER);
    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
    File out = new File(dir, name);
    try (FileOutputStream os = new FileOutputStream(out)) {
      os.write(bytes);
    }
    Log.i(TAG, "saved (legacy) " + bytes.length + " bytes to " + out);
    return new Saved(Uri.fromFile(out).toString(), name, dest, bytes.length);
  }

  private static String relativePath(String dest) {
    switch (dest) {
      case "pictures":
        return Environment.DIRECTORY_PICTURES + "/" + FOLDER;
      case "movies":
        return Environment.DIRECTORY_MOVIES + "/" + FOLDER;
      case "audio":
        return Environment.DIRECTORY_MUSIC + "/" + FOLDER;
      case "documents":
        return Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER;
      default:
        return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
    }
  }
}
