package dev.mahlernim.gasselfmeter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Authentication input deliberately never uses rememberSaveable or persistent storage. */
@Composable
fun GasappConnectScreen(
    providerId: String,
    onConnected: (GasappSession, GasappAccount) -> Unit,
    onCancel: () -> Unit,
) {
    val api = remember { GasappApi() }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var birthday by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var carrier by remember { mutableStateOf("1") }
    var terms by remember { mutableStateOf<List<GasappTerms>>(emptyList()) }
    var accepted by remember { mutableStateOf(false) }
    var challenge by remember { mutableStateOf<GasappSms?>(null) }
    var identity by remember { mutableStateOf<GasappIdentity?>(null) }
    var otp by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<GasappSession?>(null) }
    var accounts by remember { mutableStateOf<List<GasappAccount>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var shownTerms by remember { mutableStateOf<GasappTerms?>(null) }
    var resendAfter by remember { mutableIntStateOf(0) }
    DisposableEffect(api) { onDispose { api.close() } }
    LaunchedEffect(resendAfter) { if (resendAfter > 0) { delay(1000); resendAfter-- } }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = readableError(e) }
            finally { busy = false }
        }
    }
    fun loadAccounts(active: GasappSession) = run {
        accounts = withContext(Dispatchers.IO) { api.accounts(active) }.filter { GasappApi.companyProviders[it.company] == providerId }
        if (accounts.isEmpty()) error = "선택한 공급사의 계약이 없어요. 가스앱에서 사용 계약을 등록한 후 다시 확인해 주세요."
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("가스앱 연결", style = MaterialTheme.typography.headlineSmall)
        when {
            session != null -> {
                Text("연결할 계약을 선택해 주세요.")
                accounts.forEach { account ->
                    OutlinedButton(onClick = { onConnected(session!!, account) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("${account.label}\n${account.contract.ifBlank { account.customer }}")
                    }
                }
                TextButton(onClick = { loadAccounts(session!!) }, enabled = !busy) { Text("계약 다시 확인") }
            }
            challenge != null -> {
                Text("휴대전화로 받은 인증번호를 입력해 주세요.")
                OutlinedTextField(value = otp, onValueChange = { otp = it.filter(Char::isDigit).take(6) }, label = { Text("인증번호") },
                    singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Button(onClick = { run {
                    val active = withContext(Dispatchers.IO) { api.confirmSms(identity!!, challenge!!, otp) }
                    session = active
                    identity = null; challenge = null; otp = ""; name = ""; phone = ""; birthday = ""; gender = ""
                    accounts = withContext(Dispatchers.IO) { api.accounts(active) }.filter { GasappApi.companyProviders[it.company] == providerId }
                    if (accounts.isEmpty()) error = "선택한 공급사의 계약이 없어요. 가스앱에서 사용 계약을 등록한 후 다시 확인해 주세요."
                } }, enabled = !busy && otp.length == 6) { Text("인증하고 연결") }
                TextButton(onClick = { run {
                    challenge = withContext(Dispatchers.IO) { api.requestSms(identity!!, terms) }
                    otp = ""; resendAfter = 60
                } }, enabled = !busy && resendAfter == 0) { Text(if (resendAfter > 0) "${resendAfter}초 후 재요청" else "인증번호 다시 받기") }
                TextButton(onClick = { challenge = null; identity = null; otp = "" }, enabled = !busy) { Text("휴대전화 정보 수정") }
            }
            else -> {
                Text("휴대전화 본인인증으로 연결해요. 가스앱 회원 등록이 함께 진행될 수 있어요.")
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("이름") }, enabled = !busy, singleLine = true)
                OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) }, label = { Text("휴대전화 번호") }, enabled = !busy,
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(birthday, { birthday = it.filter(Char::isDigit).take(6) }, label = { Text("생년월일 6자리") }, enabled = !busy,
                        modifier = Modifier.weight(2f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(gender, { gender = it.filter(Char::isDigit).take(1) }, label = { Text("뒤 첫 자리") }, enabled = !busy,
                        modifier = Modifier.weight(1f), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1" to "SKT", "2" to "KT", "3" to "LG U+").forEach { (code, label) ->
                        FilterChip(selected = carrier == code, onClick = { carrier = code; terms = emptyList(); accepted = false },
                            enabled = !busy, label = { Text(label) })
                    }
                }
                if (terms.isEmpty()) {
                    OutlinedButton(onClick = { run { terms = withContext(Dispatchers.IO) { api.terms(carrier) } } }, enabled = !busy) { Text("필수 약관 확인") }
                } else {
                    terms.forEach { document -> TextButton(onClick = { shownTerms = document }) { Text(document.category) } }
                    Row { Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !busy); Text("필수 약관에 동의합니다.") }
                    Text("선택적 혜택 정보 수신에는 동의하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { run {
                    val person = GasappIdentity(name, phone, birthday, gender, carrier)
                    val sms = withContext(Dispatchers.IO) { api.requestSms(person, terms) }
                    identity = person; challenge = sms; resendAfter = 60
                } }, enabled = !busy && accepted && terms.isNotEmpty()) { Text("인증번호 받기") }
            }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onCancel, enabled = !busy) { Text("취소") }
    }
    shownTerms?.let { document ->
        AlertDialog(onDismissRequest = { shownTerms = null }, title = { Text(document.category) },
            text = { Text(document.text, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { shownTerms = null }) { Text("확인") } })
    }
}
