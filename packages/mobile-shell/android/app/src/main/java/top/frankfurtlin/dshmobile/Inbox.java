package top.frankfurtlin.dshmobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage for content shared into DSH Mobile from other apps (ACTION_SEND / ACTION_SEND_MULTIPLE).
 *
 * Incoming `content://` URIs are only readable while the caller's permission grant lasts, so the
 * bytes are copied into our private inbox directory at receive time and only the local path is
 * remembered. Kept intentionally tiny: it is the human-facing half of "share into DSH"; the
 * agent-facing upload path belongs to the phone bridge (P4).
 */
public final class Inbox {
  private static final String PREFS = "inbox";
  private static final String KEY_ITEMS = "items";

  private Inbox() {}

  public static final class Item {
    public final String id;
    public final String name;
    public final String mime;
    public final String path;   // absolute path in our inbox dir; null for text items
    public final String text;   // null for file items
    public final long at;

    Item(String id, String name, String mime, String path, String text, long at) {
      this.id = id;
      this.name = name;
      this.mime = mime;
      this.path = path;
      this.text = text;
      this.at = at;
    }

    JSONObject toJson() {
      JSONObject o = new JSONObject();
      try {
        o.put("id", id);
        o.put("name", name);
        o.put("mime", mime == null ? "" : mime);
        o.put("path", path == null ? "" : path);
        o.put("text", text == null ? "" : text);
        o.put("at", at);
      } catch (JSONException ignored) {
      }
      return o;
    }
  }

  public static File dir(Context c) {
    File d = new File(c.getFilesDir(), "inbox");
    if (!d.exists() && !d.mkdirs()) {
      // Non-fatal: callers fall back to the raw files dir.
    }
    return d;
  }

  /** Copy the content behind {@code src} into the inbox. Returns null when it cannot be read. */
  public static Item addFile(Context c, Uri src, String mime, String displayName) {
    String name = displayName;
    if (name == null || name.isEmpty()) name = src.getLastPathSegment();
    if (name == null || name.isEmpty()) name = "shared";
    name = name.replace('/', '_').replace('\n', '_');

    String id = String.valueOf(System.currentTimeMillis()) + "_" + (int) (Math.random() * 100000);
    File out = new File(dir(c), id + "_" + name);
    try {
      InputStream in = c.getContentResolver().openInputStream(src);
      if (in == null) return null;
      OutputStream os = new FileOutputStream(out);
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
      in.close();
      os.close();
    } catch (Exception e) {
      return null;
    }
    Item item = new Item(id, name, mime == null ? "" : mime, out.getAbsolutePath(), null,
        System.currentTimeMillis());
    save(c, item);
    return item;
  }

  public static Item addText(Context c, String text) {
    String id = String.valueOf(System.currentTimeMillis()) + "_text";
    String name = text.length() > 24 ? text.substring(0, 24) + "…" : text;
    Item item = new Item(id, name, "text/plain", null, text, System.currentTimeMillis());
    save(c, item);
    return item;
  }

  public static List<Item> list(Context c) {
    List<Item> out = new ArrayList<>();
    SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String raw = p.getString(KEY_ITEMS, "[]");
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        String path = o.optString("path", "");
        if (!path.isEmpty() && !new File(path).exists()) continue; // dropped file, skip
        out.add(new Item(o.optString("id"), o.optString("name"), o.optString("mime"),
            path.isEmpty() ? null : path, o.optString("text", "").isEmpty() ? null : o.optString("text"),
            o.optLong("at")));
      }
    } catch (JSONException ignored) {
    }
    return out;
  }

  public static String toJson(Context c) {
    JSONArray arr = new JSONArray();
    for (Item i : list(c)) arr.put(i.toJson());
    return arr.toString();
  }

  public static void remove(Context c, String id) {
    List<Item> items = list(c);
    JSONArray arr = new JSONArray();
    for (Item i : items) {
      if (i.id.equals(id)) {
        if (i.path != null) new File(i.path).delete();
        continue;
      }
      arr.put(i.toJson());
    }
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, arr.toString()).apply();
  }

  public static void clear(Context c) {
    for (Item i : list(c)) {
      if (i.path != null) new File(i.path).delete();
    }
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, "[]").apply();
  }

  private static void save(Context c, Item item) {
    List<Item> items = list(c);
    // Newest first, and keep the inbox bounded so it can never grow without limit.
    List<Item> next = new ArrayList<>();
    next.add(item);
    for (Item i : items) {
      if (next.size() >= 50) break;
      next.add(i);
    }
    JSONArray arr = new JSONArray();
    for (Item i : next) arr.put(i.toJson());
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, arr.toString()).apply();
  }
}
