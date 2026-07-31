package link.e4steam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexCodecTest {
    @Test
    void roundTripsLowercaseHex() {
        byte[] bytes = {(byte) 0x00, (byte) 0x7f, (byte) 0x80, (byte) 0xff};
        assertEquals("007f80ff", HexCodec.encode(bytes));
        assertArrayEquals(bytes, HexCodec.decode("007F80fF"));
    }

    @Test
    void rejectsMalformedTextAndRanges() {
        assertThrows(IllegalArgumentException.class, () -> HexCodec.decode("0"));
        assertThrows(IllegalArgumentException.class, () -> HexCodec.decode("zz"));
        assertThrows(IndexOutOfBoundsException.class, () -> HexCodec.encode(new byte[2], 1, 2));
    }
}
