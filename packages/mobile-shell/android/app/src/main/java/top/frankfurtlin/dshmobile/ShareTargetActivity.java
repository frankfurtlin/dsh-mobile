package top.frankfurtlin.dshmobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for "share into DSH" from any other app.
 *
 * Kept as a separate, throw-away activity (no history, excluded from recents) so the share flow
 * never leaves a half-initialised WebView screen behind. Content is copied into {@link Inbox} and
 * the user is dropped back into the running session, where the inbox badge offers open / re-share.
 */
public class ShareTargetActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Intent src = getIntent();
    String action = src == null ? null : src.getAction();
    if (action == null) {
      finish();
      return;
    }

    int saved = 0;
    if (Intent.ACTION_SEND.equals(action)) {
      String text = src.getStringExtra(Intent.EXTRA_TEXT);
      Uri uri = src.getParcelableExtra(Intent.EXTRA_STREAM);
      if (uri != null) {
        if (Inbox.addFile(this, uri, src.getType(), displayName(uri)) != null) saved++;
      } else if (text != null && !text.isEmpty()) {
        if (Inbox.addText(this, text) != null) saved++;
      }
    } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
      List<Uri> uris = src.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (uris != null) {
        for (Uri u : uris) {
          if (Inbox.addFile(this, u, src.getType(), displayName(u)) != null) saved++;
        }
      }
      ArrayList<CharSequence> texts = src.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
      if (texts != null) {
        for (CharSequence t : texts) {
          if (t != null && t.length() > 0 && Inbox.addText(this, t.toString()) != null) saved++;
        }
      }
    }

    Toast.makeText(this, saved > 0
            ? getString(R.string.share_saved, String.valueOf(saved))
            : getString(R.string.share_nothing),
        Toast.LENGTH_SHORT).show();

    Intent next = new Intent(this, MainActivity.class);
    next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(next);
    finish();
  }

  private String displayName(Uri uri) {
    String name = null;
    try {
      android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
      if (c != null) {
        int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        if (c.moveToFirst() && idx >= 0) name = c.getString(idx);
        c.close();
      }
    } catch (Exception ignored) {
    }
    if (name == null) name = uri.getLastPathSegment();
    return name;
  }
}
