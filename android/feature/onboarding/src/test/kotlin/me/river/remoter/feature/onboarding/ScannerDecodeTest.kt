package me.river.remoter.feature.onboarding

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

// an S25 couldn't scan `remoterctl pair` off a dark kitty: light on dark, the analyzer only knew dark on light
class ScannerDecodeTest {
    private val link = "remoter://pair?h=10.66.66.3&p=8444&t=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&f=BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

    // camera-ish: the code on a wider plane, with row padding
    private fun frame(inverted: Boolean): Triple<ByteArray, Int, Pair<Int, Int>> {
        val m = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 600, 600)
        val w = 800
        val h = 640
        val stride = 832
        val dark: Byte = if (inverted) 0xE0.toByte() else 0x20
        val light: Byte = if (inverted) 0x20 else 0xE0.toByte()
        val data = ByteArray(stride * h) { light }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m[x, y]) data[(y + 20) * stride + x + 100] = dark
        }
        return Triple(data, stride, w to h)
    }

    @Test
    fun decodes_dark_on_light() {
        val (d, stride, size) = frame(inverted = false)
        assertEquals(link, decodeLuma(d, stride, size.first, size.second)?.text)
    }

    @Test
    fun decodes_light_on_dark() {
        val (d, stride, size) = frame(inverted = true)
        assertEquals(link, decodeLuma(d, stride, size.first, size.second)?.text)
    }

    @Test
    fun ultrawide_stop_only_if_present() {
        assertEquals(listOf(0.6f, 1f, 2f, 3f), zoomStops(0.6f, 10f))
        assertEquals(listOf(1f, 2f), zoomStops(1f, 2.5f))
        assertEquals(listOf("0.6×", "1×", "2×"), listOf(0.6f, 1f, 2f).map(::zoomLabel))
    }
}
