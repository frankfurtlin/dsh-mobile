package top.frankfurtlin.dshmobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** First run: ask for the server address, probe it, then hand over to the login flow. */
public class SetupActivity extends AppCompatActivity {
  private static final String TAG = "SetupActivity";
  private static final int TIMEOUT_MS = 8000;

  private EditText endpointInput;
  private CheckBox insecureBox;
  private Button connectButton;
  private TextView statusText;
  private Settings settings;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private volatile boolean probing;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    settings = new Settings(this);
    setContentView(R.layout.activity_setup);

    endpointInput = findViewById(R.id.endpoint_input);
    insecureBox = findViewById(R.id.insecure_box);
    connectButton = findViewById(R.id.connect_button);
    statusText = findViewById(R.id.status_text);

    String current = settings.getEndpointRaw();
    if (!TextUtils.isEmpty(current)) endpointInput.setText(current);
    insecureBox.setChecked(settings.allowInsecure());

    connectButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        probe();
      }
    });
  }

  private void probe() {
    if (probing) return;
    String raw = endpointInput.getText().toString();
    final Endpoint ep = Endpoint.parse(raw);
    if (ep == null) {
      statusText.setText(R.string.setup_error_bad_url);
      return;
    }
    if (!ep.secure && !insecureBox.isChecked()) {
      statusText.setText(R.string.setup_error_http_needs_optin);
      return;
    }

    probing = true;
    connectButton.setEnabled(false);
    statusText.setText(getString(R.string.setup_probing, ep.display()));

    final boolean allowInsecure = insecureBox.isChecked();
    io.execute(new Runnable() {
      @Override
      public void run() {
        final Result r = probeSync(ep);
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            probing = false;
            connectButton.setEnabled(true);
            if (r.ok) {
              settings.setEndpoint(ep.base);
              settings.setAllowInsecure(allowInsecure);
              settings.setLastUrl(null);
              startActivity(new Intent(SetupActivity.this, MainActivity.class));
              finish();
            } else {
              statusText.setText(r.message);
              if (r.gatewayMissing) {
                // Still let the user continue: some deployments hide /gateway/healthz.
                connectButton.setText(R.string.setup_save_anyway);
                connectButton.setOnClickListener(new View.OnClickListener() {
                  @Override
                  public void onClick(View v) {
                    settings.setEndpoint(ep.base);
                    settings.setAllowInsecure(allowInsecure);
                    startActivity(new Intent(SetupActivity.this, MainActivity.class));
                    finish();
                  }
                });
              }
            }
          }
        });
      }
    });
  }

  private static class Result {
    boolean ok;
    boolean gatewayMissing;
    String message;
  }

  private Result probeSync(Endpoint ep) {
    Result r = new Result();
    // 1. Is there anything listening at all?
    String body = null;
    int code = -1;
    try {
      HttpURLConnection c = (HttpURLConnection) new URL(ep.healthUrl()).openConnection();
      c.setConnectTimeout(TIMEOUT_MS);
      c.setReadTimeout(TIMEOUT_MS);
      c.setInstanceFollowRedirects(true);
      code = c.getResponseCode();
      body = readFully(code >= 400 ? c.getErrorStream() : c.getInputStream());
      c.disconnect();
    } catch (Exception e) {
      Log.w(TAG, "healthz probe failed", e);
      r.ok = false;
      r.message = getString(R.string.setup_error_unreachable, describe(e));
      return r;
    }

    if (code == 200 && body != null && body.contains("dsh-passwords")) {
      r.ok = true;
      return r;
    }
    if (code == 404 || (code == 200 && (body == null || !body.contains("dsh-passwords")))) {
      r.ok = false;
      r.gatewayMissing = true;
      r.message = getString(R.string.setup_error_not_gateway, ep.display(), String.valueOf(code));
      return r;
    }
    r.ok = false;
    r.message = getString(R.string.setup_error_http_status, String.valueOf(code));
    return r;
  }

  private static String readFully(InputStream in) {
    if (in == null) return null;
    try {
      BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) sb.append(line);
      return sb.toString();
    } catch (Exception e) {
      return null;
    } finally {
      try {
        in.close();
      } catch (Exception ignored) {
      }
    }
  }

  private static String describe(Exception e) {
    String m = e.getMessage();
    return m == null ? e.getClass().getSimpleName() : m;
  }
}
