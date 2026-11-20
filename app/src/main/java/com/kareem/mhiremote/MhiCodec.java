package com.kareem.mhiremote;

/**
 * Mitsubishi Heavy Industries IR encoder.
 * Bit layouts, constants and timings ported from IRremoteESP8266
 * (src/ir_MitsubishiHeavy.h/.cpp): 88-bit (RKX502A001C / SRK-ZJ) and
 * 152-bit (RLA502A700B / SRK-ZM).
 */
public final class MhiCodec {

    public static final int PROTO_88 = 88;
    public static final int PROTO_152 = 152;

    // Modes (wire values are identical in both protocols)
    public static final int MODE_AUTO = 0;
    public static final int MODE_COOL = 1;
    public static final int MODE_DRY = 2;
    public static final int MODE_FAN = 3;

    // Fan (UI values)
    public static final int FAN_AUTO = 0;
    public static final int FAN_HI = 1;
    public static final int FAN_MED = 2;
    public static final int FAN_LO = 3;

    // HI POWER / ECONO (both live inside the fan field on the wire)
    public static final int BOOST_NONE = 0;
    public static final int BOOST_HIPOWER = 1;
    public static final int BOOST_ECONO = 2;

    // Air flow (UI index): 0 = swing, 1..5 = fixed positions
    public static final String[] SWING_V_NAMES = {"SWING", "TOP", "HIGH", "MIDDLE", "LOW", "BOTTOM"};
    public static final String[] SWING_H_NAMES = {"SWING", "LEFT MAX", "LEFT", "CENTER", "RIGHT", "RIGHT MAX"};

    public static final int MIN_TEMP = 18;
    public static final int MAX_TEMP = 30;

    // Timings (microseconds), carrier 38 kHz
    public static final int FREQ = 38000;
    private static final int HDR_MARK = 3140;
    private static final int HDR_SPACE = 1630;
    private static final int BIT_MARK = 370;
    private static final int ONE_SPACE = 420;
    private static final int ZERO_SPACE = 1220;

    private static final int[] SIG_88 = {0xAD, 0x51, 0x3C, 0xD9, 0x26};
    private static final int[] SIG_152 = {0xAD, 0x51, 0x3C, 0xE5, 0x1A};

    private static final int[] SV_88 = {0b100, 0b110, 0b001, 0b011, 0b101, 0b111};
    private static final int[] SH_88 = {0b1000, 0b0001, 0b0101, 0b1001, 0b1101, 0b0010};
    private static final int SH_88_3D = 0b1110;
    private static final int[] SV_152 = {0, 1, 2, 3, 4, 5};
    private static final int[] SH_152 = {0, 1, 2, 3, 4, 5};

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
        public boolean sleep = false;
    }

    private MhiCodec() {}

    private static int clampTemp(int t) {
        return Math.max(17, Math.min(31, t));
    }

    public static int[] encode(int protocol, State s) {
        return protocol == PROTO_152 ? encode152(s) : encode88(s);
    }

    public static int[] encode88(State s) {
        int[] r = new int[11];
        System.arraycopy(SIG_88, 0, r, 0, 5);
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = 6;
        else if (s.boost == BOOST_ECONO) fan = 7;
        else fan = (s.fan == FAN_HI) ? 4 : (s.fan == FAN_MED) ? 3 : (s.fan == FAN_LO) ? 2 : 0;
        int sv = SV_88[s.swingV];
        int sh = s.threeD ? SH_88_3D : SH_88[s.swingH];
        r[5] = ((sv & 1) << 1) | ((sh & 3) << 2) | (s.clean ? 1 << 5 : 0) | (((sh >> 2) & 3) << 6);
        r[7] = (((sv >> 1) & 3) << 3) | ((fan & 7) << 5);
        r[9] = (s.mode & 7) | (s.power ? 8 : 0) | (((clampTemp(s.temp) - 17) & 0xF) << 4);
        invertPairs(r);
        return r;
    }

    public static int[] encode152(State s) {
        int[] r = new int[19];
        System.arraycopy(SIG_152, 0, r, 0, 5);
        int fan;
        if (s.boost == BOOST_HIPOWER) fan = 8;
        else if (s.boost == BOOST_ECONO) fan = 6;
        else fan = (s.fan == FAN_HI) ? 4 : (s.fan == FAN_MED) ? 3 : (s.fan == FAN_LO) ? 2 : 0;
        r[5] = (s.mode & 7) | (s.power ? 8 : 0) | (s.clean ? (1 << 5) | (1 << 6) : 0);
        r[7] = (clampTemp(s.temp) - 17) & 0xF;
        r[9] = fan & 0xF;
        r[11] = (s.threeD ? (1 << 1) | (1 << 4) : 0) | ((SV_152[s.swingV] & 7) << 5);
        r[13] = SH_152[s.swingH] & 0xF;
        r[15] = s.sleep ? 1 << 6 : 0;
        r[17] = 0x80;
        invertPairs(r);
        return r;
    }

    /** Every 2nd byte of each pair (from byte 3) is the bitwise inverse of the first. */
    private static void invertPairs(int[] r) {
        for (int i = 4; i < r.length; i += 2) r[i] = (~r[i - 1]) & 0xFF;
    }

    /** Mark/space durations in microseconds, LSB first, ready for ConsumerIrManager. */
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
