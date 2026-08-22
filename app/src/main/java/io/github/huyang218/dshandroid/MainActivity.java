package io.github.huyang218.dshandroid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
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

    /** Request code for the attachment picker. */
    private static final int PICK_FILE = 1;

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

    /**
     * How long to wait for the host to bind before calling it dead.
     *
     * <p>Deliberately generous, and it is a BACKSTOP rather than a diagnosis: a
     * host that actually died is reported by the service the moment its process
     * exits ({@link HostStatus.Phase#FAILED}), so this timer only covers the
     * case where nothing reports anything at all.
     *
     * <p>90 seconds was the first guess and it was wrong — measured on an
     * emulator under load, composing the plugin tree took 104 seconds, so the
     * screen declared failure while the host was still coming up. Anything
     * derived from "it feels slow" belongs on the safe side of the slowest
     * device we have actually watched.
     */
    private static final long STARTUP_TIMEOUT_MS = 5 * 60_000L;

    /**
     * The same, for a launch that has to unpack first. Writing ~250 MB of small
     * files took 105 seconds on that same loaded emulator, and it happens
     * before the host even starts; timing out on a working install would be the
     * most confusing failure this screen could produce.
     */
    private static final long FIRST_RUN_TIMEOUT_MS = 20 * 60_000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextView statusTitle;
    private TextView statusDetail;
    private ProgressBar statusBar;
    private volatile boolean stopped;
    /** When this launch attempt began, for the elapsed counter. */
    private long launchedAt;
    /** The page's pending `<input type=file>`; non-null only while the picker is up. */
    private ValueCallback<Uri[]> pendingFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int chromium = webViewChromiumMajor();
        if (chromium > 0 && chromium < MIN_CHROMIUM_MAJOR) {
            // A blank screen is the worst possible failure here: nothing on it
            // says the fault is a stale system component the user can update.
            setContentView(notice(getString(R.string.state_webview_old, chromium, MIN_CHROMIUM_MAJOR)));
            return;
        }

        boolean firstRun = !RuntimeInstaller.isCurrent(this);
        if (firstRun && RuntimeInstaller.packagedStamp(this) == null && !Runtime.isProvisioned(this)) {
            // A build without payloads is a development shape, not a user one:
            // say which, rather than sitting on a port that will never open.
            setContentView(notice(getString(R.string.state_no_runtime)));
            return;
        }

        HostStatus.reset();
        launchedAt = System.currentTimeMillis();
        setContentView(statusScreen(firstRun));
        startService(new Intent(this, NodeService.class));
        awaitHost(firstRun ? FIRST_RUN_TIMEOUT_MS : STARTUP_TIMEOUT_MS);
    }

    /**
     * Poll the loopback port off the main thread, then load.
     *
     * <p>A plain socket connect rather than an HTTP request for the readiness
     * check: the server binds before its plugin tree finishes composing, and
     * this only needs to know that something is listening — the WebView's own
     * load is what proves the client is actually being served.
     */
    private void awaitHost(final long budget) {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + budget;
            while (!stopped && System.currentTimeMillis() < deadline) {
                if (portIsOpen()) {
                    HostStatus.running();
                    ui.post(this::showClient);
                    return;
                }
                // A host that already died must not be waited out: the service
                // knows, and the screen should say so now rather than in ten
                // more minutes.
                if (HostStatus.phase() == HostStatus.Phase.FAILED) {
                    final String why = HostStatus.detail();
                    ui.post(() -> setContentView(failureScreen(why)));
                    return;
                }
                ui.post(this::renderStatus);
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!stopped) {
                ui.post(() -> setContentView(failureScreen(
                        getString(R.string.state_failed_timeout, budget / 1000))));
            }
        }, "dsh-host-wait").start();
    }

    /** Push the service's published state onto the waiting screen. */
    private void renderStatus() {
        if (statusTitle == null) return;
        int percent = HostStatus.percent();
        String what = HostStatus.detail();
        if (HostStatus.phase() == HostStatus.Phase.UNPACKING) {
            statusTitle.setText(getString(R.string.state_first_run));
            statusDetail.setText(what.isEmpty()
                    ? getString(R.string.state_first_run_detail)
                    : getString(R.string.state_unpacking, what, Math.max(percent, 0)));
            if (percent >= 0) {
                statusBar.setIndeterminate(false);
                statusBar.setProgress(percent);
            }
        } else if (HostStatus.phase() == HostStatus.Phase.LAUNCHING) {
            statusTitle.setText(getString(R.string.state_launching));
            // Elapsed seconds, because composing the plugin tree can take a
            // minute or more and a still screen is how a wait looks like a hang.
            long seconds = (System.currentTimeMillis() - launchedAt) / 1000;
            statusDetail.setText(seconds < 5 ? "" : getString(R.string.state_elapsed, seconds));
            statusBar.setIndeterminate(true);
        }
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

        // Attachments. Without this the client's "+" button opens nothing at
        // all: a WebView refuses `<input type=file>` unless the app answers
        // onShowFileChooser, and it fails silently — no error in the page, no
        // log line, just a control that does nothing. The whole image half of
        // the agent's input depends on these twenty lines.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (pendingFiles != null) {
                    // A second request while one is open would strand the first
                    // callback, and the page waits on it forever.
                    pendingFiles.onReceiveValue(null);
                }
                pendingFiles = callback;
                try {
                    // The system picker, not a permission-gated media scan: it
                    // hands back a content: URI for the one file the person
                    // chose, so this app never asks for storage access at all.
                    startActivityForResult(params.createIntent(), PICK_FILE);
                } catch (RuntimeException e) {
                    // No activity can serve the intent (a stripped device, or a
                    // work profile with the picker blocked).
                    pendingFiles = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        web.loadUrl(HOST_URL);
        claimLeftEdgeGesture();
    }

    /**
     * Take the left edge back from the system's back gesture.
     *
     * <p>Swiping in from the left edge is how a drawer is opened on a phone —
     * and on gesture navigation it is ALSO how the system goes back, so the
     * client's own handler never sees the touch at all. Measured: an
     * {@code input swipe} from x=8 backed the app out to the launcher without
     * the frame receiving a single touchstart.
     *
     * <p>{@code setSystemGestureExclusionRects} is what a drawer is supposed to
     * use here. The platform caps each edge at 200dp of exclusion, so this
     * claims a band in the middle of the screen — reachable with a thumb, and
     * leaving the corners to the system so back is never fully taken away.
     */
    private void claimLeftEdgeGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        web.post(() -> {
            int height = web.getHeight();
            int band = Math.min(dp(200), height);
            int top = Math.max(0, (height - band) / 2);
            web.setSystemGestureExclusionRects(
                    java.util.Collections.singletonList(new Rect(0, top, dp(24), top + band)));
        });
    }

    /**
     * Deliver the picked file back to the page, or an empty result when the
     * person backed out — the page's promise never settles otherwise, and the
     * attach button stays stuck.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = pendingFiles;
        pendingFiles = null;
        if (callback == null) return;
        callback.onReceiveValue(resultCode == RESULT_OK && data != null
                ? WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                : null);
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

    /**
     * The waiting screen: mark, one line of what is happening, and a bar that
     * actually moves.
     *
     * <p>Built in code rather than XML because it is the only layout this app
     * owns — everything past it is the client's — and because the first launch
     * after an install spends a minute here. A minute of a motionless screen
     * reads as a hang.
     */
    private View statusScreen(boolean firstRun) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(color(R.color.shell_bg));
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams markParams =
                new LinearLayout.LayoutParams(dp(64), dp(64));
        markParams.bottomMargin = dp(24);
        root.addView(mark, markParams);

        statusTitle = new TextView(this);
        statusTitle.setText(firstRun
                ? getString(R.string.state_first_run)
                : getString(R.string.state_starting));
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusTitle.setTextColor(color(R.color.shell_text));
        statusTitle.setGravity(Gravity.CENTER);
        root.addView(statusTitle);

        statusBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        statusBar.setMax(100);
        statusBar.setIndeterminate(true);
        // The platform accent is whatever the device vendor chose; this screen
        // should look like this app in both themes.
        statusBar.setProgressTintList(android.content.res.ColorStateList.valueOf(color(R.color.shell_accent)));
        statusBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color(R.color.shell_accent)));
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dp(20);
        barParams.bottomMargin = dp(12);
        root.addView(statusBar, barParams);

        statusDetail = new TextView(this);
        statusDetail.setText(firstRun ? getString(R.string.state_first_run_detail) : "");
        statusDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusDetail.setTextColor(color(R.color.shell_text_dim));
        statusDetail.setGravity(Gravity.CENTER);
        root.addView(statusDetail);

        return root;
    }

    /**
     * What went wrong, with the evidence attached.
     *
     * <p>The old screen printed the log's PATH — a path inside private app
     * storage, which the person reading it cannot open. The last lines of that
     * log are the only thing that makes the failure actionable, so they belong
     * on the screen.
     */
    private View failureScreen(String why) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.shell_bg));
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(why.isEmpty() ? getString(R.string.state_failed) : why);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(color(R.color.shell_text));
        root.addView(title);

        Button retry = new Button(this);
        retry.setText(getString(R.string.action_retry));
        retry.setOnClickListener(v -> {
            HostStatus.reset();
            launchedAt = System.currentTimeMillis();
            setContentView(statusScreen(false));
            startService(new Intent(this, NodeService.class));
            awaitHost(STARTUP_TIMEOUT_MS);
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = dp(16);
        retryParams.bottomMargin = dp(16);
        root.addView(retry, retryParams);

        Button keys = new Button(this);
        keys.setText(getString(R.string.keys_open));
        keys.setOnClickListener(v -> startActivity(new Intent(this, KeysActivity.class)));
        LinearLayout.LayoutParams keysParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keysParams.bottomMargin = dp(16);
        root.addView(keys, keysParams);

        TextView label = new TextView(this);
        label.setText(getString(R.string.label_log));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(color(R.color.shell_text_dim));
        root.addView(label);

        TextView log = new TextView(this);
        log.setText(logTail(20));
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        log.setTextColor(color(R.color.shell_text_dim));
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setHorizontallyScrolling(true);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(log);
        root.addView(scroller);
        return root;
    }

    /** The tail of the host log, or a note that there is none yet. */
    private String logTail(int lines) {
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(Runtime.logFile(this)))) {
            for (String line; (line = reader.readLine()) != null; ) {
                tail.addLast(line);
                if (tail.size() > lines) tail.removeFirst();
            }
        } catch (IOException e) {
            return "(" + Runtime.logFile(this).getName() + ": " + e.getMessage() + ")";
        }
        return tail.isEmpty() ? "(empty)" : String.join("\n", tail);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @SuppressWarnings("deprecation")
    private int color(int id) {
        // getColor(int) is API 23; this app still starts at 21.
        return getResources().getColor(id);
    }

    /** A plain-text screen, for the states a web page cannot describe. */
    private TextView notice(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setTextColor(color(R.color.shell_text));
        view.setBackgroundColor(color(R.color.shell_bg));
        int pad = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        view.setPadding(pad, pad, pad, pad);
        return view;
    }

    /**
     * Back closes what is open before it leaves.
     *
     * <p>On a handheld the drawer and the details sheet ARE the navigation, and
     * a back press that skips straight past them — as it did while this only
     * consulted WebView history — feels like the app quitting at random. The
     * client knows what is open, so ask it: `mobile-layout` publishes
     * {@code window.__dshmBack()}, which closes the topmost layer and returns
     * true when it consumed the press.
     *
     * <p>The answer arrives asynchronously, so the key is always consumed here
     * and the decision made in the callback. The fallback order is the client's
     * own history, and only then leaving.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK || web == null) {
            return super.onKeyDown(keyCode, event);
        }
        web.evaluateJavascript(
                "(function(){try{return !!(window.__dshmBack && window.__dshmBack())}"
                        + "catch(e){return false}})()",
                value -> {
                    if ("true".equals(value)) return;
                    if (web == null) return;
                    if (web.canGoBack()) {
                        web.goBack();
                        return;
                    }
                    // Not finish(): the host is a foreground service that keeps
                    // running, and the next launch should come back to a live
                    // WebView rather than reloading the client from scratch.
                    moveTaskToBack(true);
                });
        return true;
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
