package io.zer0.muse.ui.markdown

import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class CardHeightSinkTest {

    @Test
    fun `registration exposes the same sink under the existing script name`() {
        val webView = WebView(RuntimeEnvironment.getApplication())
        try {
            val sink = CardHeightSink()
            sink.registerWith(webView)

            assertSame(sink, shadowOf(webView).getJavascriptInterface("MuseHeightSink"))
            val exposedMethods = sink.javaClass.methods
                .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
                .map { it.name }
            assertEquals(listOf("reportHeight"), exposedMethods)
        } finally {
            webView.destroy()
        }
    }

    @Test
    fun `positive heights are posted to the callback and invalid heights are ignored`() {
        val heights = mutableListOf<Int>()
        val sink = CardHeightSink()
        sink.onHeightChanged = { heights += it }

        sink.reportHeight(0)
        sink.reportHeight(-1)
        sink.reportHeight(240)

        assertEquals(emptyList<Int>(), heights)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(240), heights)
    }
}
