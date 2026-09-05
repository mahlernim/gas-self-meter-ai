package dev.mahlernim.gasselfmeter

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayInputStream

/** Observes only a bearer header already sent by the official page. Never replays a request. */
internal open class EnergyTalkWebClient(
    private val onToken: (String) -> Unit,
    private val onBlocked: () -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        // Android does not invoke shouldOverrideUrlLoading for POST navigations.
        // Enforce the same boundary before a main-frame form request can leave the allowlist.
        if (request != null && request.isForMainFrame &&
            !EnergyTalkBoundary.navigationAllowed(request.url.toString())) {
            onBlocked()
            return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        }
        if (request != null && EnergyTalkBoundary.officialProxy(request.url.toString())) {
            val header = request.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", true) }?.value
            EnergyTalkBoundary.token(header)?.let(onToken)
        }
        return null
    }
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val blocked = request == null || !EnergyTalkBoundary.navigationAllowed(request.url.toString())
        if (blocked) onBlocked()
        return blocked
    }
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) { handler.cancel() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EnergyTalkConnectDialog(clientId: String, onDismiss: () -> Unit, onResult: (EnergyTalkSnapshot) -> Unit) {
    require(clientId in EnergyTalkBoundary.tenants)
    var consented by remember(clientId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val candidate = remember(clientId) { AtomicReference<String?>(null) }
    val sessionClosed = remember(clientId) { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<WebView?>(null) }
    val latestResult by rememberUpdatedState(onResult)
    DisposableEffect(clientId) {
        onDispose {
            synchronized(candidate) { sessionClosed.set(true); candidate.set(null) }
            browser?.apply { runCatching { stopLoading(); clearHistory() }; runCatching { destroy() } }
            // Best effort only. Other cookie paths and abnormal shutdown may retain provider data.
            // Never clear the global store or Kakao/other provider sessions.
            if (consented) runCatching { WebStorage.getInstance().deleteOrigin("https://energytalk.ai") }
            runCatching {
                val cookies = CookieManager.getInstance()
                (if (consented) cookies.getCookie("https://energytalk.ai") else null)?.split(';')?.forEach { entry ->
                    val name = entry.substringBefore('=').trim()
                    if (name.matches(Regex("[A-Za-z0-9_-]+"))) {
                        cookies.setCookie("https://energytalk.ai", "$name=; Max-Age=0; Path=/; Secure")
                        cookies.setCookie("https://energytalk.ai", "$name=; Max-Age=0; Domain=energytalk.ai; Path=/; Secure")
                    }
                }
                cookies.flush()
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Text("에너지톡 실험적 조회", style = MaterialTheme.typography.titleLarge)
                if (!consented) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    Text("공식 에너지톡·카카오 화면에서 직접 로그인하고 조회할 주소를 선택해 주세요. 공식 서비스의 가입·약관 동의가 발생할 수 있어요. 이 웹 화면은 조회 전용이 아니므로 결제나 검침 제출 버튼을 누르지 마세요.")
                    Spacer(Modifier.height(12.dp))
                    Text("앱은 공식 페이지가 보내는 로그인 세션을 기기 메모리에서만 확인하고, 선택한 공급사의 사용량·자가검침 상태를 가져와요. 비밀번호를 읽거나 로그인 코드를 대신 교환하지 않아요. 결과는 참고용이며 추정 모델이나 자동 제출에 사용하지 않아요.")
                    Spacer(Modifier.height(12.dp))
                    Text("정상적으로 닫을 때 에너지톡 웹 저장소와 일부 쿠키 삭제를 시도해요. 비정상 종료나 쿠키 경로에 따라 로그인 정보가 남을 수 있어요. 카카오 로그인 쿠키는 지우지 않아요.")
                    Button(onClick = { consented = true }) { Text("동의하고 공식 로그인 열기") }
                    TextButton(onClick = onDismiss) { Text("취소") }
                    }
                } else {
                    Text("공식 화면에서 주소를 확인한 뒤 가져오기를 누르세요.", style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    diagnostic?.let { code ->
                        TextButton(onClick = { clipboard.setText(AnnotatedString("EnergyTalk ${BuildConfig.VERSION_NAME} · $code · Android ${android.os.Build.VERSION.SDK_INT}")) }) {
                            Text("개인정보 없는 오류 진단 복사")
                        }
                    }
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = EnergyTalkWebClient(
                                onToken = { synchronized(candidate) { if (!sessionClosed.get()) candidate.set(it) } },
                                onBlocked = { post { if (!sessionClosed.get()) { diagnostic = "navigation_blocked"; message = "외부 앱 또는 허용되지 않은 이동을 차단했어요. 이 로그인 방식은 아직 지원하지 않아요." } } },
                            )
                            browser = this
                            loadUrl("https://energytalk.ai/gas?clientId=$clientId")
                        }
                    })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("닫기") }
                        Button(enabled = !busy, onClick = {
                            val token = candidate.get()
                            if (token == null) { diagnostic = "session_header_missing"; message = "로그인 세션을 확인하지 못했어요. 공식 로그인을 완료하고 주소를 선택해 주세요. 계속 안 되면 이 기기에서는 연결을 지원하지 못해요." }
                            else {
                                busy = true; message = null; diagnostic = null
                                scope.launch {
                                    try {
                                        val snapshot = withContext(Dispatchers.IO) { EnergyTalkReadClient().verifyAndRead(token, clientId) }
                                        candidate.set(null)
                                        latestResult(snapshot)
                                    } catch (e: CancellationException) {
                                        candidate.set(null)
                                        throw e
                                    } catch (_: EnergyTalkAuthException) {
                                        candidate.set(null); diagnostic = "authentication_failed"; message = "로그인 상태를 확인하지 못했어요. 공식 화면에서 다시 로그인해 주세요."
                                    } catch (_: Exception) {
                                        diagnostic = "tenant_address_or_read_failed"; message = "공급사·주소 또는 조회 응답을 확인하지 못했어요. 공식 화면의 주소를 확인한 뒤 다시 시도해 주세요."
                                    } finally { busy = false }
                                }
                            }
                        }) { Text(if (busy) "확인 중" else "이 주소로 가져오기") }
                    }
                }
            }
        }
    }
}
