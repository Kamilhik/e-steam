package link.e4steam.steam;

import link.e4steam.HexCodec;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A self-contained e4steam endpoint. The token prevents unrelated App ID
 * 480 traffic from being forwarded into the local Minecraft server.
 */
public final class SteamAddress {
    private static final Pattern SHORT_PATTERN = Pattern.compile(
            "^s-([0-9a-z]{1,13})-([0-9a-z]{1,25})\\.steam\\.?$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LEGACY_PATTERN = Pattern.compile(
            "^e4steam-([0-9]{1,20})-([0-9a-f]{32})\\.steam\\.?$",
            Pattern.CASE_INSENSITIVE
    );
    public static final int TOKEN_LENGTH = 16;

    private final long steamId;
    private final byte[] token;

    public SteamAddress(long steamId, byte[] token) {
        if (steamId == 0) {
            throw new IllegalArgumentException("Steam ID must be non-zero");
        }
        if (token.length != TOKEN_LENGTH) {
            throw new IllegalArgumentException("Steam invite tokens must be 128 bits");
        }
        this.steamId = steamId;
        this.token = token.clone();
    }

    public long steamId() {
        return steamId;
    }

    public byte[] token() {
        return token.clone();
    }

    public static Optional<SteamAddress> tryParse(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        Matcher shortMatcher = SHORT_PATTERN.matcher(normalized);
        if (shortMatcher.matches()) {
            return parseShort(shortMatcher);
        }

        Matcher legacyMatcher = LEGACY_PATTERN.matcher(normalized);
        if (legacyMatcher.matches()) {
            return parseLegacy(legacyMatcher);
        }

        return Optional.empty();
    }

    private static Optional<SteamAddress> parseShort(Matcher matcher) {
        try {
            long steamId = Long.parseUnsignedLong(matcher.group(1), Character.MAX_RADIX);
            if (steamId == 0) {
                return Optional.empty();
            }
            byte[] token = decodeBase36Token(matcher.group(2));
            return Optional.of(new SteamAddress(steamId, token));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<SteamAddress> parseLegacy(Matcher matcher) {
        try {
            long steamId = Long.parseUnsignedLong(matcher.group(1));
            if (steamId == 0) {
                return Optional.empty();
            }
            return Optional.of(new SteamAddress(steamId, HexCodec.decode(matcher.group(2))));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static byte[] decodeBase36Token(String encoded) {
        BigInteger value = new BigInteger(encoded, Character.MAX_RADIX);
        if (value.signum() < 0 || value.bitLength() > TOKEN_LENGTH * Byte.SIZE) {
            throw new IllegalArgumentException("Steam invite token exceeds 128 bits");
        }

        byte[] compact = value.toByteArray();
        int sourceOffset = compact.length > TOKEN_LENGTH ? compact.length - TOKEN_LENGTH : 0;
        int copyLength = compact.length - sourceOffset;
        byte[] decoded = new byte[TOKEN_LENGTH];
        System.arraycopy(compact, sourceOffset, decoded, decoded.length - copyLength, copyLength);
        return decoded;
    }

    public String inviteString() {
        return "s-"
                + Long.toUnsignedString(steamId, Character.MAX_RADIX)
                + "-"
                + new BigInteger(1, token).toString(Character.MAX_RADIX)
                + ".steam";
    }

    @Override
    public String toString() {
        return "SteamAddress{steamId=" + Long.toUnsignedString(steamId) + ", token=<redacted>}";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SteamAddress that)) {
            return false;
        }
        return steamId == that.steamId && Arrays.equals(token, that.token);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(steamId) + Arrays.hashCode(token);
    }
}
