package dev.mahlernim.gasselfmeter

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Entirely synthetic HTTPS page and XHR, intercepted locally. No provider network requests. */
class EnergyTalkWebViewTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun blocksMainFramePostOutsideAllowlistBeforeNetwork() {
        var blocks = 0
        var tokens = 0
        val client = EnergyTalkWebClient({ tokens++ }, { blocks++ })
        fun request(url: String, mainFrame: Boolean = true) = object : WebResourceRequest {
            override fun getUrl() = Uri.parse(url)
            override fun isForMainFrame() = mainFrame
            override fun isRedirect() = false
            override fun hasGesture() = true
            override fun getMethod() = "POST"
            override fun getRequestHeaders() = mutableMapOf<String, String>()
        }
        for (url in listOf("https://foreign.invalid/submit", "http://energytalk.ai/login", "https://energytalk.ai.foreign.invalid/login")) {
            val response = client.shouldInterceptRequest(null, request(url))!!
            assertEquals(403, response.statusCode)
            assertEquals("no-store", response.responseHeaders["Cache-Control"])
            response.data.close()
        }
        assertEquals(3, blocks)
        assertEquals(0, tokens)
        assertNull(client.shouldInterceptRequest(null, request("https://accounts.kakao.com/login")))
        assertNull(client.shouldInterceptRequest(null, request("https://energytalk.ai/gas")))
        // The boundary constrains navigation, not unrelated login assets. No requests are executed.
        assertNull(client.shouldInterceptRequest(null, request("https://foreign.invalid/style.css", false)))
    }

    @Test fun observesSyntheticXhrHeaderWithoutJavascriptBridge() {
        val latch = CountDownLatch(1)
        val observed = AtomicReference<String?>()
        var web: WebView? = null
        rule.runOnUiThread {
            web = WebView(rule.activity).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : EnergyTalkWebClient({ observed.set(it); latch.countDown() }, {}) {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                        super.shouldInterceptRequest(view, request)
                        return WebResourceResponse("application/json", "UTF-8", ByteArrayInputStream("{}".toByteArray()))
                    }
                }
                loadDataWithBaseURL("https://energytalk.ai/", """<html><script>const r = new XMLHttpRequest(); r.open('POST', '/api/fetch'); r.setRequestHeader('Authorization', 'Bearer synthetic-token-12345'); r.setRequestHeader('Content-Type', 'application/json'); r.send('{}');</script></html>""", "text/html", "UTF-8", null)
            }
            rule.activity.setContentView(web)
        }
        try {
            assertTrue("WebView did not expose the request Authorization header", latch.await(15, TimeUnit.SECONDS))
            assertEquals("synthetic-token-12345", observed.get())
        } finally {
            rule.runOnUiThread { web?.stopLoading(); web?.destroy() }
        }
    }
}
