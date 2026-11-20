package com.kareem.mhiremote;

/**
 * Mitsubishi Heavy Industries IR encoders, four variants:
 *  ZJ  - 88-bit, remote RKX502A001C   (IRremoteESP8266 MITSUBISHI_HEAVY_88, verified byte-for-byte)
 *  ZEA - 88-bit frame, different fan/air-flow codes (ToniA arduino-heatpumpir, ZEA class)
 *  ZMP - 88-bit frame, remote RKX502A001P, no left/right flow, no 3D (ToniA, ZMP class)
 *  ZM  - 152-bit, remote RLA502A700B  (IRremoteESP8266 MITSUBISHI_HEAVY_152, verified byte-for-byte)
 *
 * All returned arrays use IRremoteESP8266 byte convention (bit 1 = short space, LSB first).
 * ToniA's library sends the bitwise inverse with swapped space lengths, which is the same
 * waveform, so its bytes are converted with ~b.
 */
public final class MhiCodec {

    public static final int PROTO_ZJ = 0;
    public static final int PROTO_ZEA = 1;
    public static final int PROTO_ZMP = 2;
    public static final int PROTO_ZM = 3;
    public static final String[] PROTO_NAMES = {"ZJ", "ZEA", "ZMP", "ZM"};

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

    private MhiCodec() {}

    public static int[] encode(int protocol, State s) {
        switch (protocol) {
            case PROTO_ZEA: return encodeZea(s);
            case PROTO_ZMP: return encodeZmp(s);
            case PROTO_ZM: return encodeZm(s);
            default: return encodeZj(s);
        }
    }

    private static int clampTemp(int t) {
        return Math.max(MIN_TEMP, Math.min(MAX_TEMP, t));
    }

    // ------------------------------------------------------------------ ZJ (IRremoteESP8266 88-bit)

    private static final int[] SIG_88 = {0xAD, 0x51, 0x3C, 0xD9, 0x26};
    private static final int[] SV_88 = {0b100, 0b110, 0b001, 0b011, 0b101, 0b111};
    private static final int[] SH_88 = {0b1000, 0b0001, 0b0101, 0b1001, 0b1101, 0b0010};

    public static int[] encodeZj(State s) {
        int[] r = new int[11];
        System.arraycopy(SIG_88, 0, r, 0, 5);
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = 6;
        else if (s.boost == BOOST_ECONO) fan = 7;
        else fan = (s.fan == FAN_HI) ? 4 : (s.fan == FAN_MED) ? 3 : (s.fan == FAN_LO) ? 2 : 0;
        int sv = SV_88[s.swingV];
        int sh = s.threeD ? 0b1110 : SH_88[s.swingH];
        r[5] = ((sv & 1) << 1) | ((sh & 3) << 2) | (s.clean ? 1 << 5 : 0) | (((sh >> 2) & 3) << 6);
        r[7] = (((sv >> 1) & 3) << 3) | ((fan & 7) << 5);
        r[9] = (s.mode & 7) | (s.power ? 8 : 0) | (((clampTemp(s.temp) - 17) & 0xF) << 4);
        invertPairs(r);
        return r;
    }

    // ------------------------------------------------------------------ ZM (IRremoteESP8266 152-bit)

    private static final int[] SIG_152 = {0xAD, 0x51, 0x3C, 0xE5, 0x1A};

    public static int[] encodeZm(State s) {
        int[] r = new int[19];
        System.arraycopy(SIG_152, 0, r, 0, 5);
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = 8;
        else if (s.boost == BOOST_ECONO) fan = 6;
        else fan = (s.fan == FAN_HI) ? 4 : (s.fan == FAN_MED) ? 3 : (s.fan == FAN_LO) ? 2 : 0;
        r[5] = (s.mode & 7) | (s.power ? 8 : 0) | (s.clean ? (1 << 5) | (1 << 6) : 0);
        r[7] = (clampTemp(s.temp) - 17) & 0xF;
        r[9] = fan & 0xF;
        r[11] = (s.threeD ? (1 << 1) | (1 << 4) : 0) | ((s.swingV & 7) << 5);
        r[13] = s.swingH & 0xF;
        r[17] = 0x80;
        invertPairs(r);
        return r;
    }

    // ------------------------------------------------------------------ ToniA family (ZEA, ZMP)

    // ToniA codes (inverted convention)
    private static final int T_MODE_AUTO = 0x07, T_MODE_COOL = 0x06, T_MODE_DRY = 0x05, T_MODE_FAN = 0x04;
    private static final int T_POWER_ON = 0x00, T_POWER_OFF = 0x08;
    private static final int[] T_HEADER = {0x52, 0xAE, 0xC3, 0x26, 0xD9};

    // ZEA
    private static final int ZEA_FAN_AUTO = 0xE0, ZEA_FAN1 = 0xC2, ZEA_FAN2 = 0xA4, ZEA_FAN3 = 0x86;
    private static final int ZEA_HIPOWER = 0x2C, ZEA_ECONO = 0x0E;
    private static final int ZEA_CLEAN_ON = 0x00, ZEA_CLEAN_OFF = 0x08;
    // swing index order: SWING, UP, MUP, MIDDLE, MDOWN, DOWN
    private static final int[] ZEA_VS = {0x1A, 0x0E, 0x31, 0x25, 0x19, 0x0D};
    // swing index order: SWING, LEFT, MLEFT, MIDDLE, MRIGHT, RIGHT
    private static final int[] ZEA_HS = {0xD2, 0xC3, 0xB4, 0xA5, 0x96, 0x87};
    private static final int ZEA_HS_3DAUTO = 0xE1;

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

    public static int[] encodeZea(State s) {
        int power = s.power ? T_POWER_ON : T_POWER_OFF;
        int clean = ZEA_CLEAN_OFF;
        if (s.clean && !s.power && s.mode != MODE_FAN) {
            power = T_POWER_ON;
            clean = ZEA_CLEAN_ON;
        }
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = ZEA_HIPOWER;
        else if (s.boost == BOOST_ECONO) fan = ZEA_ECONO;
        else fan = (s.fan == FAN_LO) ? ZEA_FAN1 : (s.fan == FAN_MED) ? ZEA_FAN2
                : (s.fan == FAN_HI) ? ZEA_FAN3 : ZEA_FAN_AUTO;
        int sv = ZEA_VS[s.swingV];
        int sh = ZEA_HS[s.swingH];
        if (s.threeD && (s.mode == MODE_AUTO || s.mode == MODE_COOL)) sh = ZEA_HS_3DAUTO;
        int b5 = 0xDF | (sh & 0xF0) | ((sv >> 1) & 0x01) | clean;
        int b7 = 0x07 | (fan & 0xE0) | ((sv >> 1) & 0x18);
        int b9 = tMode(s.mode) | power | tTemp(s.temp);
        return tFrame(b5, b7, b9);
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
