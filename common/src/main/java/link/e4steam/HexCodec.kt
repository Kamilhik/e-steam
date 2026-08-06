package link.e4steam

/**
 * Java 16-compatible lowercase hexadecimal codec used by the legacy build.
 */
object HexCodec {
    private val DIGITS = "0123456789abcdef".toCharArray()

    @JvmStatic
    fun encode(bytes: ByteArray): String = encode(bytes, 0, bytes.size)

    @JvmStatic
    fun encode(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw IndexOutOfBoundsException("Invalid hexadecimal byte range")
        }
        val result = CharArray(length * 2)
        for (i in 0 until length) {
            val value = bytes[offset + i].toInt() and 0xff
            result[i * 2] = DIGITS[value ushr 4]
            result[i * 2 + 1] = DIGITS[value and 0x0f]
        }
        return String(result)
    }

    @JvmStatic
    fun decode(value: String): ByteArray {
        if ((value.length and 1) != 0) {
            throw IllegalArgumentException("Hexadecimal text must contain an even number of characters")
        }
        val result = ByteArray(value.length / 2)
        for (i in result.indices) {
            val high = Character.digit(value[i * 2], 16)
            val low = Character.digit(value[i * 2 + 1], 16)
            if (high < 0 || low < 0) {
                throw IllegalArgumentException("Invalid hexadecimal character")
            }
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }
}
