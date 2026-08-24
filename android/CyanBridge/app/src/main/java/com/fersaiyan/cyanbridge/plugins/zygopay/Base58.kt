package com.fersaiyan.cyanbridge.plugins.zygopay

/**
 * Base58 (Bitcoin alphabet), which is how Solana addresses and signatures are written.
 *
 * Hand-rolled because the app has no Solana client library — the Mobile Wallet Adapter hands over
 * raw bytes and the service expects an address string, and that conversion is the whole need. The
 * alphabet excludes `0`, `O`, `I` and `l` precisely so that a human reading an address aloud cannot
 * confuse them, which is the reason this encoding exists at all.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Leading zero bytes are not carried by the numeric conversion, so they are counted and
        // re-emitted as '1's. Dropping them would silently produce a different address.
        var zeros = 0
        while (zeros < input.size && input[zeros] == 0.toByte()) zeros++

        val digits = IntArray(input.size * 2)
        var length = 0
        for (index in zeros until input.size) {
            var carry = input[index].toInt() and 0xFF
            var i = 0
            while (i < length || carry != 0) {
                if (i < length) carry += digits[i] shl 8
                digits[i] = carry % 58
                carry /= 58
                i++
            }
            length = i
        }

        val out = StringBuilder(zeros + length)
        repeat(zeros) { out.append(ALPHABET[0]) }
        for (i in length - 1 downTo 0) out.append(ALPHABET[digits[i]])
        return out.toString()
    }

    /**
     * A short, speakable form: first and last four characters.
     *
     * Reading a 44-character address aloud is useless — nobody verifies it and everybody stops
     * listening. Four either end is enough to tell two wallets apart when the user already knows
     * which one they connected, and it is honest about being an abbreviation.
     */
    fun abbreviate(address: String): String =
        if (address.length <= 12) address else address.take(4) + "…" + address.takeLast(4)
}
