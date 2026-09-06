package dev.mahlernim.gasselfmeter

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.EmptyCoroutineContext

internal data class AlphaProbeResult(val lines: List<String>, val feedback: String)

internal object AlphaConnections {
    val directProviders = listOf("knenergy")
    val energyTenants = linkedMapOf("cncity" to "CNCITY에너지", "kne" to "경남에너지", "ktrm" to "귀뚜라미에너지",
        "miraense" to "미래엔서해에너지", "srb" to "서라벌도시가스", "gse" to "지에스이",
        "cwjgas" to "참빛원주도시가스", "ccbgas" to "참빛충북도시가스", "cydgas" to "참빛영동도시가스",
        "cdhgas" to "참빛영동도시가스 동해지점", "cscgas" to "참빛속초도시가스")

    suspend fun run(providerId: String, identity: String, password: String): AlphaProbeResult {
        val quick = if (providerId == "knenergy") KyungnamQuickClient() else null
        val probe = if (providerId in setOf("daesung", "daesungclean")) DaesungReadProbe(providerId) else null
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { quick?.close(); probe?.cancel() }
            Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                try {
                    if (continuation.isActive) continuation.resume(check(providerId, identity, password, quick, probe))
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                finally { quick?.close(); probe?.close() }
            })
        }
    }

    private fun check(providerId: String, identity: String, password: String,
        quick: KyungnamQuickClient?, probe: DaesungReadProbe?): AlphaProbeResult = when (providerId) {
        "knenergy" -> requireNotNull(quick).let { client ->
            val bill = client.lookup(identity)
            if (bill == null) AlphaProbeResult(listOf("조회 가능한 고지서가 없어요. 고객번호가 틀렸다는 뜻은 아니며, 공식 사이트에서도 확인해 주세요."),
                "공급사 knenergy · 단계 bills · 조회 결과 없음")
            else AlphaProbeResult(listOf(
                "고지년월 ${bill.billMonth}",
                "고지금액 ${bill.billedAmount?.let { "${decimalText(it, 0)}원" } ?: "정보 없음"}",
                "보정사용량 ${bill.correctedUsage?.let { "${decimalText(it, 3)} m³" } ?: "정보 없음"}",
                "사용열량 ${bill.energyUsageMj?.let { "${decimalText(it, 3)} MJ" } ?: "정보 없음"}",
                "보정사용량은 계량기 숫자의 차이와 다를 수 있어 추정 이력에 자동으로 합치지 않아요.",
            ), "공급사 knenergy · 단계 bills · 고지월 수신 · 금액 있음=${bill.billedAmount != null} · 보정사용량 있음=${bill.correctedUsage != null}")
        }
        "daesung", "daesungclean" -> requireNotNull(probe).let { client ->
            val result = client.check(identity, password)
            AlphaProbeResult(listOf(
                "로그인 화면 구조 ${if (result.sessionStructureObserved) "확인됨" else "확인하지 못했어요."}",
                "월별 요금 페이지 ${if (result.billingPageReached) "확인됨" else "확인하지 못했어요."}",
                "페이지의 표 ${result.tableCount}개 · 인식한 항목 ${result.recognizedBillingColumns.joinToString().ifBlank { "없음" }}",
                "공급사 추가 조회 결과예요.",
            ), "공급사 $providerId · 단계 bills · 세션 유사 구조=${result.sessionStructureObserved} · 요금 페이지=${result.billingPageReached} · 표=${result.tableCount} · 인식 항목=${result.recognizedBillingColumns.joinToString()}")
        }
        else -> throw ProviderFailure("connect", "unsupported")
    }
}

