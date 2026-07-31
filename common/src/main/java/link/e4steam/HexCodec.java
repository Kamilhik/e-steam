package link.e4steam;

/**
 * Java 16-compatible lowercase hexadecimal codec used by the legacy build.
 */
public final class HexCodec {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private HexCodec() {
    }

    public static String encode(byte[] bytes) {
        return encode(bytes, 0, bytes.length);
    }

    public static String encode(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException("Invalid hexadecimal byte range");
        }
        char[] result = new char[length * 2];
        for (int i = 0; i < length; i++) {
            int value = bytes[offset + i] & 0xff;
            result[i * 2] = DIGITS[value >>> 4];
            result[i * 2 + 1] = DIGITS[value & 0x0f];
        }
        return new String(result);
    }

    public static byte[] decode(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("Hexadecimal text must contain an even number of characters");
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hexadecimal character");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
