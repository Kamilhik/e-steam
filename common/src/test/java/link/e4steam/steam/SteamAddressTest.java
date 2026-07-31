package link.e4steam.steam;

import link.e4steam.HexCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SteamAddressTest {
    @Test
    void roundTripsAddressAndDefensivelyCopiesToken() {
        byte[] token = HexCodec.decode("00112233445566778899aabbccddeeff");
        SteamAddress address = new SteamAddress(76561198000000000L, token);
        token[0] = 0x7f;

        String encoded = "s-kxuogt5na4g-54v5h7phl5wc4e458l6ckxr.steam";
        assertEquals(encoded, address.inviteString());
        assertFalse(address.toString().contains("00112233445566778899aabbccddeeff"));
        assertEquals(address, SteamAddress.tryParse(encoded.toUpperCase()).orElseThrow());
        assertArrayEquals(HexCodec.decode("00112233445566778899aabbccddeeff"), address.token());
    }

    @Test
    void parsesLegacyAddressesWithoutGeneratingThem() {
        String legacy = "e4steam-76561198000000000-00112233445566778899aabbccddeeff.steam";
        SteamAddress parsed = SteamAddress.tryParse(legacy).orElseThrow();

        assertEquals(76561198000000000L, parsed.steamId());
        assertArrayEquals(HexCodec.decode("00112233445566778899aabbccddeeff"), parsed.token());
        assertTrue(parsed.inviteString().startsWith("s-"));
    }

    @Test
    void preservesUnsignedSteamIdAndAllTokenBits() {
        byte[] token = HexCodec.decode("ffffffffffffffffffffffffffffffff");
        SteamAddress address = new SteamAddress(-1L, token);

        SteamAddress parsed = SteamAddress.tryParse(address.inviteString() + ".").orElseThrow();
        assertEquals(address, parsed);
        assertTrue(address.inviteString().length() < 50);
    }

    @Test
    void preservesLeadingZeroBytesInToken() {
        byte[] token = new byte[SteamAddress.TOKEN_LENGTH];
        token[token.length - 1] = 1;
        SteamAddress address = new SteamAddress(1, token);

        assertEquals("s-1-1.steam", address.inviteString());
        assertEquals(address, SteamAddress.tryParse(address.inviteString()).orElseThrow());
    }

    @Test
    void rejectsOrdinaryAndMalformedHosts() {
        byte[] token = new byte[SteamAddress.TOKEN_LENGTH];
        assertThrows(IllegalArgumentException.class, () -> new SteamAddress(0, token));
        assertFalse(SteamAddress.tryParse("example.org").isPresent());
        assertFalse(SteamAddress.tryParse("s-0-1.steam").isPresent());
        assertFalse(SteamAddress.tryParse("s-1-zzzzzzzzzzzzzzzzzzzzzzzzzz.steam").isPresent());
        assertFalse(SteamAddress.tryParse("s-zzzzzzzzzzzzzz-1.steam").isPresent());
        assertFalse(SteamAddress.tryParse("e4steam-0-00112233445566778899aabbccddeeff.steam").isPresent());
        assertFalse(SteamAddress.tryParse("e4steam-1-too-short.steam").isPresent());
        assertFalse(SteamAddress.tryParse("e4steam-18446744073709551616-00112233445566778899aabbccddeeff.steam").isPresent());
        assertTrue(SteamAddress.tryParse("e4steam-1-00112233445566778899aabbccddeeff.steam.").isPresent());
    }
}
