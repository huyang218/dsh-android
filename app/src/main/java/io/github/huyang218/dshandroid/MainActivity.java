package io.github.huyang218.dshandroid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shell: a WebView over the host this app runs itself.
 *
 * <p>The URL is loopback and always will be. This app does not connect to a
 * host on another machine — that would need an auth layer dsh deliberately
 * does not have (its `/api` trust fence is "a reachability policy, not
 * authentication"), and it would defeat the premise, which is that the whole
 * thing works with no second machine involved.
 *
 * <p>Startup is therefore three steps, not one: ask {@link NodeService} to own
 * a Node process, wait for the port it binds, then load. The waiting screen
 * exists because the host takes seconds to compose its plugin tree, and a
 * WebView pointed at a port nobody is listening on renders a browser error
 * page that says nothing useful about what went wrong.
 */
public class MainActivity extends Activity {

    /** The only URL this app ever loads. */
    private static final String HOST_URL = "http://127.0.0.1:" + NodeService.PORT + "/";

    /**
     * Chromium major version the dsh client needs.
     *
     * <p>Evidence, not a guess: an API 28 emulator ships Chromium 66 and the
     * client's bundle dies on `Unexpected token ?` — optional chaining and
     * nullish coalescing, which arrived in Chromium 80. This is the floor that
     * one syntax error proves, NOT an audit of everything the client uses, so
     * the real floor can only be higher. Tighten it from device evidence.
     *
     * <p>WebView is updated independently of the OS (Play ships "Android System
     * WebView"), so this is orthogonal to minSdk: a maintained Android 9 phone
     * passes, and a device with no update channel may fail at any OS version.
     * Since this app is distributed outside Play, that channel is not ours to
     * rely on — hence a check rather than an assumption.
     */
    private static final int MIN_CHROMIUM_MAJOR = 80;

    /** `Chrome/<major>.<...>` inside the WebView user-agent string. */
    private static final Pattern CHROME_VERSION = Pattern.compile("Chrome/(\\d+)\\.");

    /** How long to wait for the host to bind before saying so. */
    private static final long STARTUP_TIMEOUT_MS = 90_000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView status;
    private volatile boolean stopped;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int chromium = webViewChromiumMajor();
        if (chromium > 0 && chromium < MIN_CHROMIUM_MAJOR) {
            // A blank screen is the worst possible failure here: nothing on it
            // says the fault is a stale system component the user can update.
            setContentView(notice("This device's Android System WebView is too old to run the"
                    + " dsh client.\n\nFound Chromium " + chromium + ", need "
                    + MIN_CHROMIUM_MAJOR + " or newer.\n\nUpdating \"Android System WebView\""
                    + " (or Chrome, when it provides the WebView) should fix it."));
            return;
        }

        if (!Runtime.isProvisioned(this)) {
            setContentView(notice("No runtime installed.\n\nThis build does not carry the Node"
                    + " and dsh trees yet; they are provisioned from the build machine."
                    + "\n\nSee PLAN.md 线 A."));
            return;
        }

        status = notice("Starting the dsh host on this device…");
        setContentView(status);

        startService(new Intent(this, NodeService.class));
        awaitHost();
    }

    /**
     * Poll the loopback port off the main thread, then load.
     *
     * <p>A plain socket connect rather than an HTTP request for the readiness
     * check: the server binds before its plugin tree finishes composing, and
     * this only needs to know that something is listening — the WebView's own
     * load is what proves the client is actually being served.
     */
    private void awaitHost() {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
            while (!stopped && System.currentTimeMillis() < deadline) {
                if (portIsOpen()) {
                    ui.post(this::showClient);
                    return;
                }
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!stopped) {
                ui.post(() -> status.setText("The dsh host did not come up within "
                        + (STARTUP_TIMEOUT_MS / 1000) + "s.\n\nIts output is in "
                        + Runtime.logFile(this).getAbsolutePath()));
            }
        }, "dsh-host-wait").start();
    }

    private boolean portIsOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", NodeService.PORT), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void showClient() {
        // Debug builds expose the WebView to chrome://inspect (and to CDP over
        // `adb forward … localabstract:webview_devtools_remote_<pid>`). The
        // client is a React application whose failures are invisible from
        // outside — screenshots and `adb shell input tap` can tell you that a
        // control did nothing, never why. Release builds must not carry this:
        // it would let any local debugger drive the user's sessions.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        WebSettings settings = web.getSettings();
        // The dsh client is a React application served by the host; without
        // scripting there is nothing to show at all.
        settings.setJavaScriptEnabled(true);
        // Session-scoped browser state the client keeps (locale preference and
        // similar) lives in DOM storage.
        settings.setDomStorageEnabled(true);
        // The client owns its own responsive layout, so the WebView must hand
        // it the real viewport rather than the 980px desktop fiction.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        // Keep every navigation inside this WebView: there is nowhere else for
        // a loopback host to be opened.
        web.setWebViewClient(new WebViewClient());

        setContentView(web);
        web.loadUrl(HOST_URL);
    }

    /**
     * Chromium major version behind this device's WebView, or 0 when the
     * user-agent does not name one (a non-Chromium WebView implementation, in
     * which case this check has nothing to say and the client is left to try).
     */
    private int webViewChromiumMajor() {
        String ua;
        try {
            ua = WebSettings.getDefaultUserAgent(this);
        } catch (RuntimeException e) {
            // Thrown when no WebView implementation can be loaded at all.
            return 0;
        }
        Matcher m = CHROME_VERSION.matcher(ua == null ? "" : ua);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A plain-text screen, for the states a web page cannot describe. */
    private TextView notice(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTextColor(Color.BLACK);
        view.setBackgroundColor(Color.WHITE);
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    /** Hardware/gesture back walks the client's own history before leaving. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
