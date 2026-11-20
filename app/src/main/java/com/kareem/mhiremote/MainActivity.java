package com.kareem.mhiremote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen IR remote for Mitsubishi Heavy SRK-CR units (88-bit protocol). */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0D1117");
    private static final int CARD = Color.parseColor("#161B22");
    private static final int TILE = Color.parseColor("#1C222B");
    private static final int STROKE = Color.parseColor("#262D37");
    private static final int TEXT = Color.parseColor("#F0F3F6");
    private static final int MUTED = Color.parseColor("#7D8590");
    private static final int ACCENT = Color.parseColor("#4FC3F7");
    private static final int ACCENT_DIM = Color.parseColor("#123447");
    private static final int ON_GREEN = Color.parseColor("#2EA043");
    private static final int OFF_RED = Color.parseColor("#DA3633");

    private final MhiCodec.State st = new MhiCodec.State();
    private int protocol = MhiCodec.PROTO_ZJ;
    private final TextView[] protoChips = new TextView[4];
    private ConsumerIrManager ir;
    private SharedPreferences prefs;

    private TextView powerChip, modeBig, tempBig, tempUnit, infoFan, infoV, infoH, statusLine;
    private TextView chipHiPower, chipEcono, chip3d, chipClean;
    private LinearLayout tileMode, tileFan, tileHiPower, tileEcono, tileV, tileH, tile3d, tileClean;
    private TextView valMode, valFan, valV, valH;
    private TextView powerBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        load();
        setContentView(buildUi());
        refresh();
        if (ir == null || !ir.hasIrEmitter()) statusLine.setText("No IR emitter on this phone");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void setPressable(View v, int color, float r, int stroke) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                rounded(color, r, stroke), rounded(Color.WHITE, r, 0)));
        v.setClickable(true);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif" + (bold ? "-medium" : ""), Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams weighted(float w) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Air Conditioner", 20, TEXT, true));
        TextView sub = text("Mitsubishi Heavy  ·  SRK CR", 12, MUTED, false);
        sub.setPadding(0, dp(4), 0, 0);
        titles.addView(sub);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        powerChip = text("OFF", 12, TEXT, true);
        powerChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        header.addView(powerChip);
        root.addView(header);

        // Protocol picker
        LinearLayout protoRow = new LinearLayout(this);
        protoRow.setGravity(Gravity.CENTER_VERTICAL);
        protoRow.setPadding(0, dp(14), 0, 0);
        TextView pl = text("PROTOCOL", 10, MUTED, true);
        pl.setLetterSpacing(0.15f);
        protoRow.addView(pl, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView c = text(MhiCodec.PROTO_NAMES[i], 12, TEXT, true);
            c.setGravity(Gravity.CENTER);
            c.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                protocol = idx;
                save();
                refresh();
                statusLine.setText("Protocol " + MhiCodec.PROTO_NAMES[idx] + " selected");
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            protoRow.addView(c, lp);
            protoChips[i] = c;
        }
        root.addView(protoRow);

        // Display card
        LinearLayout display = new LinearLayout(this);
        display.setOrientation(LinearLayout.VERTICAL);
        display.setGravity(Gravity.CENTER);
        display.setBackground(rounded(CARD, dp(24), STROKE));
        display.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams dlp = weighted(4.2f);
        dlp.topMargin = dp(12);
        root.addView(display, dlp);

        modeBig = text("COOL", 14, ACCENT, true);
        modeBig.setLetterSpacing(0.25f);
        display.addView(modeBig);

        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setGravity(Gravity.CENTER);
        tempBig = text("24", 96, TEXT, false);
        tempBig.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        tempUnit = text("°C", 28, MUTED, false);
        tempUnit.setPadding(dp(4), 0, 0, dp(48));
        tempRow.addView(tempBig);
        tempRow.addView(tempUnit);
        display.addView(tempRow);

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(4), 0, dp(14));
        infoFan = infoItem(info, "FAN");
        infoV = infoItem(info, "UP / DOWN");
        infoH = infoItem(info, "LEFT / RIGHT");
        display.addView(info, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER);
        chipHiPower = chip(chips, "HI POWER");
        chipEcono = chip(chips, "ECONO");
        chip3d = chip(chips, "3D AUTO");
        chipClean = chip(chips, "CLEAN");
        display.addView(chips);

        // Temperature controls
        LinearLayout tempCtl = new LinearLayout(this);
        tempCtl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = weighted(1.3f);
        tlp.topMargin = dp(14);
        root.addView(tempCtl, tlp);
        tempCtl.addView(roundKey("−", v -> { tempStep(-1); send(); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        TextView tl = text("TEMP", 13, MUTED, true);
        tl.setGravity(Gravity.CENTER);
        tl.setLetterSpacing(0.2f);
        tempCtl.addView(tl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        tempCtl.addView(roundKey("+", v -> { tempStep(1); send(); }),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Function grid: 2 rows x 4
        LinearLayout g1 = new LinearLayout(this);
        LinearLayout g2 = new LinearLayout(this);
        LinearLayout.LayoutParams g1lp = weighted(1.25f);
        g1lp.topMargin = dp(14);
        LinearLayout.LayoutParams g2lp = weighted(1.25f);
        g2lp.topMargin = dp(10);
        root.addView(g1, g1lp);
        root.addView(g2, g2lp);

        valMode = new TextView(this);
        valFan = new TextView(this);
        valV = new TextView(this);
        valH = new TextView(this);
        tileMode = tile(g1, "MODE", valMode, v -> { cycleMode(); send(); });
        tileFan = tile(g1, "FAN", valFan, v -> { cycleFan(); send(); });
        tileHiPower = tile(g1, "HI POWER", null, v -> { toggleBoost(MhiCodec.BOOST_HIPOWER); send(); });
        tileEcono = tile(g1, "ECONO", null, v -> { toggleBoost(MhiCodec.BOOST_ECONO); send(); });
        tileV = tile(g2, "▲▼ FLOW", valV, v -> { st.swingV = (st.swingV + 1) % 6; send(); });
        tileH = tile(g2, "◀▶ FLOW", valH, v -> { if (!MhiCodec.hasSwingH(protocol)) { notAvailable("◀▶ FLOW"); return; } st.threeD = false; st.swingH = (st.swingH + 1) % 6; send(); });
        tile3d = tile(g2, "3D AUTO", null, v -> { if (!MhiCodec.has3D(protocol)) { notAvailable("3D AUTO"); return; } st.threeD = !st.threeD; send(); });
        tileClean = tile(g2, "CLEAN", null, v -> { st.clean = !st.clean; send(); });

        // Power
        powerBtn = text("TURN ON", 16, Color.WHITE, true);
        powerBtn.setGravity(Gravity.CENTER);
        powerBtn.setLetterSpacing(0.15f);
        powerBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            st.power = !st.power;
            send();
        });
        LinearLayout.LayoutParams plp = weighted(1.0f);
        plp.topMargin = dp(14);
        root.addView(powerBtn, plp);

        statusLine = text("Point the phone at the AC", 10, MUTED, false);
        statusLine.setTypeface(Typeface.MONOSPACE);
        statusLine.setGravity(Gravity.CENTER);
        statusLine.setPadding(0, dp(10), 0, 0);
        root.addView(statusLine);

        return root;
    }

    private TextView infoItem(LinearLayout parent, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView l = text(label, 10, MUTED, true);
        l.setLetterSpacing(0.1f);
        TextView v = text("", 14, TEXT, true);
        v.setPadding(0, dp(4), 0, 0);
        box.addView(l);
        box.addView(v);
        parent.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return v;
    }

    private TextView chip(LinearLayout parent, String label) {
        TextView c = text(label, 10, MUTED, true);
        c.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        parent.addView(c, lp);
        return c;
    }

    private TextView roundKey(String label, View.OnClickListener l) {
        TextView k = text(label, 34, TEXT, false);
        k.setGravity(Gravity.CENTER);
        setPressable(k, TILE, dp(20), STROKE);
        k.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        return k;
    }

    private LinearLayout tile(LinearLayout parent, String label, TextView value, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        TextView lab = text(label, 11, TEXT, true);
        lab.setGravity(Gravity.CENTER);
        t.addView(lab);
        if (value != null) {
            value.setTextSize(11);
            value.setTextColor(ACCENT);
            value.setGravity(Gravity.CENTER);
            value.setPadding(0, dp(5), 0, 0);
            t.addView(value);
        }
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        parent.addView(t, lp);
        return t;
    }

    // ------------------------------------------------------------------ logic

    private void cycleMode() {
        int[] order = {MhiCodec.MODE_AUTO, MhiCodec.MODE_COOL, MhiCodec.MODE_DRY, MhiCodec.MODE_FAN};
        int i = 0;
        for (int k = 0; k < order.length; k++) if (order[k] == st.mode) i = k;
        st.mode = order[(i + 1) % order.length];
    }

    private void cycleFan() {
        st.boost = MhiCodec.BOOST_NONE;
        int[] order = {MhiCodec.FAN_AUTO, MhiCodec.FAN_HI, MhiCodec.FAN_MED, MhiCodec.FAN_LO};
        int i = 0;
        for (int k = 0; k < order.length; k++) if (order[k] == st.fan) i = k;
        st.fan = order[(i + 1) % order.length];
    }

    private void notAvailable(String key) {
        statusLine.setText(key + " is not available in " + MhiCodec.PROTO_NAMES[protocol]);
    }

    private void toggleBoost(int which) {
        st.boost = (st.boost == which) ? MhiCodec.BOOST_NONE : which;
    }

    private void tempStep(int d) {
        st.temp = Math.max(MhiCodec.MIN_TEMP, Math.min(MhiCodec.MAX_TEMP, st.temp + d));
    }

    private void send() {
        save();
        refresh();
        int[] bytes = MhiCodec.encode(protocol, st);
        if (ir == null || !ir.hasIrEmitter()) {
            statusLine.setText("No IR emitter on this phone");
            return;
        }
        try {
            ir.transmit(MhiCodec.FREQ, MhiCodec.toPattern(bytes));
            statusLine.setText(MhiCodec.PROTO_NAMES[protocol] + "  " + MhiCodec.hex(bytes));
        } catch (Exception e) {
            statusLine.setText("IR error: " + e.getMessage());
        }
    }

    private static final String[] MODE_NAMES = {"AUTO", "COOL", "DRY", "FAN"};
    private static final String[] FAN_NAMES = {"Auto", "High", "Medium", "Low"};

    private void refresh() {
        boolean on = st.power;
        powerChip.setText(on ? "RUNNING" : "OFF");
        powerChip.setBackground(rounded(on ? Color.parseColor("#12321B") : Color.parseColor("#2A1416"), dp(14), 0));
        powerChip.setTextColor(on ? Color.parseColor("#56D364") : Color.parseColor("#F85149"));

        modeBig.setText(MODE_NAMES[st.mode]);
        boolean showTemp = st.mode != MhiCodec.MODE_AUTO;
        tempBig.setText(showTemp ? String.valueOf(st.temp) : "--");
        tempUnit.setVisibility(showTemp ? View.VISIBLE : View.INVISIBLE);
        float alpha = on ? 1f : 0.45f;
        tempBig.setAlpha(alpha);
        modeBig.setAlpha(alpha);

        String fan = st.boost == MhiCodec.BOOST_HIPOWER ? "Hi Power"
                : st.boost == MhiCodec.BOOST_ECONO ? "Econo" : FAN_NAMES[st.fan];
        infoFan.setText(fan);
        infoV.setText(MhiCodec.SWING_V_NAMES[st.swingV]);
        infoH.setText(st.threeD ? "3D Auto" : MhiCodec.SWING_H_NAMES[st.swingH]);

        styleChip(chipHiPower, st.boost == MhiCodec.BOOST_HIPOWER);
        styleChip(chipEcono, st.boost == MhiCodec.BOOST_ECONO);
        styleChip(chip3d, st.threeD);
        styleChip(chipClean, st.clean);

        valMode.setText(MODE_NAMES[st.mode]);
        valFan.setText(fan);
        valV.setText(MhiCodec.SWING_V_NAMES[st.swingV]);
        valH.setText(st.threeD ? "3D" : MhiCodec.SWING_H_NAMES[st.swingH]);

        styleTile(tileMode, false);
        styleTile(tileFan, false);
        styleTile(tileV, false);
        styleTile(tileH, false);
        styleTile(tileHiPower, st.boost == MhiCodec.BOOST_HIPOWER);
        styleTile(tileEcono, st.boost == MhiCodec.BOOST_ECONO);
        styleTile(tile3d, st.threeD);
        styleTile(tileClean, st.clean);

        for (int i = 0; i < 4; i++) {
            boolean a = i == protocol;
            protoChips[i].setBackground(rounded(a ? ACCENT_DIM : TILE, dp(17), a ? ACCENT : STROKE));
            protoChips[i].setTextColor(a ? ACCENT : MUTED);
        }
        boolean h = MhiCodec.hasSwingH(protocol);
        boolean d = MhiCodec.has3D(protocol);
        tileH.setAlpha(h ? 1f : 0.3f);
        tile3d.setAlpha(d ? 1f : 0.3f);
        chip3d.setAlpha(d ? 1f : 0.3f);
        if (!h) infoH.setText("n/a");

        powerBtn.setText(on ? "TURN OFF" : "TURN ON");
        setPressable(powerBtn, on ? OFF_RED : ON_GREEN, dp(20), 0);
    }

    private void styleChip(TextView c, boolean active) {
        c.setBackground(rounded(active ? ACCENT_DIM : Color.TRANSPARENT, dp(12), active ? ACCENT : STROKE));
        c.setTextColor(active ? ACCENT : MUTED);
    }

    private void styleTile(LinearLayout t, boolean active) {
        setPressable(t, active ? ACCENT_DIM : TILE, dp(18), active ? ACCENT : STROKE);
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        prefs.edit()
                .putInt("proto4", protocol)
                .putBoolean("power", st.power).putInt("mode", st.mode).putInt("temp", st.temp)
                .putInt("fan", st.fan).putInt("boost", st.boost)
                .putInt("swingV", st.swingV).putInt("swingH", st.swingH)
                .putBoolean("threeD", st.threeD).putBoolean("clean", st.clean)
                .apply();
    }

    private void load() {
        protocol = prefs.getInt("proto4", MhiCodec.PROTO_ZJ);
        st.power = prefs.getBoolean("power", false);
        st.mode = prefs.getInt("mode", MhiCodec.MODE_COOL);
        st.temp = prefs.getInt("temp", 24);
        st.fan = prefs.getInt("fan", MhiCodec.FAN_AUTO);
        st.boost = prefs.getInt("boost", MhiCodec.BOOST_NONE);
        st.swingV = prefs.getInt("swingV", 0);
        st.swingH = prefs.getInt("swingH", 0);
        st.threeD = prefs.getBoolean("threeD", false);
        st.clean = prefs.getBoolean("clean", false);
    }
}
