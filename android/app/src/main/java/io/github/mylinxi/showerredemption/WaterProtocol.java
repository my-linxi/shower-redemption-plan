package io.github.mylinxi.showerredemption;

import java.util.Locale;

final class WaterProtocol {
    private static final int[][] KEY_TABLE = {
        {0x0000, 0xC1C0, 0x81C1, 0x4001, 0x01C3, 0xC003, 0x8002, 0x41C2, 0x01C6, 0xC006},
        {0x0000, 0x0190, 0x0160, 0x00F0, 0x02C0, 0x0350, 0x03A0, 0x0230, 0x07C0, 0x0650},
        {0x0000, 0x51C0, 0xA1C0, 0xF000, 0x41C1, 0x1001, 0xE001, 0xB1C1, 0x81C2, 0xD002},
        {0x0000, 0x01FC, 0x01B8, 0x0044, 0x0130, 0x00CC, 0x0088, 0x0174, 0x0260, 0x039C},
        {0x0000, 0x3DC0, 0x79C0, 0x4400, 0xF1C0, 0xCC00, 0x8800, 0xB5C0, 0xE1C1, 0xDC01}
    };

    static final int HANDSHAKE = 0xB0;
    static final int HANDSHAKE_ALT = 0xB1;
    static final int OPEN = 0xB2;
    static final int CLOSE = 0xB3;
    static final int CONFIRM = 0xB4;

    private WaterProtocol() {}

    static String waterNumber(String name) {
        String digits = name.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        String padded = "000000" + digits;
        return padded.substring(padded.length() - 6);
    }

    static String nameFromQr(String contents) {
        return contents.matches("[0-9]{10}") ? "Water" + contents.substring(4) : null;
    }

    static byte[] command(int command, String waterNumber) {
        return switch (command) {
            case HANDSHAKE -> new byte[]{(byte) 0xFE, (byte) 0xFE, 9, (byte) HANDSHAKE, 1, 1, 0, 0};
            case OPEN -> {
                int key = 0x2BDC;
                for (int position = 0; position < KEY_TABLE.length; position++) {
                    key ^= KEY_TABLE[position][waterNumber.charAt(5 - position) - '0'];
                }
                yield new byte[]{(byte) 0xFE, (byte) 0xFE, 9, (byte) OPEN, 1,
                        (byte) (key >> 8), (byte) key, 0x1B};
            }
            case CLOSE, CONFIRM -> new byte[]{(byte) 0xFE, (byte) 0xFE, 9, (byte) command, 0, 0};
            default -> throw new IllegalArgumentException("Unknown water command: " + command);
        };
    }

    static Reply parse(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (unsigned(bytes[i]) != 0xFD || unsigned(bytes[i + 1]) != 0xFD) continue;
            int command = unsigned(bytes[i + 3]);
            if (command == CLOSE && i + 19 < bytes.length) {
                int quantity = unsigned(bytes[i + 17]) | (unsigned(bytes[i + 18]) << 8)
                        | (unsigned(bytes[i + 19]) << 16);
                return new Reply(command, quantity);
            }
            return new Reply(command, null);
        }
        return null;
    }

    static String reading(int quantity) {
        // 每计数 0.1 L，按 25 元/吨换算。每 4 计数等于 1 分。
        int cents = (quantity + 2) / 4;
        return String.format(Locale.ROOT, "用水量 %.1f L\n参考金额 ¥%.2f", quantity / 10.0, cents / 100.0);
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    record Reply(int command, Integer quantity) {}
}