/** Explicit per-attempt consent, ephemeral credentials/results, never merges into household data. */
@Composable internal fun AlphaConnectionsDialog(initialProviderId: String, onDismiss: () -> Unit,
    runCheck: suspend (String, String, String) -> AlphaProbeResult = { provider, identity, password ->
        AlphaConnections.run(provider, identity, password)
    }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var providerId by remember { mutableStateOf(initialProviderId.takeIf { it in AlphaConnections.directProviders } ?: "knenergy") }
    var menu by remember { mutableStateOf(false) }
    var identity by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<AlphaProbeResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var energyMode by remember { mutableStateOf(false) }
    var energyTenant by remember { mutableStateOf("cncity") }
    var energyOpen by remember { mutableStateOf(false) }
    if (energyOpen) {
        EnergyTalkConnectDialog(energyTenant, onDismiss = { energyOpen = false }, onResult = { snapshot ->
            result = AlphaProbeResult(buildList {
                add("조회 주소 ${snapshot.address}")
                snapshot.usage.forEach { add("${it.month} · ${it.amount}원 · ${it.usage}") }
                snapshot.meter?.let { add("자가검침 상태 ${if (it.eligible) "가능 표시" else "불가 표시"}")
                    add("이전 지침 ${it.previous ?: "정보 없음"} · 최근 지침 ${it.recent ?: "정보 없음"}") }
                addAll(snapshot.unavailable)
                add("조회 내용은 이 창에 표시하며 내 기록과 추정 모델에는 자동 반영하지 않아요.")
            }, "EnergyTalk 공급사 ${snapshot.clientId} · 사용량 ${snapshot.usage.size}행 · 검침 상태 있음=${snapshot.meter != null} · 미확인 영역 ${snapshot.unavailable.size}개")
            energyOpen = false
        })
        return
    }
    val quickBill = providerId == "knenergy"
    val valid = if (quickBill) identity.trim().matches(Regex("[0-9]{1,9}")) else identity.isNotBlank() && password.isNotBlank()
    fun close() { requestJob?.cancel(); onDismiss() }
    AlertDialog(onDismissRequest = ::close,
        title = { Text("공급사 추가 조회") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("선택한 공급사의 청구 정보와 자가검침 상태를 추가로 확인할 수 있어요. 조회를 시작할 때 선택한 공급사에만 요청합니다.")
                Row {
                    FilterChip(selected = !energyMode, enabled = !busy, onClick = { energyMode = false; result = null; error = null; consent = false }, label = { Text("고객번호·계정 조회") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = energyMode, enabled = !busy, onClick = { energyMode = true; identity = ""; password = ""; result = null; error = null; consent = false }, label = { Text("에너지톡") })
                }
                if (energyMode) {
                    Text("선택한 EnergyTalk 공급사의 공식 로그인과 사용량·자가검침 상태를 확인해요.")
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text(AlphaConnections.energyTenants.getValue(energyTenant)) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            AlphaConnections.energyTenants.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                                energyTenant = id; menu = false; result = null; error = null; copied = false
                            }) }
                        }
                    }
                    Button(onClick = { result = null; copied = false; energyOpen = true }) { Text("공식 로그인으로 조회") }
                } else {
                Box {
                    OutlinedButton(onClick = { menu = true }, enabled = !busy) { Text(Providers.get(providerId).name) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        AlphaConnections.directProviders.forEach { id ->
                            DropdownMenuItem(text = { Text(Providers.get(id).name) }, onClick = {
                                providerId = id; identity = ""; password = ""; consent = false; result = null; error = null; menu = false
                            })
                        }
                    }
                }
                Text(if (quickBill) "고객번호로 현재 고지금액·보정사용량을 조회해요. 비밀번호나 문자 인증은 사용하지 않아요."
                    else "아이디와 비밀번호로 로그인한 뒤 월별 요금 페이지를 확인해요.")
                OutlinedTextField(identity, { if (it.length <= 100) identity = it }, enabled = !busy, singleLine = true,
                    label = { Text(if (quickBill) "고객번호 (최대 9자리)" else "아이디") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (quickBill) KeyboardType.Number else KeyboardType.Text))
                if (!quickBill) OutlinedTextField(password, { if (it.length <= 256) password = it }, enabled = !busy, singleLine = true,
                    label = { Text("비밀번호") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(Modifier.fillMaxWidth().toggleable(consent, enabled = !busy, role = Role.Checkbox, onValueChange = { consent = it }).padding(vertical = 4.dp)) {
                    Checkbox(consent, onCheckedChange = null)
                    Text("내 계정 또는 조회 권한이 있는 고객번호이며, 선택한 공급사로 직접 보내 조회하는 데 동의해요.", Modifier.weight(1f))
                }
                Text("입력값과 조회 내용은 이 창에서만 사용하며 내 기록·백업에 저장하지 않아요. 실패 시 오류 단계와 코드만 기기에 남겨요.", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.lines?.forEach { Text(it) }
                val report = result?.feedback ?: error
                if (report != null) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("공급사 추가 조회", "앱 ${BuildConfig.VERSION_NAME}\n$report"))
                    copied = true
                }) { Text(if (copied) "진단 요약 복사됨" else "개인정보 없는 진단 요약 복사") }
                TextButton(onClick = { AlphaFeedback.share(context, report) }) { Text("피드백 초안 보내기") }
                }
            }
        },
        confirmButton = { if (!energyMode) TextButton(enabled = !busy && consent && valid, onClick = {
            busy = true; result = null; error = null; copied = false
            val selected = providerId; val input = identity; val secret = password
            requestJob = scope.launch {
                try { result = runCheck(selected, input, secret) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    error = withContext(Dispatchers.IO) { Diagnostics.record(context, selected, "connect", e) }
                } finally { password = ""; busy = false; consent = false }
            }
        }) { Text("추가 조회 실행") } },
        dismissButton = { TextButton(onClick = {
            if (busy) { requestJob?.cancel(); error = "조회를 취소했어요. 다시 실행할 수 있어요." } else close()
        }) { Text(if (busy) "조회 취소" else "닫기") } },
    )
}
