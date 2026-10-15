package top.frankfurtlin.dshmobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Automation without root: reads the on-screen tree and drives taps/swipes through
 * the accessibility API. The user must switch this service on in system settings.
 */
public class A11yService extends AccessibilityService {
  private static final String TAG = "DshA11y";
  /** Longest a bridge call waits for a UI operation. */
  private static final long OP_TIMEOUT_MS = 15_000;
  /** Default screenshot budget; the bridge rejects messages above 1 MiB. */
  private static final int DEFAULT_MAX_IMAGE_BYTES = 600_000;

  static volatile A11yService instance;

  private final Handler main = new Handler(Looper.getMainLooper());

  @Override
  protected void onServiceConnected() {
    instance = this;
    Log.i(TAG, "accessibility service connected");
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    // The bridge drives automation by request; there is nothing to do per event.
  }

  @Override
  public void onInterrupt() {
    // No continuous feedback to interrupt.
  }

  @Override
  public boolean onUnbind(Intent intent) {
    instance = null;
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    instance = null;
    super.onDestroy();
  }

  /** True while the user has this service switched on. */
  public static boolean ready() {
    return instance != null;
  }

  /** Runs `body` on the main thread and waits, because accessibility calls expect it. */
  private <T> T onMain(Callable<T> body) throws Exception {
    if (Looper.myLooper() == Looper.getMainLooper()) return body.call();
    final Object[] result = new Object[1];
    final Exception[] failure = new Exception[1];
    final CountDownLatch done = new CountDownLatch(1);
    main.post(() -> {
      try {
        result[0] = body.call();
      } catch (Exception e) {
        failure[0] = e;
      } finally {
        done.countDown();
      }
    });
    if (!done.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException("accessibility call timed out");
    if (failure[0] != null) throw failure[0];
    @SuppressWarnings("unchecked")
    T value = (T) result[0];
    return value;
  }

  /** Visible text/content of the active window, flattened to a node list. */
  public String tree(final int maxNodes) throws Exception {
    return onMain(() -> {
      AccessibilityNodeInfo root = getRootInActiveWindow();
      if (root == null) throw new IllegalStateException("no active window");
      JSONArray nodes = new JSONArray();
      ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
      queue.add(root);
      while (!queue.isEmpty() && nodes.length() < maxNodes) {
        AccessibilityNodeInfo node = queue.poll();
        JSONObject entry = new JSONObject();
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) entry.put("text", text.toString());
        if (desc != null && desc.length() > 0) entry.put("desc", desc.toString());
        CharSequence hint = node.getHintText();
        if (hint != null && hint.length() > 0) entry.put("hint", hint.toString());
        if (node.getViewIdResourceName() != null) entry.put("id", node.getViewIdResourceName());
        CharSequence cls = node.getClassName();
        if (cls != null) entry.put("class", cls.toString());
        if (node.isClickable()) entry.put("clickable", true);
        if (node.isEditable()) entry.put("editable", true);
        if (node.isScrollable()) entry.put("scrollable", true);
        if (node.isChecked()) entry.put("checked", true);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        entry.put("bounds", new JSONArray(new int[] { bounds.left, bounds.top, bounds.right, bounds.bottom }));
        entry.put("center", new JSONArray(new int[] { bounds.centerX(), bounds.centerY() }));
        nodes.put(entry);
        for (int i = 0; i < node.getChildCount(); i++) {
          AccessibilityNodeInfo child = node.getChild(i);
          if (child != null) queue.add(child);
        }
      }
      JSONObject out = new JSONObject();
      CharSequence pkg = root.getPackageName();
      out.put("package", pkg == null ? "" : pkg.toString());
      out.put("count", nodes.length());
      out.put("nodes", nodes);
      return out.toString();
    });
  }

