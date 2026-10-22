package com.kareem.headlink;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kareem.headlink.adb.AdbConnection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements PhoneLink.Listener {

    private static final String ACTION_USB_PERMISSION = "com.kareem.headlink.USB_PERMISSION";
    private static final String MAPS_PKG = "com.google.android.apps.maps";
    private static final int[] DPI_STEPS = {160, 200, 240, 280, 320};
    private static final int MAX_LOG_LINES = 500;
    private static final float MIN_RATIO = 0.2f;
    private static final float MAX_RATIO = 0.8f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private SharedPreferences prefs;

    private LinearLayout panesRow;
    private Pane leftPane;
    private Pane rightPane;
    private View divider;
    private Pane focused;
    private boolean split;
    private float ratio; // width share of the LEFT pane
    private boolean dragging;

    private TextView statusView;
    private ScrollView logScroll;
    private TextView logView;
    private LinearLayout appsPanel;
    private TextView targetLabel;
    private AppAdapter adapter;
    private Button dpiButton;
    private Button splitButton;
    private Button phoneButton;
    private FrameLayout lockOverlay;
    private TextView lockBadge;
    private boolean locked;
    private static final long UNLOCK_HOLD_MS = 3000;
    private Button systemButton;
    private Button favTabButton;
    private Button allTabButton;
    private EditText searchBox;
    private TextView emptyView;
    private boolean favTab = true;
    private boolean showSystem;

    private PhoneLink link;
    private boolean linkReady;
    private boolean permissionPending;
    private String wifiEnabledForUsb; // USB device that already got "tcpip" during this plug-in
    private ConnectivityManager.NetworkCallback wifiCallback;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                permissionPending = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    tryConnect();
                } else {
                    setStatus("USB permission denied - tap Connect to retry");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                ui.postDelayed(MainActivity.this::tryConnect, 800);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && d.getDeviceName().equals(wifiEnabledForUsb)) {
                    wifiEnabledForUsb = null;
                }
                if (link != null && link.device != null && d != null
                        && d.getDeviceName().equals(link.device.getDeviceName())) {
                    closeLink();
                    setStatus("Cable unplugged - switching to Wi-Fi...");
                    ui.postDelayed(MainActivity.this::tryConnect, 3000);
                }
            }
        }
    };

    // ================================================================== pane

    /** One screen area showing one virtual display of the phone. */
    private final class Pane implements SurfaceHolder.Callback {
        final String id;
        final String tag;
        final FrameLayout frame;
        final SurfaceView view;
        Surface surface;
        ScrcpySession session;
        int surfW;
        int surfH;
        boolean appLaunched;

        Pane(String id, String tag) {
            this.id = id;
            this.tag = tag;
            frame = new FrameLayout(MainActivity.this);
            frame.setPadding(dp(2), dp(2), dp(2), dp(2));
            view = new SurfaceView(MainActivity.this);
            view.getHolder().addCallback(this);
            view.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    focus(this);
                }
                return onVideoTouch(this, e);
            });
            frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
        }

        int dpi() {
            return prefs.getInt("dpi_" + id, "right".equals(id) ? 160 : 240);
        }

        void setDpi(int dpi) {
            prefs.edit().putInt("dpi_" + id, dpi).apply();
        }

        String lastApp() {
            return prefs.getString("app_" + id, "right".equals(id) ? MAPS_PKG : "");
        }

        void start() {
            if (session != null || surface == null || !linkReady || link == null || link.adb() == null
                    || surfW <= 0 || surfH <= 0 || frame.getVisibility() != View.VISIBLE) {
                return;
            }
            appLaunched = false;
            final ScrcpySession[] holder = new ScrcpySession[1];
            int bitRate = link.isWifi() ? 4_000_000 : 8_000_000; // phone hotspot is slower than USB
            // phone audio is global, so only the right pane (always present) carries it
            boolean withAudio = Pane.this == rightPane;
            ScrcpySession s = new ScrcpySession(link.adb(), surface, surfW & ~7, surfH & ~7, dpi(), bitRate,
                    withAudio, tag,
                    new ScrcpySession.Callback() {
                        @Override
                        public void onLog(String line) {
                            ui.post(() -> addLog(line));
                        }

                        @Override
                        public void onStarted(int w, int h) {
                            ui.post(() -> paneStarted(Pane.this, holder[0]));
                        }

                        @Override
                        public void onEnded(String reason) {
                            ui.post(() -> paneEnded(Pane.this, holder[0], reason));
                        }
                    });
            holder[0] = s;
            session = s;
            s.start();
        }

        void stop() {
            ScrcpySession s = session;
            session = null;
            if (s != null) {
                s.stop();
            }
        }

        void restart() {
            stop();
            start();
        }

        void launch(String pkg, boolean force) {
            if (session == null) {
                return;
            }
            // "+" makes scrcpy force-stop the app first, so a crashed app relaunches here
            session.startApp((force ? "+" : "") + pkg);
            appLaunched = true;
            prefs.edit().putString("app_" + id, pkg).apply();
        }

        void resizeToView() {
            if (session != null && surfW > 0 && surfH > 0) {
                session.resize(surfW & ~7, surfH & ~7);
            }
        }

        @Override
        public void surfaceCreated(SurfaceHolder h) {
            surface = h.getSurface();
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
            boolean sizeChanged = w != surfW || hh != surfH;
            surfW = w;
            surfH = hh;
            surface = h.getSurface();
            if (session == null) {
                start();
                return;
            }
            session.attachSurface(surface); // no-op if already rendering to this surface
            if (sizeChanged && !dragging) {
                resizeToView();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            // keep the phone session (and audio) running while HeadLink is in the background
            if (session != null) {
                session.detachSurface();
            }
            surface = null;
        }
    }

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("headlink", MODE_PRIVATE);
        showSystem = prefs.getBoolean("showSystem", false);
        split = prefs.getBoolean("split", true);
        ratio = prefs.getFloat("ratio", 0.5f);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
        registerWifiCallback();
        ui.postDelayed(this::tryConnect, 500);
    }

    /** When the head unit joins a Wi-Fi network (the phone hotspot), try to connect. */
    private void registerWifiCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                ui.postDelayed(MainActivity.this::tryConnect, 2500);
            }
        };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback);
        } catch (RuntimeException e) {
            wifiCallback = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            ui.postDelayed(this::tryConnect, 800);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onDestroy() {
        closeLink();
        ui.removeCallbacksAndMessages(null);
        if (wifiCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                cm.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
                // not registered
            }
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (locked && keyCode == KeyEvent.KEYCODE_BACK) {
            return true; // child lock: hardware Back does nothing either
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (appsPanel.getVisibility() == View.VISIBLE) {
                hideApps();
                return true;
            }
            if (focused != null && focused.session != null) {
                focused.session.key(KeyEvent.KEYCODE_BACK);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ================================================================== UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.rgb(16, 24, 32));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(bar, new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT));

        bar.addView(barButton("Back", v -> {
            if (focused != null && focused.session != null) {
                focused.session.key(KeyEvent.KEYCODE_BACK);
            }
        }), barParams());
        bar.addView(barButton("Apps", v -> toggleApps()), barParams());
        splitButton = barButton("", v -> toggleSplit());
        bar.addView(splitButton, barParams());
        dpiButton = barButton("", v -> cycleDpi());
        bar.addView(dpiButton, barParams());
        phoneButton = barButton("", v -> togglePhoneScreen());
        bar.addView(phoneButton, barParams());
        updatePhoneLabel();
        bar.addView(barButton("Connect", v -> {
            closeLink();
            tryConnect();
        }), barParams());
        bar.addView(barButton("Log", v -> toggleLog()), barParams());
        bar.addView(barButton("Lock", v -> setLocked(true)), barParams());

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // two panes with a draggable divider; Maps defaults to the right pane
        panesRow = new LinearLayout(this);
        panesRow.setOrientation(LinearLayout.HORIZONTAL);
        leftPane = new Pane("left", "L");
        rightPane = new Pane("right", "R");
        divider = buildDivider();
        panesRow.addView(leftPane.frame, new LinearLayout.LayoutParams(0, -1, ratio));
        panesRow.addView(divider, new LinearLayout.LayoutParams(dp(18), -1));
        panesRow.addView(rightPane.frame, new LinearLayout.LayoutParams(0, -1, 1f - ratio));
        content.addView(panesRow, new FrameLayout.LayoutParams(-1, -1));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(20);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(24), dp(24), dp(24));
        statusView.setBackgroundColor(Color.argb(200, 0, 0, 0));
        content.addView(statusView, new FrameLayout.LayoutParams(-1, -1));

        buildAppsPanel(content);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.argb(230, 0, 0, 0));
        logScroll.setVisibility(View.GONE);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(140, 255, 160));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logScroll.addView(logView);
        content.addView(logScroll, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout top = new FrameLayout(this);
        top.addView(root, new FrameLayout.LayoutParams(-1, -1));
        top.addView(buildLockOverlay(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(top);
        applySplit();
        focus(rightPane);
        setStatus("Connect the phone by USB (USB debugging on)");
    }

    /**
     * Child lock: a transparent layer over the whole app that swallows every touch.
     * The phone apps keep running and stay visible. Unlock = hold the lock badge for 3 seconds.
     */
    private View buildLockOverlay() {
        lockOverlay = new FrameLayout(this);
        lockOverlay.setVisibility(View.GONE);
        lockOverlay.setClickable(true);
        lockOverlay.setOnTouchListener((v, e) -> true);

        lockBadge = new TextView(this);
        lockBadge.setText("🔒  Hold 3 s to unlock");
        lockBadge.setTextColor(Color.WHITE);
        lockBadge.setTextSize(14);
        lockBadge.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(170, 0, 0, 0));
        bg.setCornerRadius(dp(20));
        lockBadge.setBackground(bg);
        final Runnable unlock = () -> setLocked(false);
        final Runnable[] tick = new Runnable[1];
        final long[] downAt = new long[1];
        tick[0] = () -> {
            long left = UNLOCK_HOLD_MS - (System.currentTimeMillis() - downAt[0]);
            if (left > 0) {
                lockBadge.setText("🔓  Keep holding… " + ((left + 999) / 1000));
                ui.postDelayed(tick[0], 200);
            }
        };
        lockBadge.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downAt[0] = System.currentTimeMillis();
                    ui.postDelayed(unlock, UNLOCK_HOLD_MS);
                    ui.post(tick[0]);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(unlock);
                    ui.removeCallbacks(tick[0]);
                    if (locked) {
                        lockBadge.setText("🔒  Hold 3 s to unlock");
                    }
                    return true;
                default:
                    return true;
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(10), dp(10), 0);
        lockOverlay.addView(lockBadge, lp);
        return lockOverlay;
    }

    private void setLocked(boolean on) {
        locked = on;
        if (on) {
            hideApps();
            logScroll.setVisibility(View.GONE);
            lockBadge.setText("🔒  Hold 3 s to unlock");
        }
        lockOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private View buildDivider() {
        FrameLayout d = new FrameLayout(this);
        d.setBackgroundColor(Color.rgb(20, 30, 40));
        View grip = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.rgb(120, 150, 170));
        g.setCornerRadius(dp(3));
        grip.setBackground(g);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(6), dp(64), Gravity.CENTER);
        d.addView(grip, gp);
        d.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int[] loc = new int[2];
                    panesRow.getLocationOnScreen(loc);
                    float x = e.getRawX() - loc[0];
                    ratio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, x / Math.max(1, panesRow.getWidth())));
                    applySplit();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    prefs.edit().putFloat("ratio", ratio).apply();
                    // let the new layout settle, then resize both virtual displays to their panes
                    ui.postDelayed(() -> {
                        leftPane.resizeToView();
                        rightPane.resizeToView();
                    }, 150);
                    return true;
                default:
                    return true;
            }
        });
        return d;
    }

    private void buildAppsPanel(FrameLayout content) {
        appsPanel = new LinearLayout(this);
        appsPanel.setOrientation(LinearLayout.VERTICAL);
        appsPanel.setBackgroundColor(Color.argb(240, 12, 18, 26));
        appsPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        appsPanel.setVisibility(View.GONE);
        appsPanel.setClickable(true); // taps must not fall through to the phone screens

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        favTabButton = smallButton("★ Favorites", v -> selectTab(true));
        allTabButton = smallButton("All apps", v -> selectTab(false));
        header.addView(favTabButton);
        header.addView(allTabButton, gapParams());
        searchBox = new EditText(this);
        searchBox.setHint("Search apps");
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Color.WHITE);
        searchBox.setHintTextColor(Color.GRAY);
        searchBox.setTextSize(16);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                refreshApps();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, -2, 1f);
        sp.setMargins(dp(12), 0, dp(12), 0);
        header.addView(searchBox, sp);
        systemButton = smallButton(systemLabel(), v -> {
            showSystem = !showSystem;
            prefs.edit().putBoolean("showSystem", showSystem).apply();
            systemButton.setText(systemLabel());
            refreshApps();
        });
        header.addView(systemButton);
        header.addView(smallButton("Close", v -> hideApps()), gapParams());
        appsPanel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        targetLabel = new TextView(this);
        targetLabel.setTextColor(Color.rgb(120, 220, 200));
        targetLabel.setTextSize(14);
        targetLabel.setPadding(dp(4), dp(6), dp(4), dp(2));
        appsPanel.addView(targetLabel, new LinearLayout.LayoutParams(-1, -2));

        emptyView = new TextView(this);
        emptyView.setTextColor(Color.LTGRAY);
        emptyView.setTextSize(16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), dp(24), dp(16), dp(8));
        emptyView.setVisibility(View.GONE);
        appsPanel.addView(emptyView, new LinearLayout.LayoutParams(-1, -2));

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(120));
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(adapter.getItem(pos), false));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showAppMenu(adapter.getItem(pos));
            return true;
        });
        appsPanel.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(appsPanel, new FrameLayout.LayoutParams(-1, -1));
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    private LinearLayout.LayoutParams gapParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        styleButton(b, false);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = barButton(text, l);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
    }

    private void styleButton(Button b, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(active ? Color.rgb(46, 140, 120) : Color.rgb(34, 52, 68));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
    }

    private String systemLabel() {
        return showSystem ? "Hide system apps" : "Show system apps";
    }

    private void setStatus(String text) {
        if (text == null || text.isEmpty()) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    // ================================================================== split / focus / dpi

    private void applySplit() {
        LinearLayout.LayoutParams lpL = (LinearLayout.LayoutParams) leftPane.frame.getLayoutParams();
        LinearLayout.LayoutParams lpR = (LinearLayout.LayoutParams) rightPane.frame.getLayoutParams();
        if (split) {
            leftPane.frame.setVisibility(View.VISIBLE);
            divider.setVisibility(View.VISIBLE);
            lpL.weight = ratio;
            lpR.weight = 1f - ratio;
        } else {
            leftPane.frame.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
            lpR.weight = 1f;
            if (focused == leftPane) {
                focus(rightPane);
            }
        }
        leftPane.frame.setLayoutParams(lpL);
        rightPane.frame.setLayoutParams(lpR);
        splitButton.setText(split ? "Split\nON" : "Split\nOFF");
        styleButton(splitButton, split);
    }

    private void toggleSplit() {
        split = !split;
        prefs.edit().putBoolean("split", split).apply();
        if (!split) {
            leftPane.stop(); // hidden pane must not keep a session (and the phone encoder) busy
        }
        applySplit();
        // the left SurfaceView is created/destroyed with its visibility, which starts/stops its session;
        // the right pane keeps running and gets a resize when its surface size changes
    }

    private void focus(Pane p) {
        focused = p;
        leftPane.frame.setBackgroundColor(p == leftPane && split ? Color.rgb(46, 140, 120) : Color.BLACK);
        rightPane.frame.setBackgroundColor(p == rightPane && split ? Color.rgb(46, 140, 120) : Color.BLACK);
        updateDpiLabel();
    }

    private String paneName(Pane p) {
        if (!split) {
            return "";
        }
        return p == leftPane ? "L " : "R ";
    }

    private void updateDpiLabel() {
        if (dpiButton != null && focused != null) {
            dpiButton.setText("DPI\n" + paneName(focused) + focused.dpi());
        }
    }

    private void cycleDpi() {
        Pane p = focused;
        if (p == null) {
            return;
        }
        int cur = p.dpi();
        int next = DPI_STEPS[0];
        for (int i = 0; i < DPI_STEPS.length; i++) {
            if (DPI_STEPS[i] == cur) {
                next = DPI_STEPS[(i + 1) % DPI_STEPS.length];
                break;
            }
        }
        p.setDpi(next);
        updateDpiLabel();
        if (p.session != null) {
            p.restart(); // DPI is fixed per virtual display; its last app reopens automatically
        }
    }

    private boolean phoneScreenOff() {
        return prefs.getBoolean("phoneScreenOff", true);
    }

    private void updatePhoneLabel() {
        phoneButton.setText(phoneScreenOff() ? "Phone\nscreen OFF" : "Phone\nscreen ON");
        styleButton(phoneButton, phoneScreenOff());
    }

    private void togglePhoneScreen() {
        boolean off = !phoneScreenOff();
        prefs.edit().putBoolean("phoneScreenOff", off).apply();
        updatePhoneLabel();
        applyPhoneScreen();
    }

    /** The right pane is always present, so it owns the phone-screen setting. */
    private void applyPhoneScreen() {
        if (rightPane.session != null) {
            rightPane.session.setPhoneScreen(!phoneScreenOff());
        }
    }

    // ================================================================== panels

    private void toggleApps() {
        if (appsPanel.getVisibility() == View.VISIBLE) {
            hideApps();
        } else {
            showApps();
        }
    }

    private void showApps() {
        logScroll.setVisibility(View.GONE);
        appsPanel.setVisibility(View.VISIBLE);
        targetLabel.setText(split
                ? "Opens on the " + (focused == leftPane ? "LEFT" : "RIGHT") + " screen (tap a screen first to choose)"
                : "Opens on the screen");
        refreshApps();
    }

    private void hideApps() {
        appsPanel.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        }
        searchBox.clearFocus();
    }

    private void selectTab(boolean favorites) {
        favTab = favorites;
        refreshApps();
    }

    private void toggleLog() {
        boolean show = logScroll.getVisibility() != View.VISIBLE;
        logScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            renderLog();
        }
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            sb.append(l).append('\n');
        }
        logView.setText(sb);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void addLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        if (logScroll.getVisibility() == View.VISIBLE) {
            renderLog();
        }
    }

    // ================================================================== apps

    private void refreshApps() {
        if (adapter == null) {
            return;
        }
        styleButton(favTabButton, favTab);
        styleButton(allTabButton, !favTab);
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<AppEntry> items = new ArrayList<>();
        int highlighted = 0;

        if (!q.isEmpty()) {
            for (AppEntry a : allApps) {
                if (a.name.toLowerCase(Locale.ROOT).contains(q) || a.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                    items.add(a);
                }
            }
            Collections.sort(items, (x, y) -> x.name.compareToIgnoreCase(y.name));
        } else if (favTab) {
            for (String pkg : favoritePackages()) {
                AppEntry a = findApp(pkg);
                if (a != null) {
                    items.add(a);
                }
            }
        } else {
            List<AppEntry> recentApps = new ArrayList<>();
            List<AppEntry> users = new ArrayList<>();
            List<AppEntry> systems = new ArrayList<>();
            for (String pkg : csv("recent")) {
                AppEntry a = findApp(pkg);
                if (a != null) {
                    recentApps.add(a);
                }
            }
            for (AppEntry a : allApps) {
                if (recentApps.contains(a)) {
                    continue;
                }
                if (a.system) {
                    if (showSystem) {
                        systems.add(a);
                    }
                } else {
                    users.add(a);
                }
            }
            Collections.sort(users, (x, y) -> x.name.compareToIgnoreCase(y.name));
            Collections.sort(systems, (x, y) -> x.name.compareToIgnoreCase(y.name));
            items.addAll(recentApps);
            items.addAll(users);
            items.addAll(systems);
            highlighted = recentApps.size();
        }

        if (allApps.isEmpty()) {
            emptyView.setText(linkReady ? "Loading app list..." : "Not connected");
            emptyView.setVisibility(View.VISIBLE);
        } else if (items.isEmpty()) {
            emptyView.setText(!q.isEmpty() ? "No app matches \"" + q + "\""
                    : "No favorites yet - long-press an app in \"All apps\" and choose Add to Favorites");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
        adapter.setItems(items, highlighted);
    }

    private AppEntry findApp(String pkg) {
        for (AppEntry a : allApps) {
            if (a.packageName.equals(pkg)) {
                return a;
            }
        }
        return null;
    }

    private List<String> csv(String key) {
        List<String> out = new ArrayList<>();
        for (String x : prefs.getString(key, "").split(",")) {
            if (!x.isEmpty()) {
                out.add(x);
            }
        }
        return out;
    }

    private List<String> favoritePackages() {
        return csv("favs");
    }

    private boolean isFavorite(String pkg) {
        return favoritePackages().contains(pkg);
    }

    private void setFavorite(String pkg, boolean fav) {
        List<String> favs = favoritePackages();
        favs.remove(pkg);
        if (fav) {
            favs.add(pkg);
        }
        prefs.edit().putString("favs", String.join(",", favs)).apply();
        refreshApps();
    }

    /** First time the app list arrives: put Maps and YouTube Morphe in Favorites. */
    private void seedFavoritesOnce() {
        if (prefs.getBoolean("favSeeded", false) || allApps.isEmpty()) {
            return;
        }
        List<String> favs = favoritePackages();
        for (AppEntry a : allApps) {
            String n = a.name.toLowerCase(Locale.ROOT);
            boolean maps = a.packageName.equals(MAPS_PKG) || n.equals("maps") || n.equals("google maps");
            boolean morphe = n.contains("morphe");
            if ((maps || morphe) && !favs.contains(a.packageName)) {
                favs.add(a.packageName);
            }
        }
        prefs.edit().putString("favs", String.join(",", favs)).putBoolean("favSeeded", true).apply();
    }

    private void showAppMenu(AppEntry app) {
        if (app == null) {
            return;
        }
        boolean fav = isFavorite(app.packageName);
        String[] options = {
                fav ? "Remove from Favorites" : "Add to Favorites",
                "Force restart (use if it crashed or won't show)"
        };
        new AlertDialog.Builder(this)
                .setTitle(app.name)
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        setFavorite(app.packageName, !fav);
                    } else {
                        launch(app, true);
                    }
                })
                .show();
    }

    private void launch(AppEntry app, boolean forceRestart) {
        Pane p = focused;
        if (app == null || p == null || p.session == null) {
            return;
        }
        p.launch(app.packageName, forceRestart);
        List<String> recent = csv("recent");
        recent.remove(app.packageName);
        recent.add(0, app.packageName);
        while (recent.size() > 8) {
            recent.remove(recent.size() - 1);
        }
        prefs.edit().putString("recent", String.join(",", recent)).apply();
        hideApps();
    }

    private final class AppAdapter extends BaseAdapter {
        private List<AppEntry> items = new ArrayList<>();
        private int recentCount;

        void setItems(List<AppEntry> list, int recent) {
            items = list;
            recentCount = recent;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout tile;
            TextView letter;
            TextView name;
            if (convertView instanceof LinearLayout) {
                tile = (LinearLayout) convertView;
                letter = (TextView) tile.getChildAt(0);
                name = (TextView) tile.getChildAt(1);
            } else {
                tile = new LinearLayout(MainActivity.this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setPadding(dp(4), dp(8), dp(4), dp(8));
                letter = new TextView(MainActivity.this);
                letter.setTextColor(Color.WHITE);
                letter.setTextSize(26);
                letter.setGravity(Gravity.CENTER);
                tile.addView(letter, new LinearLayout.LayoutParams(dp(64), dp(64)));
                name = new TextView(MainActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(13);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            }
            AppEntry a = items.get(position);
            String first = a.name.isEmpty() ? "?" : a.name.substring(0, a.name.offsetByCodePoints(0, 1)).toUpperCase();
            letter.setText(first);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            float hue = (Math.abs(a.packageName.hashCode()) % 360);
            circle.setColor(Color.HSVToColor(new float[]{hue, 0.55f, 0.75f}));
            if (position < recentCount) {
                circle.setStroke(dp(3), Color.rgb(255, 210, 80));
            }
            letter.setBackground(circle);
            name.setText(isFavorite(a.packageName) ? "★ " + a.name : a.name);
            return tile;
        }
    }

    // ================================================================== USB / link

    private UsbDevice findPhone() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (AdbConnection.findAdbInterface(d) != null) {
                return d;
            }
        }
        return null;
    }

    private void tryConnect() {
        if (link != null || isFinishing()) {
            return;
        }
        UsbDevice d = findPhone();
        if (d == null) {
            connectWifi();
            return;
        }
        if (!usb.hasPermission(d)) {
            if (!permissionPending) {
                permissionPending = true;
                Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
            }
            setStatus("Allow HeadLink to access the phone (USB)");
            return;
        }
        logLines.clear();
        boolean enableWifi = !d.getDeviceName().equals(wifiEnabledForUsb);
        link = new PhoneLink(this, usb, d, enableWifi, this);
        link.start();
    }

    /** No cable: connect to the phone over its hotspot (the hotspot gateway is the phone). */
    private void connectWifi() {
        String host = wifiGateway();
        String last = prefs.getString("lastHost", "");
        if (host == null && last.isEmpty()) {
            setStatus("Plug the phone in by USB once (after every phone restart),\n"
                    + "then turn on the phone hotspot and connect the head unit to it.");
            return;
        }
        if (host == null) {
            host = last;
        }
        addLog("Trying Wi-Fi " + host + ":" + PhoneLink.TCP_PORT);
        link = new PhoneLink(this, host, this);
        link.start();
    }

    @SuppressWarnings("deprecation")
    private String wifiGateway() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled() || wm.getDhcpInfo() == null) {
            return null;
        }
        int g = wm.getDhcpInfo().gateway; // little-endian IPv4
        if (g == 0) {
            return null;
        }
        return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
    }

    private void closeLink() {
        linkReady = false;
        leftPane.stop();
        rightPane.stop();
        if (link != null) {
            link.close();
            link = null;
        }
    }

    private void paneStarted(Pane p, ScrcpySession s) {
        if (p.session != s) {
            return;
        }
        setStatus(null);
        if (p == rightPane && phoneScreenOff()) {
            ui.postDelayed(this::applyPhoneScreen, 1500);
        }
        if (!p.appLaunched) {
            String app = p.lastApp();
            if (!app.isEmpty()) {
                p.launch(app, false);
            } else {
                focus(p);
                showApps();
            }
        }
    }

    private void paneEnded(Pane p, ScrcpySession s, String reason) {
        addLog("[" + p.tag + "] ended: " + reason);
        if (p.session != s) {
            return;
        }
        p.session = null;
        logScroll.setVisibility(View.VISIBLE);
        renderLog();
        setStatus((split ? (p == leftPane ? "Left" : "Right") + " screen stopped: " : "Disconnected: ")
                + reason + "\nTap Connect to retry, or Log for details");
    }

    // ---- PhoneLink.Listener (worker thread) ----

    @Override
    public void onLinkStatus(String text) {
        ui.post(() -> {
            addLog("[status] " + text);
            setStatus(text);
        });
    }

    @Override
    public void onLinkLog(String line) {
        ui.post(() -> addLog(line));
    }

    @Override
    public void onLinkReady() {
        ui.post(() -> {
            if (link == null) {
                return;
            }
            linkReady = true;
            if (link.isWifi()) {
                prefs.edit().putString("lastHost", link.host).apply();
            }
            setStatus("Starting screens...");
            leftPane.start();
            rightPane.start();
        });
    }

    @Override
    public void onLinkApps(List<AppEntry> apps) {
        ui.post(() -> {
            allApps.clear();
            allApps.addAll(apps);
            seedFavoritesOnce();
            if (favoritePackages().isEmpty()) {
                favTab = false;
            }
            refreshApps();
        });
    }

    @Override
    public void onLinkFailed(String reason) {
        ui.post(() -> {
            addLog("Link failed: " + reason);
            boolean wifi = link != null && link.isWifi();
            closeLink();
            if (wifi) {
                setStatus("Waiting for the phone over Wi-Fi...\n"
                        + "Phone hotspot on + head unit connected to it.\n"
                        + "After a phone restart, plug the USB cable in once.");
                ui.postDelayed(this::tryConnect, 8000);
                return;
            }
            logScroll.setVisibility(View.VISIBLE);
            renderLog();
            setStatus("Disconnected: " + reason + "\nTap Connect to retry, or Log for details");
        });
    }

    @Override
    public void onLinkWifiEnabled() {
        ui.post(() -> {
            if (link != null && link.device != null) {
                wifiEnabledForUsb = link.device.getDeviceName();
            }
            closeLink();
            setStatus("Wi-Fi mode is on. Reconnecting...\nYou can unplug the cable any time.");
            ui.postDelayed(this::tryConnect, 3000);
        });
    }

    // ================================================================== touch

    private boolean onVideoTouch(Pane p, MotionEvent e) {
        ScrcpySession s = p.session;
        if (s == null) {
            return true;
        }
        int vw = s.getVideoWidth();
        int vh = s.getVideoHeight();
        if (vw == 0 || vh == 0) {
            return true;
        }
        float sx = (float) vw / p.view.getWidth();
        float sy = (float) vh / p.view.getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                s.touch(0, e.getPointerId(i), (int) (e.getX(i) * sx), (int) (e.getY(i) * sy));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                s.touch(1, e.getPointerId(i), (int) (e.getX(i) * sx), (int) (e.getY(i) * sy));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    s.touch(2, e.getPointerId(i), (int) (e.getX(i) * sx), (int) (e.getY(i) * sy));
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    s.touch(1, e.getPointerId(i), (int) (e.getX(i) * sx), (int) (e.getY(i) * sy));
                }
                break;
            default:
                break;
        }
        return true;
    }
}
