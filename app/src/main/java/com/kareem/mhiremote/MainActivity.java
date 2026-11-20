package com.kareem.mhiremote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Replica of the SRK-CR series wireless remote (catalog HB91-15R02E, page 3).
 * Every press sends the full state, exactly like the original remote.
 */
public class MainActivity extends Activity {

    private static final int BODY = Color.parseColor("#F1F0EC");
    private static final int PANEL = Color.parseColor("#B9BBBD");
    private static final int LCD = Color.parseColor("#C9D3BE");
    private static final int LCD_ON = Color.parseColor("#1E2418");
    private static final int LCD_OFF = Color.parseColor("#AAB59F");
    private static final int KEY = Color.parseColor("#E9E9E6");
    private static final int KEY_PRESSED = Color.parseColor("#C8C8C4");
    private static final int KEY_TEAL = Color.parseColor("#9FDCCF");
    private static final int LABEL = Color.parseColor("#2B2B2B");

    private final MhiCodec.State st = new MhiCodec.State();
    private int protocol = MhiCodec.PROTO_88;

    private ConsumerIrManager ir;
    private SharedPreferences prefs;

    private TextView lcdModeAuto, lcdModeCool, lcdModeDry, lcdModeFan;
    private TextView lcdTemp, lcdFan, lcdPower, lcdFlags, lcdFlow;
    private TextView status;
    private Button proto88, proto152;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        prefs = getSharedPreferences("state", MODE_PRIVATE);
        load();
        setContentView(buildUi());
        refresh();
        if (ir == null || !ir.hasIrEmitter()) {
            status.setText("This phone has no IR emitter / الموبايل مفيهوش IR");
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#3A3F44"));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        // Protocol selector (outside the remote body)
        LinearLayout protoRow = new LinearLayout(this);
        protoRow.setGravity(Gravity.CENTER);
        TextView pl = new TextView(this);
        pl.setText("Protocol  ");
        pl.setTextColor(Color.WHITE);
        protoRow.addView(pl);
        proto88 = smallToggle("88-bit");
        proto152 = smallToggle("152-bit");
        proto88.setOnClickListener(v -> { protocol = MhiCodec.PROTO_88; save(); refresh(); });
        proto152.setOnClickListener(v -> { protocol = MhiCodec.PROTO_152; save(); refresh(); });
        protoRow.addView(proto88);
        protoRow.addView(proto152);
        root.addView(protoRow);

        TextView hint = new TextView(this);
        hint.setText("لو الجهاز ما استجابش صح، غيّر البروتوكول وجرّب تاني");
        hint.setTextColor(Color.parseColor("#C8CDD2"));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(4), 0, dp(10));
        root.addView(hint);

