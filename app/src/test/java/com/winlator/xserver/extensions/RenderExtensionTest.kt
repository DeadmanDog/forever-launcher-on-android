package com.winlator.xserver.extensions

import com.winlator.xconnector.XOutputStream
import com.winlator.xserver.CursorManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteOrder

/**
 * The RENDER replies must be byte for byte what tools/xserver-tests/run_render_cursor_regressions.py serves to the real
 * libX11/libXrender/libXcursor (which then build a full-colour cursor). The golden files are that script's replies.
 */
class RenderExtensionTest {
    private val golden = listOf(File("src/test/resources/render"), File("app/src/test/resources/render")).first { it.isDirectory }

    private fun bytes(write: (XOutputStream) -> Unit): ByteArray {
        val out = XOutputStream(16).apply { setByteOrder(ByteOrder.LITTLE_ENDIAN) }
        write(out)
        val buffer = out.buffer.duplicate().apply { flip() }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    @Test fun versionReplyIsRender07() {
        assertArrayEquals(File(golden, "version.bin").readBytes(), bytes { RenderExtension.writeVersionReply(it, 0) })
    }

    @Test fun pictFormatsReplyOffersArgb32AndTheRootVisualFormat() {
        assertArrayEquals(File(golden, "pictformats.bin").readBytes(), bytes { RenderExtension.writePictFormatsReply(it, 0, 0x21) })
    }

    @Test fun filtersReplyIsEmpty() {
        assertArrayEquals(File(golden, "filters.bin").readBytes(), bytes { RenderExtension.writeFiltersReply(it, 0) })
    }

    @Test fun premultipliedCursorPixelsBecomeStraightAlphaForTheRenderer() {
        assertEquals(0xffd4a017.toInt(), CursorManager.unpremultiply(0xffd4a017.toInt()))
        assertEquals(0, CursorManager.unpremultiply(0x00123456))
        // Half-transparent grey 0x40 at alpha 0x80 is grey 0x80 with straight alpha.
        assertEquals(0x80808080.toInt(), CursorManager.unpremultiply(0x80404040.toInt()))
        assertEquals(0xc0aa8055.toInt(), CursorManager.unpremultiply(0xc0806040.toInt()))
        // A colour channel above its alpha (invalid premultiplied input) is clamped, not wrapped.
        assertEquals(0x10ffffff, CursorManager.unpremultiply(0x10ffffff))
    }
}
