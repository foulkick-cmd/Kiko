package com.kareem.headlink;

import android.app.Activity;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
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

public class MainActivity extends Activity implements SurfaceHolder.Callback, ScrcpySession.Callback {

    private static final String ACTION_USB_PERMISSION = "com.kareem.headlink.USB_PERMISSION";
    private static final int[] DPI_STEPS = {160, 200, 240, 280, 320};
    private static final int MAX_LOG_LINES = 400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private SharedPreferences prefs;

    private SurfaceView surfaceView;
    private Surface surface;
    private TextView statusView;
    private ScrollView logScroll;
    private TextView logView;
    private LinearLayout appsPanel;
    private AppAdapter adapter;
    private Button dpiButton;
    private Button systemButton;

    private ScrcpySession session;
    private boolean permissionPending;
    private boolean appLaunched;
    private boolean showSystem;
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
                if (session != null && d != null && d.getDeviceName().equals(session.device.getDeviceName())) {
                    stopSession();
                    setStatus("Phone disconnected - plug it in again");
                }
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usb = (UsbManager) getSystemService(Context.USB_SERVICE);
        prefs = getSharedPreferences("headlink", MODE_PRIVATE);
        showSystem = prefs.getBoolean("showSystem", false);
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
        stopSession();
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
            // not registered
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // hardware BACK on the head unit goes to the phone app while mirroring
        if (keyCode == KeyEvent.KEYCODE_BACK && session != null && appsPanel.getVisibility() != View.VISIBLE) {
            session.key(KeyEvent.KEYCODE_BACK);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && appsPanel.getVisibility() == View.VISIBLE) {
            appsPanel.setVisibility(View.GONE);
            return true;
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

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);

