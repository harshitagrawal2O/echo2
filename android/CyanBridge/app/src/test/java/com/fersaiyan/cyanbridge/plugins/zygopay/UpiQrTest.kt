package com.fersaiyan.cyanbridge.plugins.zygopay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app is willing to treat as a payment instruction.
 *
 * The refusals matter more than the acceptances here. A blind user pointing a camera at a wall of
 * stickers will photograph product barcodes, Wi-Fi codes and URLs, and every one of those reaching
 * a payment screen is a chance to pay something they did not mean to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpiQrTest {

    @Test
    fun `parses a standard merchant intent`() {
        val qr = UpiQr.parse("upi://pay?pa=merchant@bank&pn=Chai%20Stall&am=45.50&cu=INR&tn=Tea")
        requireNotNull(qr)
        assertEquals("merchant@bank", qr.payeeAddress)
        assertEquals("Chai Stall", qr.payeeName)
        assertEquals(4550L, qr.amountMinor())
        assertEquals("Tea", qr.note)
    }

    @Test
    fun `an open amount stays null rather than becoming zero`() {
        // A QR with no amount must reach the "how much?" branch. Defaulting to zero here would
        // quote a zero-rupee payment and look like a service failure instead of a missing amount.
        val qr = UpiQr.parse("upi://pay?pa=merchant@bank&pn=Shop")
        requireNotNull(qr)
        assertNull(qr.amountMinor())
    }

    @Test
    fun `rejects everything that is not a upi payment intent`() {
        listOf(
            "https://example.com/pay?pa=merchant@bank",
            "WIFI:S:MyNetwork;T:WPA;P:hunter2;;",
            "8901234567890",
            "upi://mandate?pa=merchant@bank",
            "upi://pay?pn=NoPayeeAddress",
            "",
            "   ",
        ).forEach { text ->
            assertNull("should have refused: $text", UpiQr.parse(text))
        }
    }

    @Test
    fun `amounts are parsed as integers so no rupee is lost to floating point`() {
        fun minor(am: String) = UpiQr.parse("upi://pay?pa=a@b&am=$am")?.amountMinor()

        assertEquals(10L, minor("0.1"))
        assertEquals(1L, minor("0.01"))
        assertEquals(100L, minor("1"))
        assertEquals(100L, minor("1.00"))
        assertEquals(3333333333L, minor("33333333.33"))
        // Third decimal is truncated, not rounded: paying more than the code says is worse than
        // paying the stated amount.
        assertEquals(1029L, minor("10.299"))
    }

    @Test
    fun `malformed amounts are refused rather than guessed`() {
        fun minor(am: String) = UpiQr.parse("upi://pay?pa=a@b&am=$am")?.amountMinor()

        assertNull(minor("abc"))
        assertNull(minor("1.2.3"))
        assertNull(minor("-5"))
    }

    @Test
    fun `scheme is matched case-insensitively`() {
        // Real-world QRs are not consistent about this, and a refusal here reads to the user as
        // "that isn't a payment code" when it plainly is.
        requireNotNull(UpiQr.parse("UPI://PAY?pa=merchant@bank"))
    }
}
