package com.kareem.mhiremote;

/**
 * Mitsubishi Heavy Industries 88-bit IR encoders:
 *  CR  - ZJ frame with left/right = 0 by default, as sent by a real SRK13CRV remote
 *        (SmartIR code set 1680, decoded byte-for-byte)
 *  ZJ  - remote RKX502A001C (IRremoteESP8266 MITSUBISHI_HEAVY_88, verified byte-for-byte)
 *  ZMP - remote RKX502A001P, no left/right flow, no 3D (ToniA arduino-heatpumpir, verified)
 * Bytes use IRremoteESP8266 convention (bit 1 = short space, LSB first).
 */
public final class MhiCodec {

    public static final int PROTO_CR = 0;
    public static final int PROTO_ZJ = 1;
    public static final int PROTO_ZMP = 2;
    public static final String[] PROTO_NAMES = {"CR", "ZJ", "ZMP"};

    public static final int MODE_AUTO = 0;
    public static final int MODE_COOL = 1;
    public static final int MODE_DRY = 2;
    public static final int MODE_FAN = 3;

    public static final int FAN_AUTO = 0;
    public static final int FAN_HI = 1;
    public static final int FAN_MED = 2;
    public static final int FAN_LO = 3;

    public static final int BOOST_NONE = 0;
    public static final int BOOST_HIPOWER = 1;
    public static final int BOOST_ECONO = 2;

    public static final String[] SWING_V_NAMES = {"Swing", "Top", "High", "Middle", "Low", "Bottom"};
    public static final String[] SWING_H_NAMES = {"Swing", "Far left", "Left", "Center", "Right", "Far right"};
    /** CR: real SRK13CRV remote capture (SmartIR 1680) sends left/right = 0, so that is the default. */
    public static final String[] SWING_H_NAMES_CR = {"Fixed", "Far left", "Left", "Center", "Right", "Far right", "Swing"};

    public static final int MIN_TEMP = 18;
    public static final int MAX_TEMP = 30;

    public static final int FREQ = 38000;
    private static final int HDR_MARK = 3140;
    private static final int HDR_SPACE = 1630;
    private static final int BIT_MARK = 370;
    private static final int ONE_SPACE = 420;
    private static final int ZERO_SPACE = 1220;

    public static final class State {
        public boolean power = false;
        public int mode = MODE_COOL;
        public int temp = 24;
        public int fan = FAN_AUTO;
        public int boost = BOOST_NONE;
        public int swingV = 0;
        public int swingH = 0;
        public boolean threeD = false;
        public boolean clean = false;
    }

    /** Whether a feature exists in a protocol (used to disable keys). */
    public static boolean hasSwingH(int p) { return p != PROTO_ZMP; }
    public static boolean has3D(int p) { return p != PROTO_ZMP; }
    public static String[] swingHNames(int p) { return p == PROTO_CR ? SWING_H_NAMES_CR : SWING_H_NAMES; }

    private MhiCodec() {}

    public static int[] encode(int protocol, State s) {
        switch (protocol) {
            case PROTO_ZMP: return encodeZmp(s);
            case PROTO_ZJ: return encode88(s, SH_88);
            default: return encode88(s, SH_CR);
        }
    }

    private static int clampTemp(int t) {
        return Math.max(MIN_TEMP, Math.min(MAX_TEMP, t));
    }

    // ------------------------------------------------------------------ ZJ (IRremoteESP8266 88-bit)

    private static final int[] SIG_88 = {0xAD, 0x51, 0x3C, 0xD9, 0x26};
    private static final int[] SV_88 = {0b100, 0b110, 0b001, 0b011, 0b101, 0b111};
    private static final int[] SH_88 = {0b1000, 0b0001, 0b0101, 0b1001, 0b1101, 0b0010};
    private static final int[] SH_CR = {0b0000, 0b0001, 0b0101, 0b1001, 0b1101, 0b0010, 0b1000};

    public static int[] encodeZj(State s) {
        return encode88(s, SH_88);
    }

    private static int[] encode88(State s, int[] shTable) {
        int[] r = new int[11];
        System.arraycopy(SIG_88, 0, r, 0, 5);
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = 6;
        else if (s.boost == BOOST_ECONO) fan = 7;
        else fan = (s.fan == FAN_HI) ? 4 : (s.fan == FAN_MED) ? 3 : (s.fan == FAN_LO) ? 2 : 0;
        // real CR remote always sends fan = auto in DRY
        if (shTable == SH_CR && s.mode == MODE_DRY) fan = 0;
        int sv = SV_88[s.swingV];
        int sh = s.threeD ? 0b1110 : shTable[Math.min(s.swingH, shTable.length - 1)];
        r[5] = ((sv & 1) << 1) | ((sh & 3) << 2) | (s.clean ? 1 << 5 : 0) | (((sh >> 2) & 3) << 6);
        r[7] = (((sv >> 1) & 3) << 3) | ((fan & 7) << 5);
        r[9] = (s.mode & 7) | (s.power ? 8 : 0) | (((clampTemp(s.temp) - 17) & 0xF) << 4);
        invertPairs(r);
        return r;
    }

    // ------------------------------------------------------------------ ToniA family (ZMP)

