package top.frankfurtlin.dshmobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Directory listing, reading and writing for the agent, over the two doors the
 * user can open:
 *
 * <ul>
 *   <li><b>tree</b> — the folder the user picked with
 *       {@code ACTION_OPEN_DOCUMENT_TREE}; any provider (internal storage, SD
 *       card, a document provider) becomes reachable through its persisted
 *       permission.</li>
 *   <li><b>downloads</b> — {@code Downloads/DSH}, always present on API 29+ and
 *       requiring no permission.</li>
 * </ul>
 *
 * Both are addressed with one portable string per entry so the agent never has
 * to hold a raw {@code content://} URI:
 *
 * <pre>
 *   tree:/Documents/report.pdf
 *   downloads:/report.pdf
 * </pre>
 *
 * A raw document URI is still accepted wherever a path is, because providers
 * may hand out identifiers the visible path cannot express.
 */
public final class FileBrowser {
  private static final String TAG = "DshFiles";

  /** Hard ceiling on entries returned by one listing. */
  public static final int MAX_ENTRIES = 500;
  /** Hard ceiling on bytes returned by one read. */
  public static final int MAX_READ_BYTES = 512 * 1024;

  static final String KIND_TREE = "tree";
  static final String KIND_DOWNLOADS = "downloads";

  private final Context ctx;
  private final Settings settings;

  /**
   * @param ctx any context; the application context is used for queries
   */
  public FileBrowser(Context ctx) {
    this.ctx = ctx.getApplicationContext();
    this.settings = new Settings(this.ctx);
  }

  /** One addressed location: a kind plus a slash separated relative path. */
  static final class Target {
    final String kind;
    final String rel;

    Target(String kind, String rel) {
      this.kind = kind;
      this.rel = rel;
    }

    /** @return {@code kind:/rel} */
    String path() {
      return kind + ":/" + rel;
    }

    /** @return last path segment, empty for a root */
    String name() {
      int slash = rel.lastIndexOf('/');
      return slash < 0 ? rel : rel.substring(slash + 1);
    }

    /** @return parent path, or the root when there is no parent */
    String parentRel() {
      int slash = rel.lastIndexOf('/');
      return slash < 0 ? "" : rel.substring(0, slash);
    }

    /** @return the location holding this one */
    Target parent() {
      return new Target(kind, parentRel());
    }
  }

  /**
   * Parse an agent supplied path.
   *
   * @param path {@code tree:/…}, {@code downloads:/…} (single or double slash), possibly empty after the prefix
   * @return the parsed target, or {@code null} when the prefix is unknown or the path tries to escape its root
   */
  static Target parsePath(String path) {
    if (path == null) return null;
    String trimmed = path.trim();
    String kind = null;
    String rest = null;
    for (String candidate : new String[]{KIND_TREE, KIND_DOWNLOADS}) {
      String prefix = candidate + ":";
      if (trimmed.equals(candidate) || trimmed.equals(prefix)) {
        kind = candidate;
        rest = "";
        break;
      }
      if (trimmed.startsWith(prefix)) {
        kind = candidate;
        rest = trimmed.substring(prefix.length());
        break;
      }
    }
    if (kind == null) return null;

    List<String> segments = new ArrayList<>();
    for (String raw : rest.split("/", -1)) {
      if (raw.isEmpty() || raw.equals(".")) continue;
      if (raw.equals("..") || raw.indexOf('\0') >= 0) return null;
      segments.add(raw);
    }
    return new Target(kind, String.join("/", segments));
  }

  /** @return the roots the agent may list right now */
  JSONArray roots() {
    JSONArray out = new JSONArray();
    try {
      out.put(root(KIND_DOWNLOADS, "Downloads/" + Files.FOLDER, "no permission needed; flat drop folder"));
      Uri tree = treeUri();
      if (tree != null) {
        out.put(root(KIND_TREE, labelOf(tree), "picked by the user; read and write inside this folder"));
      }
    } catch (Exception e) {
      Log.w(TAG, "roots failed", e);
    }
    return out;
  }

  private JSONObject root(String kind, String label, String note) throws Exception {
    JSONObject o = new JSONObject();
    o.put("path", new Target(kind, "").path());
    o.put("kind", kind);
    o.put("label", label);
    o.put("note", note);
    return o;
  }

  /**
   * List one directory.
   *
   * @param path addressed directory, may be null when {@code uri} is given
   * @param uri raw document URI, may be null
   * @return {@code {path, entries[], count, truncated}} or {@code {entries:[], note}} for an empty provider
   * @throws IllegalArgumentException when the location cannot be addressed
   * @throws Exception when the provider fails
   */
  JSONObject list(String path, String uri) throws Exception {
    if (uri != null && !uri.isEmpty()) {
      return listTreeUri(Uri.parse(uri));
    }
    Target target = parsePath(path);
    if (target == null) {
      throw new IllegalArgumentException("fs.list needs path like tree:/Documents or downloads:/, or a document uri");
    }
    if (KIND_DOWNLOADS.equals(target.kind)) return listDownloads(target);
    return listTree(target);
  }

  /**
   * Read one file.
   *
   * @param path addressed file, may be null when {@code uri} is given
   * @param uri raw document URI, may be null
   * @return {@code {path, uri, name, mime, bytes, encoding, text|data, truncated}}
   * @throws IllegalArgumentException when the location cannot be addressed
   * @throws Exception when the provider fails
   */
  JSONObject read(String path, String uri) throws Exception {
    Uri docUri = uri != null && !uri.isEmpty() ? Uri.parse(uri) : resolve(path, true).uri;
    if (docUri == null) throw new IllegalArgumentException("no such location: " + (path != null ? path : uri));

    byte[] bytes;
    try (InputStream in = ctx.getContentResolver().openInputStream(docUri)) {
      if (in == null) throw new IllegalStateException("cannot open " + docUri);
      bytes = readAtMost(in, MAX_READ_BYTES + 1);
    }
    boolean truncated = bytes.length > MAX_READ_BYTES;
    if (truncated) {
      byte[] capped = new byte[MAX_READ_BYTES];
      System.arraycopy(bytes, 0, capped, 0, MAX_READ_BYTES);
      bytes = capped;
    }

    String name = displayName(docUri);
    String mime = mimeOf(docUri, name);
    JSONObject out = new JSONObject();
    out.put("uri", docUri.toString());
    if (path != null && !path.isEmpty()) out.put("path", path);
    out.put("name", name);
    out.put("mime", mime);
    out.put("bytes", bytes.length);
    out.put("truncated", truncated);
    if (truncated) out.put("note", "only the first " + MAX_READ_BYTES + " bytes are returned");
    if (isText(mime, bytes)) {
      out.put("encoding", "utf8");
      out.put("text", new String(bytes, StandardCharsets.UTF_8));
    } else {
      out.put("encoding", "base64");
      out.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
    }
    return out;
  }

  /**
   * Create or overwrite one file.
   *
   * @param path addressed file, may be null when {@code uri} is given
   * @param uri raw document URI of an existing file, may be null
   * @param name file name, required when creating under a path that has no file yet
   * @param mime mime type for a newly created file, inferred from {@code name} when empty
   * @param data content, {@code utf8} text or {@code base64}
   * @param encoding {@code utf8} (default) or {@code base64}
   * @return {@code {uri, path, name, bytes, created}}
   * @throws IllegalArgumentException when the location or the payload cannot be addressed
   * @throws Exception when the provider fails
   */
  JSONObject write(String path, String uri, String name, String mime, String data, String encoding) throws Exception {
    if (data == null) throw new IllegalArgumentException("fs.write needs data");
    byte[] bytes = "base64".equalsIgnoreCase(encoding)
        ? Base64.decode(data, Base64.DEFAULT)
        : data.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_READ_BYTES * 8) {
      throw new IllegalArgumentException("payload too large: " + bytes.length + " bytes");
    }

    Uri target = null;
    boolean created = false;
    if (uri != null && !uri.isEmpty()) {
      target = Uri.parse(uri);
    } else {
      Target parsed = parsePath(path);
      if (parsed == null) {
        throw new IllegalArgumentException("fs.write needs path like tree:/Documents/report.md, or a document uri");
      }
      Resolved existing = resolve(parsed, false);
      if (existing.uri != null) {
        target = existing.uri;
      } else {
        String fileName = name != null && !name.isEmpty() ? name : parsed.name();
        if (fileName == null || fileName.isEmpty()) {
          throw new IllegalArgumentException("fs.write needs a file name (path ends with a folder)");
        }
        String type = mime != null && !mime.isEmpty() ? mime : Files.guessMime(fileName);
        target = create(parsed.parent(), fileName, type);
        created = true;
      }
    }

    try (OutputStream out = ctx.getContentResolver().openOutputStream(target, "wt")) {
      if (out == null) throw new IllegalStateException("cannot open " + target + " for writing");
      out.write(bytes);
      out.flush();
    }

    JSONObject result = new JSONObject();
    result.put("uri", target.toString());
    if (path != null && !path.isEmpty()) result.put("path", path);
    result.put("name", displayName(target));
    result.put("bytes", bytes.length);
    result.put("created", created);
    return result;
  }

  /**
   * Create a directory.
   *
   * @param path addressed parent directory or the new directory itself
   * @param uri raw document URI of the parent, may be null
   * @param name directory name, required when {@code path} names the parent
   * @return {@code {uri, path, name, created}}
   * @throws IllegalArgumentException when the location cannot be addressed
   * @throws Exception when the provider fails
   */
  JSONObject mkdir(String path, String uri, String name) throws Exception {
    Target parent;
    String dirName = name;
    if (uri != null && !uri.isEmpty()) {
      if (dirName == null || dirName.isEmpty()) {
        throw new IllegalArgumentException("fs.mkdir needs name when a uri is given");
      }
      parent = null;
      Uri created = createUri(Uri.parse(uri), dirName, DocumentsContract.Document.MIME_TYPE_DIR);
      return mkdirResult(path, created, dirName);
    }
    Target parsed = parsePath(path);
    if (parsed == null) {
      throw new IllegalArgumentException("fs.mkdir needs path like tree:/Documents/notes, or a document uri");
    }
    if (dirName != null && !dirName.isEmpty()) {
      parent = parsed;
    } else {
      dirName = parsed.name();
      parent = parsed.parent();
    }
    if (dirName == null || dirName.isEmpty()) {
      throw new IllegalArgumentException("fs.mkdir needs a folder name");
    }
    Uri created = create(parent, dirName, DocumentsContract.Document.MIME_TYPE_DIR);
    return mkdirResult(path, created, dirName);
  }

  private JSONObject mkdirResult(String path, Uri created, String dirName) throws Exception {
    JSONObject result = new JSONObject();
    result.put("uri", created.toString());
    if (path != null && !path.isEmpty()) result.put("path", path);
    result.put("name", dirName);
    result.put("created", true);
    return result;
  }

  /* ---------- tree provider ---------- */

  private Uri treeUri() {
    String raw = settings.getFilesRoot();
    if (raw == null || raw.isEmpty()) return null;
    Uri uri = Uri.parse(raw);
    return DocumentsContract.isTreeUri(uri) ? uri : null;
  }

  private JSONObject listTree(Target target) throws Exception {
    Uri tree = treeUri();
    if (tree == null) {
      throw new IllegalStateException("no folder is shared yet; open DSH Mobile and pick one (menu → 选择可浏览的文件夹)");
    }
    return listTreeUri(DocumentsContract.buildDocumentUriUsingTree(tree, documentId(tree, target.rel)));
  }

  private JSONObject listTreeUri(Uri dirUri) throws Exception {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, DocumentsContract.getDocumentId(dirUri));
    Target base = baseOf(dirUri);
    String[] projection = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    List<JSONObject> entries = new ArrayList<>();
    boolean truncated = false;
    try (Cursor cursor = ctx.getContentResolver().query(children, projection, null, null, null)) {
      if (cursor != null) {
        int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
        int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
        int dateCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
        while (cursor.moveToNext()) {
          if (entries.size() >= MAX_ENTRIES) {
            truncated = true;
            break;
          }
          String childId = cursor.getString(idCol);
          String childName = cursor.getString(nameCol);
          String childMime = cursor.getString(mimeCol);
          boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(childMime);
          JSONObject entry = new JSONObject();
          entry.put("name", childName);
          entry.put("mime", childMime);
          entry.put("dir", dir);
          entry.put("size", cursor.isNull(sizeCol) ? JSONObject.NULL : cursor.getLong(sizeCol));
          entry.put("modified", cursor.isNull(dateCol) ? JSONObject.NULL : cursor.getLong(dateCol));
          entry.put("path", joinedPath(base, childName));
          entry.put("uri", DocumentsContract.buildDocumentUriUsingTree(dirUri, childId).toString());
          entries.add(entry);
        }
      }
    }

    return listing(dirUri, base, entries, truncated);
  }

  private JSONObject listing(Uri dirUri, Target base, List<JSONObject> entries, boolean truncated) throws Exception {
    Collections.sort(entries, new Comparator<JSONObject>() {
      @Override
      public int compare(JSONObject a, JSONObject b) {
        boolean da = a.optBoolean("dir");
        boolean db = b.optBoolean("dir");
        if (da != db) return da ? -1 : 1;
        return a.optString("name").compareToIgnoreCase(b.optString("name"));
      }
    });
    JSONArray array = new JSONArray();
    for (JSONObject entry : entries) array.put(entry);
    JSONObject out = new JSONObject();
    out.put("path", base.path());
    out.put("uri", dirUri.toString());
    out.put("count", entries.size());
    out.put("truncated", truncated);
    out.put("entries", array);
    return out;
  }

  /* ---------- Downloads/DSH via MediaStore ---------- */

  private JSONObject listDownloads(Target target) throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      throw new IllegalStateException("directory listings need Android 10 or newer");
    }
    String relDir = Files.FOLDER + (target.rel.isEmpty() ? "" : "/" + target.rel) + "/";
    String[] projection = {
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
    };
    Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
    String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=?";
    List<JSONObject> entries = new ArrayList<>();
    boolean truncated = false;
    try (Cursor cursor = ctx.getContentResolver().query(collection, projection, selection, new String[]{relDir}, null)) {
      if (cursor != null) {
        int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
        int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
        int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
        int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
        int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
        while (cursor.moveToNext()) {
          if (entries.size() >= MAX_ENTRIES) {
            truncated = true;
            break;
          }
          String childName = cursor.getString(nameCol);
          String childMime = cursor.getString(mimeCol);
          Uri childUri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idCol));
          JSONObject entry = new JSONObject();
          entry.put("name", childName);
          entry.put("mime", childMime);
          entry.put("dir", false);
          entry.put("size", cursor.isNull(sizeCol) ? JSONObject.NULL : cursor.getLong(sizeCol));
          entry.put("modified", cursor.isNull(dateCol) ? JSONObject.NULL : cursor.getLong(dateCol) * 1000L);
          entry.put("path", joinedPath(target, childName));
          entry.put("uri", childUri.toString());
          entries.add(entry);
        }
      }
    }
    return listing(downloadsDirUri(target), target, entries, truncated);
  }

  private Uri downloadsDirUri(Target target) {
    return Uri.parse("content://" + MediaStore.AUTHORITY + "/downloads" + (target.rel.isEmpty() ? "" : "/" + target.rel));
  }

  /* ---------- addressing ---------- */

  /** A resolved location: the provider URI plus the visible path. */
  private static final class Resolved {
    final Uri uri;
    final Target target;

    Resolved(Uri uri, Target target) {
      this.uri = uri;
      this.target = target;
    }
  }

  private Resolved resolve(String path, boolean required) {
    Target target = parsePath(path);
    if (target == null) {
      throw new IllegalArgumentException("path must start with " + KIND_TREE + ":/ or " + KIND_DOWNLOADS + ":/");
    }
    Resolved resolved = resolve(target, required);
    if (resolved.uri == null && required) {
      throw new IllegalArgumentException("no such file: " + path);
    }
    return resolved;
  }

  private Resolved resolve(Target target, boolean required) {
    if (KIND_DOWNLOADS.equals(target.kind)) {
      Uri uri = target.rel.isEmpty() ? null : mediaStoreUri(target);
      if (uri == null && required && !target.rel.isEmpty()) {
        throw new IllegalArgumentException("no such file in Downloads/" + Files.FOLDER + ": " + target.rel);
      }
      return new Resolved(uri, target);
    }
    Uri tree = treeUri();
    if (tree == null) {
      if (required) {
        throw new IllegalStateException("no folder is shared yet; open DSH Mobile and pick one (menu → 选择可浏览的文件夹)");
      }
      return new Resolved(null, target);
    }
    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId(tree, target.rel));
    return new Resolved(exists(docUri) ? docUri : null, target);
  }

  private boolean exists(Uri docUri) {
    try (Cursor cursor = ctx.getContentResolver().query(docUri,
        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
      return cursor != null && cursor.moveToFirst();
    } catch (Exception e) {
      Log.w(TAG, "exists failed: " + docUri, e);
      return false;
    }
  }

  private String documentId(Uri tree, String rel) {
    String rootId = DocumentsContract.getTreeDocumentId(tree);
    if (rel.isEmpty()) return rootId;
    return rootId.isEmpty() ? rel : rootId + "/" + rel;
  }

  private Target baseOf(Uri dirUri) {
    Uri tree = treeUri();
    if (tree == null) return new Target(KIND_TREE, "");
    String rootId = DocumentsContract.getTreeDocumentId(tree);
    String dirId = DocumentsContract.getDocumentId(dirUri);
    String rel = dirId.equals(rootId) ? ""
        : (dirId.startsWith(rootId + "/") ? dirId.substring(rootId.length() + 1) : "");
    return new Target(KIND_TREE, rel);
  }

  private static String joinedPath(Target base, String name) {
    return new Target(base.kind, base.rel.isEmpty() ? name : base.rel + "/" + name).path();
  }

  private Uri mediaStoreUri(Target target) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;
    String name = target.name();
    String relDir = Files.FOLDER + (target.parentRel().isEmpty() ? "" : "/" + target.parentRel()) + "/";
    String[] projection = {MediaStore.MediaColumns._ID};
    String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
    try (Cursor cursor = ctx.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        projection, selection, new String[]{name, relDir}, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
      }
    }
    return null;
  }

  /* ---------- create ---------- */

  private Uri create(Target parent, String name, String mime) throws Exception {
    Resolved dir = resolve(parent, true);
    if (dir.uri == null) {
      throw new IllegalArgumentException("no such folder: " + parent.path());
    }
    return createUri(dir.uri, name, mime);
  }

  private Uri createUri(Uri parentUri, String name, String mime) throws Exception {
    if (KIND_DOWNLOADS.equals(parentKind(parentUri))) {
      return createDownload(name, mime);
    }
    Uri created = DocumentsContract.createDocument(ctx.getContentResolver(), parentUri, mime, name);
    if (created == null) {
      throw new IllegalStateException("the provider refused to create " + name + " under " + parentUri);
    }
    return created;
  }

  private String parentKind(Uri parentUri) {
    String scheme = parentUri.getScheme();
    if (scheme != null && scheme.startsWith("content") && MediaStore.AUTHORITY.equals(parentUri.getAuthority())) {
      return KIND_DOWNLOADS;
    }
    return KIND_TREE;
  }

  private Uri createDownload(String name, String mime) throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      throw new IllegalStateException("writing to Downloads needs Android 10 or newer");
    }
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Files.FOLDER);
    Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new IllegalStateException("could not create " + name + " in Downloads/" + Files.FOLDER);
    return uri;
  }

  /* ---------- helpers ---------- */

  private String labelOf(Uri tree) {
    try {
      String rootId = DocumentsContract.getTreeDocumentId(tree);
      int colon = rootId.indexOf(':');
      String tail = colon >= 0 ? rootId.substring(colon + 1) : rootId;
      int slash = tail.lastIndexOf('/');
      return slash >= 0 ? tail.substring(slash + 1) : tail;
    } catch (Exception e) {
      return tree.getLastPathSegment() != null ? tree.getLastPathSegment() : "picked folder";
    }
  }

  private String displayName(Uri docUri) {
    String scheme = docUri.getScheme();
    if (scheme != null && MediaStore.AUTHORITY.equals(docUri.getAuthority())) {
      try (Cursor cursor = ctx.getContentResolver().query(docUri,
          new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
      } catch (Exception e) {
        Log.w(TAG, "displayName failed: " + docUri, e);
      }
    }
    try {
      if (DocumentsContract.isDocumentUri(ctx, docUri)) {
        try (Cursor cursor = ctx.getContentResolver().query(docUri,
            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
          if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
      }
    } catch (Exception e) {
      Log.w(TAG, "displayName failed: " + docUri, e);
    }
    String last = docUri.getLastPathSegment();
    return last != null ? last : "file";
  }

  private String mimeOf(Uri docUri, String name) {
    String type = ctx.getContentResolver().getType(docUri);
    if (type != null && !type.isEmpty()) return type;
    return Files.guessMime(name);
  }

  private static byte[] readAtMost(InputStream in, int limit) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[16384];
    int total = 0;
    int read;
    while (total < limit && (read = in.read(chunk, 0, Math.min(chunk.length, limit - total))) > 0) {
      buffer.write(chunk, 0, read);
      total += read;
    }
    return buffer.toByteArray();
  }

  private static boolean isText(String mime, byte[] bytes) {
    if (mime != null) {
      String lower = mime.toLowerCase();
      if (lower.startsWith("text/") || lower.equals("application/json") || lower.equals("application/xml")
          || lower.endsWith("+json") || lower.endsWith("+xml") || lower.equals("application/javascript")) {
        return true;
      }
      if (lower.startsWith("image/") || lower.startsWith("audio/") || lower.startsWith("video/")
          || lower.equals("application/pdf") || lower.equals("application/zip")) {
        return false;
      }
    }
    int limit = Math.min(bytes.length, 4096);
    for (int i = 0; i < limit; i++) {
      if (bytes[i] == 0) return false;
    }
    return true;
  }
}