        // side bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.rgb(16, 24, 32));
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(bar, new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.MATCH_PARENT));

        bar.addView(barButton("Back", v -> {
            if (session != null) {
                session.key(KeyEvent.KEYCODE_BACK);
            }
        }), barParams());
        bar.addView(barButton("Apps", v -> toggleApps()), barParams());
        bar.addView(barButton("Connect", v -> {
            stopSession();
            tryConnect();
        }), barParams());
        dpiButton = barButton("DPI\n" + currentDpi(), v -> cycleDpi());
        bar.addView(dpiButton, barParams());
        bar.addView(barButton("Log", v -> toggleLog()), barParams());

        // content area
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener((v, e) -> onVideoTouch(e));
        content.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(20);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(24), dp(24), dp(24));
        statusView.setBackgroundColor(Color.argb(200, 0, 0, 0));
        content.addView(statusView, new FrameLayout.LayoutParams(-1, -1));

        // apps panel
        appsPanel = new LinearLayout(this);
        appsPanel.setOrientation(LinearLayout.VERTICAL);
        appsPanel.setBackgroundColor(Color.argb(240, 12, 18, 26));
        appsPanel.setPadding(dp(8), dp(8), dp(8), dp(8));
        appsPanel.setVisibility(View.GONE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Phone apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        systemButton = smallButton(systemLabel(), v -> {
            showSystem = !showSystem;
            prefs.edit().putBoolean("showSystem", showSystem).apply();
            systemButton.setText(systemLabel());
            refreshApps();
        });
        header.addView(systemButton);
        header.addView(smallButton("Close", v -> appsPanel.setVisibility(View.GONE)));
        appsPanel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(120));
        grid.setVerticalSpacing(dp(8));
        grid.setHorizontalSpacing(dp(8));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(adapter.getItem(pos)));
        appsPanel.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(appsPanel, new FrameLayout.LayoutParams(-1, -1));

        // log overlay
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

        setContentView(root);
        setStatus("Connect the phone by USB (USB debugging on)");
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(34, 52, 68));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = barButton(text, l);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
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

    private void toggleApps() {
        appsPanel.setVisibility(appsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        logScroll.setVisibility(View.GONE);
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

    private int currentDpi() {
        return prefs.getInt("dpi", 240);
    }

    private void cycleDpi() {
        int cur = currentDpi();
        int next = DPI_STEPS[0];
        for (int i = 0; i < DPI_STEPS.length; i++) {
            if (DPI_STEPS[i] == cur) {
                next = DPI_STEPS[(i + 1) % DPI_STEPS.length];
                break;
            }
        }
        prefs.edit().putInt("dpi", next).apply();
        dpiButton.setText("DPI\n" + next);
        if (session != null) {
            stopSession();
            tryConnect();
        }
    }

    // ------------------------------------------------------------------ apps

    private void refreshApps() {
        List<String> recent = recentPackages();
        List<AppEntry> recentApps = new ArrayList<>();
        List<AppEntry> users = new ArrayList<>();
        List<AppEntry> systems = new ArrayList<>();
        for (String pkg : recent) {
            for (AppEntry a : allApps) {
                if (a.packageName.equals(pkg)) {
                    recentApps.add(a);
                    break;
                }
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
        List<AppEntry> items = new ArrayList<>(recentApps);
        items.addAll(users);
        items.addAll(systems);
        adapter.setItems(items, recentApps.size());
    }

    private List<String> recentPackages() {
        String csv = prefs.getString("recent", "");
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private void launch(AppEntry app) {
        if (session == null || app == null) {
            return;
        }
        session.startApp(app.packageName);
        appLaunched = true;
        List<String> recent = recentPackages();
        recent.remove(app.packageName);
        recent.add(0, app.packageName);
        while (recent.size() > 8) {
            recent.remove(recent.size() - 1);
        }
        prefs.edit().putString("recent", String.join(",", recent)).apply();
        appsPanel.setVisibility(View.GONE);
        refreshApps();
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
            name.setText(a.name);
            return tile;
        }
    }

    // ------------------------------------------------------------------ USB / session

    private UsbDevice findPhone() {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (AdbConnection.findAdbInterface(d) != null) {
                return d;
            }
        }
        return null;
    }

    private void tryConnect() {
        if (session != null || surface == null || isFinishing()) {
            return;
        }
        UsbDevice d = findPhone();
        if (d == null) {
            setStatus("Connect the phone by USB and enable USB debugging\n(no ADB interface found)");
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
        int w = surfaceView.getWidth() & ~7;
        int h = surfaceView.getHeight() & ~7;
        if (w <= 0 || h <= 0) {
            ui.postDelayed(this::tryConnect, 300);
            return;
        }
        logLines.clear();
        appLaunched = false;
        addLog("Virtual display " + w + "x" + h + " @" + currentDpi() + "dpi");
        session = new ScrcpySession(this, usb, d, surface, w, h, currentDpi(), this);
        session.start();
    }

    private void stopSession() {
        ScrcpySession s = session;
        session = null;
        if (s != null) {
            s.stop();
        }
    }

    private boolean onVideoTouch(MotionEvent e) {
        ScrcpySession s = session;
        if (s == null) {
            return true;
        }
        int vw = s.getVideoWidth();
        int vh = s.getVideoHeight();
        if (vw == 0 || vh == 0) {
            return true;
        }
        float sx = (float) vw / surfaceView.getWidth();
        float sy = (float) vh / surfaceView.getHeight();
        int am = e.getActionMasked();
        switch (am) {
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

    // ------------------------------------------------------------------ SurfaceHolder

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        ui.postDelayed(this::tryConnect, 300);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // size is read at connect time
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopSession();
        surface = null;
    }

    // ------------------------------------------------------------------ session callbacks (worker threads)

    private void addLog(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        if (logScroll.getVisibility() == View.VISIBLE) {
            renderLog();
        }
    }

    @Override
    public void onStatus(String text) {
        ui.post(() -> {
            addLog("[status] " + text);
            setStatus(text);
        });
    }

    @Override
    public void onLog(String line) {
        ui.post(() -> addLog(line));
    }

    @Override
    public void onApps(List<AppEntry> apps) {
        ui.post(() -> {
            allApps.clear();
            allApps.addAll(apps);
            refreshApps();
        });
    }

    @Override
    public void onStarted(int width, int height) {
        ui.post(() -> {
            setStatus(null);
            if (!appLaunched) {
                appsPanel.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onEnded(String reason) {
        ui.post(() -> {
            addLog("Ended: " + reason);
            session = null;
            logScroll.setVisibility(View.VISIBLE);
            renderLog();
            setStatus("Disconnected: " + reason + "\nTap Connect to retry, or Log for details");
        });
    }
}