    // ToniA codes (inverted convention)
    private static final int T_MODE_AUTO = 0x07, T_MODE_COOL = 0x06, T_MODE_DRY = 0x05, T_MODE_FAN = 0x04;
    private static final int T_POWER_ON = 0x00, T_POWER_OFF = 0x08;
    private static final int[] T_HEADER = {0x52, 0xAE, 0xC3, 0x26, 0xD9};

    // ZMP
    private static final int ZMP_FAN_AUTO = 0xE0, ZMP_FAN1 = 0xA0, ZMP_FAN2 = 0x80, ZMP_FAN3 = 0x60;
    private static final int ZMP_HIPOWER = 0x20, ZMP_ECONO = 0x00;
    private static final int ZMP_CLEAN_ON = 0xDF, ZMP_CLEAN_OFF = 0x20;
    private static final int ZMP_MODE_FAN = 0xD4, ZMP_MODE_MAINT = 0x06;
    private static final int[] ZMP_VS = {0x0A, 0x02, 0x18, 0x10, 0x08, 0x00};
    private static final int ZMP_HS_STOP = 0xCC;

    // ZJ in ToniA form (used only to self-check the ToniA porting against IRremoteESP8266)
    private static final int[] ZJT_VS = {0x0A, 0x02, 0x18, 0x10, 0x08, 0x00};
    private static final int[] ZJT_HS = {0x4C, 0xC8, 0x88, 0x48, 0x08, 0xC4};

    private static int tMode(int mode) {
        switch (mode) {
            case MODE_AUTO: return T_MODE_AUTO;
            case MODE_DRY: return T_MODE_DRY;
            case MODE_FAN: return T_MODE_FAN;
            default: return T_MODE_COOL;
        }
    }

    private static int tTemp(int t) {
        return (~((clampTemp(t) - 17) << 4)) & 0xF0;
    }

    private static int[] tFrame(int b5, int b7, int b9) {
        int[] t = new int[11];
        System.arraycopy(T_HEADER, 0, t, 0, 5);
        t[5] = b5 & 0xFF;
        t[6] = ~t[5] & 0xFF;
        t[7] = b7 & 0xFF;
        t[8] = ~t[7] & 0xFF;
        t[9] = b9 & 0xFF;
        t[10] = ~t[9] & 0xFF;
        int[] r = new int[11];
        for (int i = 0; i < 11; i++) r[i] = ~t[i] & 0xFF;
        return r;
    }

    public static int[] encodeZmp(State s) {
        int power = s.power ? T_POWER_ON : T_POWER_OFF;
        int clean = ZMP_CLEAN_OFF;
        int mode = tMode(s.mode);
        int temp = tTemp(s.temp);
        // ToniA zeroes the temperature for FAN, then overwrites it with the set temperature
        if (s.mode == MODE_FAN) mode = ZMP_MODE_FAN;
        if (s.clean && !s.power) {
            power = T_POWER_ON;
            clean = ZMP_CLEAN_ON;
            mode = ZMP_MODE_MAINT;
        }
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = ZMP_HIPOWER;
        else if (s.boost == BOOST_ECONO) fan = ZMP_ECONO;
        else fan = (s.fan == FAN_LO) ? ZMP_FAN1 : (s.fan == FAN_MED) ? ZMP_FAN2
                : (s.fan == FAN_HI) ? ZMP_FAN3 : ZMP_FAN_AUTO;
        int sv = ZMP_VS[s.swingV];
        int b5 = 0x11 | ZMP_HS_STOP | (sv & 0x02) | clean;
        int b7 = 0x07 | fan | (sv & 0x18);
        int b9 = mode | power | temp;
        return tFrame(b5, b7, b9);
    }

    /** ZJ built the ToniA way; must equal encodeZj (clean off). Test-only. */
    public static int[] encodeZjViaToniA(State s) {
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = 0x40;
        else if (s.boost == BOOST_ECONO) fan = 0x00;
        else fan = (s.fan == FAN_LO) ? 0xA0 : (s.fan == FAN_MED) ? 0x80 : (s.fan == FAN_HI) ? 0x60 : 0xE0;
        int sv = ZJT_VS[s.swingV];
        int sh = s.threeD ? 0x04 : ZJT_HS[s.swingH];
        int b5 = 0x11 | sh | (sv & 0x02) | 0x20;
        int b7 = 0x07 | fan | (sv & 0x18);
        int b9 = tMode(s.mode) | (s.power ? T_POWER_ON : T_POWER_OFF) | tTemp(s.temp);
        return tFrame(b5, b7, b9);
    }

    // ------------------------------------------------------------------ shared

    private static void invertPairs(int[] r) {
        for (int i = 4; i < r.length; i += 2) r[i] = (~r[i - 1]) & 0xFF;
    }

    /** Mark/space durations in microseconds, LSB first, for ConsumerIrManager. */
    public static int[] toPattern(int[] bytes) {
        int[] p = new int[2 + bytes.length * 16 + 1];
        int k = 0;
        p[k++] = HDR_MARK;
        p[k++] = HDR_SPACE;
        for (int b : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p[k++] = BIT_MARK;
                p[k++] = ((b >> bit) & 1) == 1 ? ONE_SPACE : ZERO_SPACE;
            }
        }
        p[k] = BIT_MARK;
        return p;
    }

    public static String hex(int[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