        // Remote body
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(22));
        GradientDrawable bodyBg = new GradientDrawable();
        bodyBg.setColor(BODY);
        bodyBg.setCornerRadii(new float[]{dp(40), dp(40), dp(40), dp(40), dp(60), dp(60), dp(60), dp(60)});
        body.setBackground(bodyBg);
        body.setElevation(dp(10));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(body, bodyLp);

        TextView brand = new TextView(this);
        brand.setText("◆ MITSUBISHI");
        brand.setTextColor(LABEL);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setTextSize(11);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(4), 0, dp(8));
        body.addView(brand);

        // Grey panel holding LCD + keys
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(10), dp(10), dp(12));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(PANEL);
        panelBg.setCornerRadius(dp(18));
        panel.setBackground(panelBg);
        body.addView(panel);

        panel.addView(buildLcd());

        // Row 1: FAN SPEED | ON/OFF | MODE
        LinearLayout r1 = row();
        r1.addView(key("FAN SPEED", false, v -> { cycleFan(); send(); }), cell());
        r1.addView(powerKey(), cell());
        r1.addView(key("MODE", false, v -> { cycleMode(); send(); }), cell());
        panel.addView(r1);

        // Row 2: HI POWER | TEMP ▲ | AIR FLOW ▲▼
        LinearLayout r2 = row();
        r2.addView(key("HI POWER", false, v -> { toggleBoost(MhiCodec.BOOST_HIPOWER); send(); }), cell());
        r2.addView(key("TEMP ▲", false, v -> { tempStep(1); send(); }), cell());
        r2.addView(key("AIR FLOW ▲▼", true, v -> { st.swingV = (st.swingV + 1) % 6; send(); }), cell());
        panel.addView(r2);

        // Row 3: ECONO | TEMP ▼ | AIR FLOW ◀▶
        LinearLayout r3 = row();
        r3.addView(key("ECONO", false, v -> { toggleBoost(MhiCodec.BOOST_ECONO); send(); }), cell());
        r3.addView(key("TEMP ▼", false, v -> { tempStep(-1); send(); }), cell());
        r3.addView(key("AIR FLOW ◀▶", true, v -> {
            st.threeD = false; st.swingH = (st.swingH + 1) % 6; send();
        }), cell());
        panel.addView(r3);

        // Row 4: ON TIMER | OFF TIMER | 3D AUTO
        LinearLayout r4 = row();
        r4.addView(key("ON TIMER", false, v -> unsupported("ON TIMER")), cell());
        r4.addView(key("OFF TIMER", false, v -> unsupported("OFF TIMER")), cell());
        r4.addView(key("3D AUTO", true, v -> { st.threeD = !st.threeD; send(); }), cell());
        panel.addView(r4);

        // Row 5: SLEEP | CANCEL | (blank)
        LinearLayout r5 = row();
        r5.addView(key("SLEEP", false, v -> {
            if (protocol == MhiCodec.PROTO_88) { unsupported("SLEEP"); return; }
            st.sleep = !st.sleep; send();
        }), cell());
        r5.addView(key("CANCEL", false, v -> { st.sleep = false; send(); }), cell());
        View blank = new View(this);
        r5.addView(blank, cell());
        panel.addView(r5);

        // Bottom strip: CLEAN | ACL
        LinearLayout r6 = row();
        r6.addView(key("CLEAN", false, v -> { st.clean = !st.clean; send(); }), cell());
        View blank2 = new View(this);
        r6.addView(blank2, cell());
        r6.addView(key("ACL", false, v -> {
            MhiCodec.State d = new MhiCodec.State();
            copy(d, st); save(); refresh();
            Toast.makeText(this, "Remote reset (nothing sent)", Toast.LENGTH_SHORT).show();
        }), cell());
        panel.addView(r6);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#C8CDD2"));
        status.setTextSize(11);
        status.setTypeface(Typeface.MONOSPACE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, 0);
        status.setText("Point the phone at the AC and press ON/OFF");
        root.addView(status);

        return scroll;
    }

    private View buildLcd() {
        LinearLayout lcd = new LinearLayout(this);
        lcd.setOrientation(LinearLayout.VERTICAL);
        lcd.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(LCD);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(2), Color.parseColor("#8E968A"));
        lcd.setBackground(bg);

        LinearLayout top = new LinearLayout(this);
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.VERTICAL);
        lcdModeAuto = lcdText("AUTO", 11);
        lcdModeCool = lcdText("COOL ❄", 11);
        lcdModeDry = lcdText("DRY 💧", 11);
        lcdModeFan = lcdText("FAN", 11);
        modes.addView(lcdModeAuto);
        modes.addView(lcdModeCool);
        modes.addView(lcdModeDry);
        modes.addView(lcdModeFan);
        top.addView(modes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        lcdTemp = lcdText("24°C", 34);
        lcdTemp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        lcdTemp.setGravity(Gravity.CENTER);
        top.addView(lcdTemp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        lcdPower = lcdText("OFF", 12);
        lcdPower.setTypeface(Typeface.DEFAULT_BOLD);
        lcdFan = lcdText("FAN AUTO", 11);
        right.addView(lcdPower);
        right.addView(lcdFan);
        top.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        lcd.addView(top);

        lcdFlow = lcdText("", 10);
        lcd.addView(lcdFlow);
        lcdFlags = lcdText("", 10);
        lcdFlags.setTypeface(Typeface.DEFAULT_BOLD);
        lcd.addView(lcdFlags);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        lcd.setLayoutParams(lp);
        return lcd;
    }

    private TextView lcdText(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(LCD_ON);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private StateListDrawable keyBg(int color, float radius) {
        GradientDrawable n = new GradientDrawable();
        n.setColor(color);
        n.setCornerRadius(radius);
        n.setStroke(dp(1), Color.parseColor("#8A8C8E"));
        GradientDrawable p = new GradientDrawable();
        p.setColor(KEY_PRESSED);
        p.setCornerRadius(radius);
        p.setStroke(dp(1), Color.parseColor("#6A6C6E"));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }

    private Button key(String label, boolean teal, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setTextColor(LABEL);
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        b.setBackground(keyBg(teal ? KEY_TEAL : KEY, dp(10)));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onClick(v);
        });
        return b;
    }

    private Button powerKey() {
        Button b = key("ON/OFF", false, v -> { st.power = !st.power; send(); });
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(keyBg(Color.parseColor("#F7F7F2"), dp(22)));
        return b;
    }

    private Button smallToggle(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(90), dp(36));
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------------------------------------------------------- logic

    private void cycleMode() {
        // catalog: cooling-only unit -> AUTO, COOL, DRY, FAN
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

    private void toggleBoost(int which) {
        st.boost = (st.boost == which) ? MhiCodec.BOOST_NONE : which;
    }

    private void tempStep(int d) {
        st.temp = Math.max(MhiCodec.MIN_TEMP, Math.min(MhiCodec.MAX_TEMP, st.temp + d));
    }

    private void unsupported(String what) {
        Toast.makeText(this, what + ": مش موجود في البروتوكول المعروف", Toast.LENGTH_SHORT).show();
    }

    private void send() {
        save();
        refresh();
        int[] bytes = MhiCodec.encode(protocol, st);
        String hex = MhiCodec.hex(bytes);
        if (ir == null || !ir.hasIrEmitter()) {
            status.setText("No IR emitter\n" + hex);
            return;
        }
        try {
            ir.transmit(MhiCodec.FREQ, MhiCodec.toPattern(bytes));
            status.setText("Sent " + protocol + "-bit\n" + hex);
        } catch (Exception e) {
            status.setText("IR error: " + e.getMessage());
        }
    }

    private void refresh() {
        boolean on = st.power;
        TextView[] modes = {lcdModeAuto, lcdModeCool, lcdModeDry, lcdModeFan};
        int[] vals = {MhiCodec.MODE_AUTO, MhiCodec.MODE_COOL, MhiCodec.MODE_DRY, MhiCodec.MODE_FAN};
        for (int i = 0; i < 4; i++) modes[i].setTextColor(vals[i] == st.mode ? LCD_ON : LCD_OFF);

        // catalog: AUTO mode shows no set temperature
        lcdTemp.setText(st.mode == MhiCodec.MODE_AUTO ? "--" : st.temp + "°C");
        lcdPower.setText(on ? "RUN" : "OFF");

        String fan;
        if (st.boost == MhiCodec.BOOST_HIPOWER) fan = "HI POWER";
        else if (st.boost == MhiCodec.BOOST_ECONO) fan = "ECONO";
        else fan = new String[]{"FAN AUTO", "FAN HI", "FAN MED", "FAN LO"}[st.fan];
        lcdFan.setText(fan);

        lcdFlow.setText("▲▼ " + MhiCodec.SWING_V_NAMES[st.swingV]
                + "   ◀▶ " + (st.threeD ? "3D AUTO" : MhiCodec.SWING_H_NAMES[st.swingH]));

        StringBuilder f = new StringBuilder();
        if (st.threeD) f.append("3D AUTO  ");
        if (st.sleep) f.append("SLEEP  ");
        if (st.clean) f.append("CLEAN  ");
        lcdFlags.setText(f.toString().trim());

        styleToggle(proto88, protocol == MhiCodec.PROTO_88);
        styleToggle(proto152, protocol == MhiCodec.PROTO_152);
    }

    private void styleToggle(Button b, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(active ? Color.parseColor("#9FDCCF") : Color.parseColor("#55595E"));
        b.setBackground(g);
        b.setTextColor(active ? LABEL : Color.WHITE);
    }

    // ---------------------------------------------------------------- persistence

    private static void copy(MhiCodec.State from, MhiCodec.State to) {
        to.power = from.power; to.mode = from.mode; to.temp = from.temp; to.fan = from.fan;
        to.boost = from.boost; to.swingV = from.swingV; to.swingH = from.swingH;
        to.threeD = from.threeD; to.clean = from.clean; to.sleep = from.sleep;
    }

    private void save() {
        prefs.edit()
                .putInt("protocol", protocol)
                .putBoolean("power", st.power).putInt("mode", st.mode).putInt("temp", st.temp)
                .putInt("fan", st.fan).putInt("boost", st.boost)
                .putInt("swingV", st.swingV).putInt("swingH", st.swingH)
                .putBoolean("threeD", st.threeD).putBoolean("clean", st.clean)
                .putBoolean("sleep", st.sleep)
                .apply();
    }

    private void load() {
        protocol = prefs.getInt("protocol", MhiCodec.PROTO_88);
        st.power = prefs.getBoolean("power", false);
        st.mode = prefs.getInt("mode", MhiCodec.MODE_COOL);
        st.temp = prefs.getInt("temp", 24);
        st.fan = prefs.getInt("fan", MhiCodec.FAN_AUTO);
        st.boost = prefs.getInt("boost", MhiCodec.BOOST_NONE);
        st.swingV = prefs.getInt("swingV", 0);
        st.swingH = prefs.getInt("swingH", 0);
        st.threeD = prefs.getBoolean("threeD", false);
        st.clean = prefs.getBoolean("clean", false);
        st.sleep = prefs.getBoolean("sleep", false);
    }
}