  /** Current screen as a base64 JPEG, shrunk until it fits `maxBytes`. */
  public String screenshot(final int maxBytes) throws Exception {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) throw new IllegalStateException("screenshot needs Android 11+");
    final int budget = maxBytes > 0 ? maxBytes : DEFAULT_MAX_IMAGE_BYTES;
    final Object[] shot = new Object[1];        // the wrapped Bitmap on success, an Exception on failure
    final HardwareBuffer[] buffer = new HardwareBuffer[1];
    final CountDownLatch done = new CountDownLatch(1);
    // Same trap as gesture(): takeScreenshot() belongs on the main thread and getMainExecutor()
    // delivers its callback there too, so awaiting it on the main thread guaranteed a timeout.
    // Wait on the calling thread and keep the buffer open until the JPEG is encoded.
    main.post(new Runnable() {
      @Override
      public void run() {
        try {
          takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
              try {
                buffer[0] = result.getHardwareBuffer();
                Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer[0], result.getColorSpace());
                if (bitmap == null) throw new IllegalStateException("cannot read the screenshot bitmap");
                shot[0] = bitmap;
              } catch (Exception e) {
                shot[0] = e;
              } finally {
                done.countDown();
              }
            }

            @Override
            public void onFailure(int errorCode) {
              shot[0] = new IllegalStateException("screenshot failed with code " + errorCode);
              done.countDown();
            }
          });
        } catch (Exception e) {
          shot[0] = e;
          done.countDown();
        }
      }
    });
    if (!done.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      if (buffer[0] != null) buffer[0].close();
      throw new IllegalStateException("screenshot timed out");
    }
    try {
      if (shot[0] instanceof Exception) throw (Exception) shot[0];
      Bitmap wrapped = (Bitmap) shot[0];
      // compress() reads through the hardware buffer; a software copy keeps the scaling path simple
      // and gets the whole encode off the main thread (it used to run inside the callback).
      Bitmap source = wrapped.getConfig() == Bitmap.Config.HARDWARE
        ? wrapped.copy(Bitmap.Config.ARGB_8888, false)
        : wrapped;
      return encodeJpeg(source, budget);
    } finally {
      if (buffer[0] != null) buffer[0].close();
    }
  }

  /** Scales and re-encodes until the JPEG fits the byte budget. */
  private static String encodeJpeg(Bitmap source, int budget) {
    int[] scales = { 100, 75, 55, 40, 28 };
    int[] qualities = { 80, 65, 50, 35 };
    byte[] best = null;
    int width = source.getWidth();
    int height = source.getHeight();
    for (int scale : scales) {
      Bitmap scaled = scale == 100
        ? source
        : Bitmap.createScaledBitmap(source, Math.max(1, source.getWidth() * scale / 100), Math.max(1, source.getHeight() * scale / 100), true);
      width = scaled.getWidth();
      height = scaled.getHeight();
      for (int quality : qualities) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out);
        byte[] bytes = out.toByteArray();
        if (best == null || bytes.length < best.length) best = bytes;
        if (bytes.length <= budget) {
          best = bytes;
          scaled = null;
          break;
        }
      }
      if (scaled != null && scaled != source) scaled.recycle();
      if (best != null && best.length <= budget) break;
    }
    JSONObject meta = new JSONObject();
    try {
      meta.put("image", Base64.encodeToString(best, Base64.NO_WRAP));
      meta.put("mime", "image/jpeg");
      meta.put("width", width);
      meta.put("height", height);
      meta.put("bytes", best.length);
    } catch (Exception ignored) {
      // The fields above are all plain values, so this cannot fail in practice.
    }
    return meta.toString();
  }

  public boolean tap(final int x, final int y) throws Exception {
    Path path = new Path();
    path.moveTo(x, y);
    return gesture(path, 0, 60);
  }

  public boolean swipe(final int x1, final int y1, final int x2, final int y2, final int durationMs) throws Exception {
    Path path = new Path();
    path.moveTo(x1, y1);
    path.lineTo(x2, y2);
    return gesture(path, 0, Math.max(50, durationMs));
  }

  private boolean gesture(final Path path, final long startMs, final long durationMs) throws Exception {
    GestureDescription.Builder builder = new GestureDescription.Builder();
    builder.addStroke(new GestureDescription.StrokeDescription(path, startMs, durationMs));
    final GestureDescription description = builder.build();
    final CountDownLatch done = new CountDownLatch(1);
    final boolean[] ok = new boolean[1];
    final Exception[] failure = new Exception[1];
    // dispatchGesture() must be called from the main thread, but its result callback is posted back
    // to the main thread as well. Awaiting that callback *inside* onMain() — which is what this did
    // — froze the UI for the whole timeout, ANR'd the app and took the bridge socket down with it:
    // every tap or swipe from the agent ended with the phone going offline. Dispatch on the main
    // thread, wait on the calling one.
    main.post(new Runnable() {
      @Override
      public void run() {
        try {
          boolean dispatched = dispatchGesture(description, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
              ok[0] = true;
              done.countDown();
            }

            @Override
            public void onCancelled(GestureDescription d) {
              ok[0] = false;
              done.countDown();
            }
          }, main);
          if (!dispatched) throw new IllegalStateException("the gesture was not accepted");
        } catch (Exception e) {
          failure[0] = e;
          done.countDown();
        }
      }
    });
    if (!done.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IllegalStateException("the gesture timed out");
    if (failure[0] != null) throw failure[0];
    return ok[0];
  }

  /** back / home / recents / notifications / quick_settings. */
  public boolean global(final String action) throws Exception {
    int code;
    switch (action == null ? "" : action) {
      case "back":
        code = GLOBAL_ACTION_BACK;
        break;
      case "home":
        code = GLOBAL_ACTION_HOME;
        break;
      case "recents":
        code = GLOBAL_ACTION_RECENTS;
        break;
      case "notifications":
        code = GLOBAL_ACTION_NOTIFICATIONS;
        break;
      case "quick_settings":
        code = GLOBAL_ACTION_QUICK_SETTINGS;
        break;
      case "lock":
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw new IllegalStateException("lock needs Android 9+");
        code = GLOBAL_ACTION_LOCK_SCREEN;
        break;
      default:
        throw new IllegalStateException("unknown global action: " + action);
    }
    final int target = code;
    return onMain(() -> performGlobalAction(target));
  }
}
