package link.e4steam.steam

import link.e4steam.HexCodec
import java.math.BigInteger
import java.util.Locale
import java.util.Optional

/**
 * A self-contained e4steam endpoint. The token prevents unrelated App ID
 * 480 traffic from being forwarded into the local Minecraft server.
 */
class SteamAddress internal constructor(steamId: Long, token: ByteArray) {
    private val steamIdValue: Long = steamId
    private val tokenValue: ByteArray = token.clone()

    init {
        require(steamIdValue != 0L) { "Steam ID must be non-zero" }
        require(tokenValue.size == TOKEN_LENGTH) { "Steam invite tokens must be 128 bits" }
    }

    fun steamId(): Long = steamIdValue

    fun token(): ByteArray = tokenValue.clone()

    fun inviteString(): String = "s-"
            + java.lang.Long.toUnsignedString(steamIdValue, Character.MAX_RADIX)
            + "-"
            + BigInteger(1, tokenValue).toString(Character.MAX_RADIX)
            + ".steam"

    override fun toString(): String =
            "SteamAddress{steamId=" + java.lang.Long.toUnsignedString(steamIdValue) + ", token=<redacted>}"

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SteamAddress) {
            return false
        }
        return steamIdValue == other.steamIdValue && tokenValue.contentEquals(other.tokenValue)
    }

    override fun hashCode(): Int = 31 * steamIdValue.hashCode() + tokenValue.contentHashCode()

    companion object {
        const val TOKEN_LENGTH = 16

        private val SHORT_PATTERN = Regex(
                "^s-([0-9a-z]{1,13})-([0-9a-z]{1,25})\\.steam\\.?\$",
                RegexOption.IGNORE_CASE
        )
        private val LEGACY_PATTERN = Regex(
                "^e4steam-([0-9]{1,20})-([0-9a-f]{32})\\.steam\\.?\$",
                RegexOption.IGNORE_CASE
        )

        @JvmStatic
        fun tryParse(value: String?): Optional<SteamAddress> {
            if (value == null) {
                return Optional.empty()
            }

            val normalized = value.trim().lowercase(Locale.ROOT)
            val shortMatch = SHORT_PATTERN.matchEntire(normalized)
            if (shortMatch != null) {
                return parseShort(shortMatch)
            }

            val legacyMatch = LEGACY_PATTERN.matchEntire(normalized)
            if (legacyMatch != null) {
                return parseLegacy(legacyMatch)
            }

            return Optional.empty()
        }

        private fun parseShort(match: MatchResult): Optional<SteamAddress> = try {
            val steamId = java.lang.Long.parseUnsignedLong(match.groupValues[1], Character.MAX_RADIX)
            if (steamId == 0L) {
                Optional.empty()
            } else {
                Optional.of(SteamAddress(steamId, decodeBase36Token(match.groupValues[2])))
            }
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }

        private fun parseLegacy(match: MatchResult): Optional<SteamAddress> = try {
            val steamId = java.lang.Long.parseUnsignedLong(match.groupValues[1])
            if (steamId == 0L) {
                Optional.empty()
            } else {
                Optional.of(SteamAddress(steamId, HexCodec.decode(match.groupValues[2])))
            }
        } catch (_: IllegalArgumentException) {
            Optional.empty()
        }

        private fun decodeBase36Token(encoded: String): ByteArray {
            val value = BigInteger(encoded, Character.MAX_RADIX)
            if (value.signum() < 0 || value.bitLength() > TOKEN_LENGTH * Byte.SIZE) {
                throw IllegalArgumentException("Steam invite token exceeds 128 bits")
            }

            val compact = value.toByteArray()
            val sourceOffset = if (compact.size > TOKEN_LENGTH) compact.size - TOKEN_LENGTH else 0
            val copyLength = compact.size - sourceOffset
            val decoded = ByteArray(TOKEN_LENGTH)
            System.arraycopy(compact, sourceOffset, decoded, decoded.size - copyLength, copyLength)
            return decoded
        }
    }
}
