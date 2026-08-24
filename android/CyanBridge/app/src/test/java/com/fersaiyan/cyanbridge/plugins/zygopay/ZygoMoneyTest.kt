package com.fersaiyan.cyanbridge.plugins.zygopay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The amount a user hears is the only description of the payment they get, so the wording is part
 * of the contract, not presentation.
 */
class ZygoMoneyTest {

    @Test
    fun `spoken amounts read as money, not as digits`() {
        assertEquals("450 rupees 67 paise", spokenRupees(45067))
        assertEquals("450 rupees", spokenRupees(45000))
        assertEquals("50 paise", spokenRupees(50))
        // Singular, because "1 rupees" is the kind of detail that makes a synthetic voice sound
        // like a machine reading a database row.
        assertEquals("1 rupee", spokenRupees(100))
        assertEquals("2 rupees", spokenRupees(200))
    }

    @Test
    fun `displayed amounts always keep two decimals`() {
        assertEquals("450.67", formatRupees(45067))
        assertEquals("450.00", formatRupees(45000))
        assertEquals("0.05", formatRupees(5))
    }

    @Test
    fun `base units render exactly at six decimals`() {
        // USDC is 6-decimal. These are the values that a Double would start rounding.
        assertEquals("1.5", formatBaseUnits(1_500_000, 6))
        assertEquals("0.000001", formatBaseUnits(1, 6))
        assertEquals("12", formatBaseUnits(12_000_000, 6))
        assertEquals("9007199.254741", formatBaseUnits(9_007_199_254_741, 6))
        assertEquals("0", formatBaseUnits(0, 6))
    }

    @Test
    fun `terminal failure states are recognised by prefix, not by an exact list`() {
        // The server owns this state machine. An unrecognised terminal state must not read as
        // "still working", or the user waits forever on a payment that already died.
        listOf("failed", "failed_escrow", "cancelled", "expired", "refunded", "rejected").forEach {
            val status = OrderStatus("o", it, 100, null)
            assertTrue("$it should be terminal", status.isTerminalFailure)
            assertFalse("$it should not be settled", status.isSettled)
        }
    }

    @Test
    fun `in-flight states are neither settled nor failed`() {
        listOf("created", "escrow_pending", "paying_out", "awaiting_deposit").forEach {
            val status = OrderStatus("o", it, 100, null)
            assertFalse("$it should not be terminal", status.isTerminalFailure)
            assertFalse("$it should not be settled", status.isSettled)
        }
    }

    @Test
    fun `completed is the only settled state`() {
        assertTrue(OrderStatus("o", "completed", 100, null).isSettled)
        assertTrue(OrderStatus("o", "COMPLETED", 100, null).isSettled)
        assertFalse(OrderStatus("o", "completing", 100, null).isSettled)
    }

    @Test
    fun `base58 encodes the way a solana address is written`() {
        // Known vectors: leading zero bytes become leading '1's, and losing them would silently
        // produce a different address.
        assertEquals("", Base58.encode(byteArrayOf()))
        assertEquals("1", Base58.encode(byteArrayOf(0)))
        assertEquals("11", Base58.encode(byteArrayOf(0, 0)))
        assertEquals("2g", Base58.encode(byteArrayOf(0x61)))
        assertEquals("a3gV", Base58.encode("bbb".toByteArray()))
        assertEquals("aPEr", Base58.encode("ccc".toByteArray()))
        assertEquals("125KHVyRP932b4", Base58.encode(byteArrayOf(0) + "The quick".toByteArray()))
    }

    @Test
    fun `a 32-byte key encodes to a plausible address length`() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val encoded = Base58.encode(key)
        assertTrue("got ${encoded.length}: $encoded", encoded.length in 32..44)
    }

    @Test
    fun `abbreviation keeps both ends so two wallets can be told apart`() {
        assertEquals("ABCD…WXYZ", Base58.abbreviate("ABCDefghijklmnopqrstuvWXYZ"))
        // Short strings are returned whole rather than mangled into something unrecognisable.
        assertEquals("ABC", Base58.abbreviate("ABC"))
    }
}
