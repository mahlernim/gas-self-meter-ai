package dev.mahlernim.gasselfmeter

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val Teal = Color(0xFF006C67)
private val DeepTeal = Color(0xFF053F49)
private val Coral = Color(0xFFFF845F)
private val Paper = Color(0xFFF7F8F4)
private val Ink = Color(0xFF192F32)
private val Muted = Color(0xFF526968)
private val Pale = Color(0xFFE4F0EB)
fun decimalText(value: Double?, digits: Int = 1): String = value?.let { String.format(Locale.KOREA, "%,.${digits}f", it) } ?: "아직 몰라요"

object AppLinks {
    const val PLAY_STORE = "market://details?id=dev.mahlernim.gasselfmeter"
    const val TESTING_PAGE = "https://play.google.com/apps/testing/dev.mahlernim.gasselfmeter"
    const val TESTER_GROUP = "https://groups.google.com/g/gas-self-meter-ai"
    const val PRIVACY = "https://github.com/mahlernim/gas-self-meter-ai/blob/main/PRIVACY.md"
    const val SOURCE = "https://github.com/mahlernim/gas-self-meter-ai"
    const val ISSUES = "https://github.com/mahlernim/gas-self-meter-ai/issues"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
                primaryContainer = Pale, onPrimaryContainer = DeepTeal, secondary = Coral,
                secondaryContainer = Pale, onSecondaryContainer = Teal,
                background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
                surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEF2EC), outline = Color(0xFF80938D))) {
                GasApp()
            }
        }
    }
}

@Composable fun GasApp(vm: GasViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var calibration by remember { mutableStateOf<String?>(null) }
    var addHistory by remember { mutableStateOf(false) }
    var loginProviderId by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var restoreRaw by remember { mutableStateOf<String?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var submitValue by remember { mutableStateOf<Double?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    val data = vm.data
    val estimate = remember(data, now) { Estimator.estimate(data, now) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.attempt {
            context.contentResolver.openOutputStream(uri)?.use { it.write(DataCodec.encode(vm.data).toByteArray(Charsets.UTF_8)) } ?: error("파일을 열지 못했어요.")
            vm.message = "기록을 내보냈어요. 로그인 정보는 포함하지 않았어요."
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attempt {
            val raw = context.contentResolver.openInputStream(uri)?.use { String(it.readBytesLimited(2_000_000), Charsets.UTF_8) } ?: error("파일을 열지 못했어요.")
            DataCodec.decode(raw)
            restoreRaw = raw
        }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setReminder(granted, vm.data.profile.reminderDay, vm.data.profile.reminderHour)
        if (!granted) vm.message = "알림 권한이 꺼져 있어요. 기기 설정에서 허용할 수 있어요."
    }
    fun open(url: String) = vm.attempt { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    fun openUpdate() = vm.attempt {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.PLAY_STORE)).setPackage("com.android.vending"))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.TESTING_PAGE)))
        }
    }

    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (data.ready && !vm.storageError) NavigationBar(containerColor = Color.White) {
            listOf("검침" to Icons.Outlined.Speed, "제출" to Icons.Outlined.CloudUpload,
                "기록" to Icons.Outlined.BarChart, "설정" to Icons.Outlined.Settings).forEachIndexed { index, item ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, null) }, label = { Text(item.first) })
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.busy) {
                if (vm.progressTotal > 0) LinearProgressIndicator(progress = { (vm.progressCurrent.toFloat() / vm.progressTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(vm.progress, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            if (vm.storageError) {
                Page {
                    Title("기록을 보호하고 있어요", "저장 파일을 읽지 못했습니다. 기존 파일은 보존됩니다.")
                    ActionButton("백업 가져오기", Icons.Outlined.FileOpen) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    TextButton(onClick = { confirmation = "erase" }) { Text("저장 데이터 초기화") }
                }
            } else if (!data.ready) {
                Welcome(vm.busy, { vm.manual(it) }, { loginProviderId = it }, { vm.loadDemo() }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, ::open)
            } else when (tab) {
                0 -> Dashboard(data, estimate, now, { calibration = decimalText(estimate.reading).takeIf { estimate.reading != null } ?: "" }, { tab = 2 }, { vm.refresh() }, vm.busy)
                1 -> SubmissionPage(data, vm.selfReadTarget, now, vm.busy, vm::checkSubmissionStatus, { value -> submitValue = value }, vm::setSubmissionSettings)
                2 -> HistoryPage(data, { addHistory = true }, { period -> confirmation = "period:${data.periods.indexOf(period)}" }, { observation -> confirmation = "observation:${observation.time}" })
                3 -> SettingsPage(data, {
                    if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.setReminder(true, data.profile.reminderDay, data.profile.reminderHour)
                }, { vm.setReminder(false, data.profile.reminderDay, data.profile.reminderHour) },
                    { day, hour -> vm.setReminder(data.profile.reminder, day, hour) },
                    { loginProviderId = data.profile.providerId }, { vm.forgetCredentials() },
                    { export.launch("gas-self-meter-${today()}.json") }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    { confirmation = "meter" }, { confirmation = "erase" }, ::openUpdate, { licenses = true }, ::open, vm.busy)
            }
        }
    }
    calibration?.let { initial -> CalibrationDialog(initial, estimate, data, { calibration = null }) { value ->
        if (vm.calibrate(value)) calibration = null
    } }
    submitValue?.let { value ->
        AlertDialog(onDismissRequest = { submitValue = null }, title = { Text("검침값을 공급사에 입력할까요?") },
            text = { Text("${Providers.get(data.profile.providerId).name}에 ${decimalText(value)} m³를 입력합니다. 전송 직전에 기간과 기존 제출 여부를 다시 확인하며, 결과가 불확실하면 자동으로 다시 보내지 않습니다.") },
            confirmButton = { TextButton(onClick = { vm.submitReading(value); submitValue = null }) { Text("${decimalText(value)} m³ 입력") } },
            dismissButton = { TextButton(onClick = { submitValue = null }) { Text("취소") } })
    }
    if (addHistory) HistoryDialog({ addHistory = false }) { start, end, value ->
        vm.addPeriod(start, end, value)
        if (vm.message == "사용 이력을 저장했어요.") addHistory = false
    }
    loginProviderId?.let { providerId ->
        LoginDialog(Providers.skens(providerId), vm.busy, vm.progress, vm.progressCurrent, vm.progressTotal, vm.contracts, vm.loginError,
            { if (!vm.busy) { loginProviderId = null; vm.cancelLogin() } },
            { u, p, remember -> vm.login(providerId, u, p, remember) }, { vm.selectContract(it) }, ::open)
    }
    LaunchedEffect(data.profile.syncTime) { if (data.profile.syncTime != null && vm.contracts.isEmpty()) loginProviderId = null }
    LaunchedEffect(vm.busy) { if (!vm.busy && data.ready && data.profile.syncTime != null && vm.contracts.isEmpty() && vm.message?.contains("개월") == true) loginProviderId = null }
    confirmation?.let { action ->
        val title = when { action == "erase" -> "기기의 모든 기록을 지울까요?"; action == "meter" -> "새 계량기로 시작할까요?"; else -> "이 기록을 삭제할까요?" }
        val text = when (action) {
            "erase" -> "로그인 정보, 사용 이력, 확인 기록과 알림 설정을 지워요. 필요한 기록은 먼저 내보내 주세요."
            "meter" -> "이전 실측은 보관하고 새 계량기의 숫자로 다시 시작해요. 작년 사용 이력은 계절 추정에 계속 활용해요."
            else -> "삭제한 기록은 추정에 사용하지 않아요."
        }
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton(onClick = {
                when {
                    action == "erase" -> { vm.erase(); tab = 0 }
                    action == "meter" -> { vm.resetMeter(); tab = 1 }
                    action.startsWith("period:") -> data.periods.getOrNull(action.substringAfter(":").toInt())?.let(vm::deletePeriod)
                    action.startsWith("observation:") -> data.observations.find { it.time == action.substringAfter(":").toLong() }?.let(vm::deleteObservation)
                }; confirmation = null
            }) { Text(if (action == "meter") "새로 시작" else "삭제") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("취소") } })
    }
    restoreRaw?.let { raw ->
        val preview = remember(raw) { DataCodec.decode(raw) }
        AlertDialog(onDismissRequest = { restoreRaw = null }, title = { Text("백업 기록으로 바꿀까요?") },
            text = { Text("사용 이력 ${preview.periods.size}개와 확인 기록 ${preview.observations.size}개를 가져와요. 현재 기록은 대체되며 로그인 정보는 가져오지 않아요.") },
            confirmButton = { TextButton(onClick = { vm.restore(raw); restoreRaw = null; tab = 0 }) { Text("복원") } },
            dismissButton = { TextButton(onClick = { restoreRaw = null }) { Text("취소") } })
    }
    if (licenses) {
        val sections = remember {
            listOf(
                Triple("이 앱", "MIT 라이선스로 제공되는 앱 코드의 원문입니다.", "LICENSE.txt"),
                Triple("AndroidX, Kotlin, OkHttp 등", "Apache License 2.0을 사용하는 주요 구성요소의 원문입니다.", "APACHE-2.0.txt"),
                Triple("jsoup", "웹 문서 분석에 사용하는 jsoup의 MIT 라이선스 원문입니다.", "JSOUP-LICENSE.txt"),
                Triple("저작권 및 구성요소 고지", "앱에 포함된 구성요소와 출처에 관한 상세 고지입니다.", "NOTICE.md")
            ).map { (title, summary, asset) ->
                Triple(title, summary, context.assets.open(asset).bufferedReader().use { it.readText().trim() })
            }
        }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("오픈소스 라이선스") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("이 앱이 사용하는 오픈소스 구성요소와 라이선스 원문을 확인할 수 있어요.", color = Muted)
                    sections.forEachIndexed { index, section ->
                        if (index > 0) HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(section.first, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(section.second, color = Muted, style = MaterialTheme.typography.bodySmall)
                            Text(section.third, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("닫기") } })
    }
}

@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable private fun Title(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun ActionButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(text, fontWeight = FontWeight.SemiBold)
    }
}
@Composable private fun Badge(text: String, color: Color = Pale) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 10.dp, vertical = 5.dp), color = DeepTeal, style = MaterialTheme.typography.labelMedium)
}
@Composable private fun Hint(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(22.dp), tint = Teal)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable private fun Welcome(busy: Boolean, onManual: (String) -> Unit, onLogin: (String) -> Unit, onDemo: () -> Unit, onImport: () -> Unit, open: (String) -> Unit) {
    var region by rememberSaveable { mutableStateOf("부산") }
    var providerId by rememberSaveable { mutableStateOf("busan") }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.app_icon), null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
            Column { Text("똑똑", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold); Text("자가검침 AI", color = Muted, style = MaterialTheme.typography.labelLarge) }
        }
        Title("일주일에 한 번,\n우리 집 가스를 알아가요", "작년의 계절 흐름과 직접 확인한 숫자로\n오늘의 사용량을 가늠해요.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Badge("센서 없이"); Badge("기기 안에서 계산"); Badge("주 1회 확인") }
        SurfaceCard {
            Text("어디에 살고 계신가요?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Choice("지역", region, Providers.regions) { value ->
                region = value; providerId = Providers.all.firstOrNull { value in it.regions }?.id ?: "other"
            }
            val providers = Providers.all.filter { region in it.regions || it.id == "other" }
            Choice("도시가스 공급사", Providers.get(providerId).name, providers.map { it.name }) { name -> providerId = providers.first { it.name == name }.id }
            val provider = Providers.get(providerId)
            if (provider.automatic) {
                Hint(Icons.Outlined.CloudDownload, "${provider.name} 계정으로 청구 이력을 가져올 수 있어요.")
                ActionButton("${provider.name} 연결하기", Icons.Outlined.Login, !busy) { onLogin(providerId) }
                TextButton(onClick = { onManual(providerId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("로그인 없이 직접 시작") }
            } else {
                Hint(Icons.Outlined.EditNote, "이 공급사는 직접 입력으로 시작할 수 있어요. 자동 계정 연결은 아직 지원하지 않아요.")
                if (provider.gasapp) Text("가스앱에서 확인한 과거 사용량을 입력할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
                ActionButton("직접 입력으로 시작", Icons.Outlined.ArrowForward, !busy) { onManual(providerId) }
                TextButton(onClick = { open(provider.website) }) { Text("공급사 홈페이지에서 확인") }
            }
        }
        Hint(Icons.Outlined.Lock, "로그인 정보와 사용 기록은 기기에 암호화해 보관해요. 별도 서버나 광고·분석 도구를 사용하지 않아요.")
        Text("‘맞음’은 계량기를 실제로 보고 확인하는 버튼이에요. 공급사에 검침을 제출하는 기능은 없어요.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onDemo, enabled = !busy) { Text("예시로 둘러보기") }
            TextButton(onClick = onImport, enabled = !busy) { Text("백업 가져오기") }
        }
    }
}

@Composable private fun Dashboard(data: AppData, estimate: Estimate, now: Long, onCheck: () -> Unit, onHistory: () -> Unit, onRefresh: () -> Unit, busy: Boolean) {
    val provider = Providers.get(data.profile.providerId)
    val latest = data.periods.filter { it.meter == data.profile.meter && it.current != null && dayStart(it.last.plusDays(1)) <= now }.maxByOrNull { it.end }
    val usage = if (latest?.current != null && estimate.reading != null) (estimate.reading - latest.current).takeIf { it >= 0 } else null
    val cost = if (usage != null && latest?.unitCost != null) usage * latest.unitCost + (latest.baseCost ?: 0.0) else null
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("똑똑", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp); Text("${today().format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN))}", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Image(painterResource(R.drawable.app_icon), "똑똑 앱 아이콘", Modifier.size(48.dp).clip(CircleShape))
        }
        if (data.profile.meter == "demo") Badge("예시 데이터 · 실제 우리 집 기록이 아니에요", Color(0xFFFFE5D9))
        Text(provider.name, color = Muted, style = MaterialTheme.typography.labelLarge)
        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = DeepTeal), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("지금 계량기는 아마", color = Color(0xFFC1E1D9), style = MaterialTheme.typography.titleMedium)
                if (estimate.reading != null) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(decimalText(estimate.reading), Modifier.weight(1f, false), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 44.sp)
                        Text("m³", color = Color(0xFFC1E1D9), modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Text("누적 지침 추정 · 실제 검침값이 아니에요", color = Color(0xFFC1E1D9), style = MaterialTheme.typography.bodySmall)
                } else Text(if (estimate.ageDays == null) "숫자를 한 번\n알려주세요" else "추정에 필요한\n이력을 모아요", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = Color(0xFF32636A))
                Text(estimate.source, color = Color(0xFFC1E1D9), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DeepTeal), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.FactCheck, null); Spacer(Modifier.width(8.dp)); Text("계량기 보고 확인하기", fontWeight = FontWeight.Bold)
                }
            }
        }
        SurfaceCard {
            Text("이번 검침 기간", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (latest != null) Text("${latest.last.plusDays(1)}부터 오늘까지", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("사용량 추정", color = Muted, style = MaterialTheme.typography.labelLarge)
                    Text(if (usage == null) "확인 필요" else "${decimalText(usage)} m³", fontWeight = FontWeight.Bold, fontSize = 23.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("하루 사용량 추정", color = Muted, style = MaterialTheme.typography.labelLarge)
                    Text(if (estimate.daily == null) "이력 필요" else "${decimalText(estimate.daily, 2)} m³", fontWeight = FontWeight.Bold, fontSize = 23.sp)
                }
            }
            if (cost != null) {
                HorizontalDivider(color = Pale)
                Text("현재까지 참고 요금 약 ${decimalText(cost, 0)}원", fontWeight = FontWeight.Medium)
                Text("최근 청구서의 단가·기본료·부가세를 적용한 참고값이에요. 요금 변경, 할인, 정산에 따라 실제 청구액과 달라져요.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else if (latest == null) Text("기간별 지침이 있는 청구 이력을 연결하면 이번 기간 사용량도 보여드려요.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val planned = data.profile.plannedDate?.let(LocalDate::parse)?.takeIf { it > today() && it <= today().plusDays(45) }
        if (planned != null) {
            val future = Estimator.estimate(data, dayStart(planned), now)
            SurfaceCard {
                Text("다음 검침일 ${planned.monthValue}월 ${planned.dayOfMonth}일", fontWeight = FontWeight.Bold)
                Text(if (future.reading != null) "예상 누적 지침 ${decimalText(future.reading)} m³" else "현재 정보로 다음 지침을 추정하기 어려워요.", color = Muted)
            }
        }
        Hint(Icons.Outlined.EventAvailable, when {
            estimate.ageDays == null -> "계량기의 누적 숫자를 입력해 주세요. 작년 이력도 있으면 바로 추정할 수 있어요."
            estimate.ageDays >= 7 -> "최근 확인 기준 ${estimate.ageDays}일이 지났어요. 이번 주 숫자를 확인해 주세요."
            else -> "최근 확인 기준 ${estimate.ageDays}일 전. 일주일에 한 번 실제 숫자를 알려주세요."
        })
        TextButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("사용 흐름과 지난 기록 보기"); Icon(Icons.Outlined.ChevronRight, null) }
        if (data.profile.syncTime != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("최근 조회 ${dateOf(data.profile.syncTime)}", style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Text("새로고침") }
        }
    }
}

@Composable private fun SubmissionPage(data: AppData, target: SelfReadTarget?, now: Long, busy: Boolean,
    refresh: () -> Unit, submit: (Double) -> Unit,
    changeSettings: (Boolean, Boolean, Boolean, Int) -> Unit) {
    val settings = data.submissionSettings
    val decision = SubmissionPolicy.decide(data, target, now, automatic = false)
    val provider = Providers.get(data.profile.providerId)
    val demo = data.profile.meter == "demo"
    val demoDate = dateOf(now)
    val demoValue = Estimator.estimate(data, now).reading?.let { kotlin.math.round(it * 10.0) / 10.0 }
    Page {
        Title("자가검침 제출", "기간과 숫자를 확인해 직접 제출하거나, 조건을 정해 마지막 날 자동으로 제출해요.")
        if (demo) Badge("예시 데이터 · 실제로 제출되지 않아요")
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("공급사 제출 기능", fontWeight = FontWeight.Bold)
                    Text("확인한 누적 지침을 실제 공급사 기록에 입력해요", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.enabled, onCheckedChange = { changeSettings(it, settings.automatic, settings.requireRecentCheck, settings.recentDays) })
            }
            if (settings.enabled) {
                HorizontalDivider()
                if (provider.automaticSubmission) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("검침 기간 마지막 날 자동 제출", fontWeight = FontWeight.Medium)
                            Text("당일 최신 상태를 다시 확인한 뒤 한 번만 전송해요", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = settings.automatic, onCheckedChange = { changeSettings(true, it, settings.requireRecentCheck, settings.recentDays) })
                    }
                } else Text("이 공급사는 사용자가 값을 확인하는 직접 제출만 지원해요.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("최근 실측이 있을 때만", fontWeight = FontWeight.Medium)
                        Text("추정값이 오래된 실측에 기대지 않도록 제한해요", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.requireRecentCheck, onCheckedChange = { changeSettings(true, settings.automatic, it, settings.recentDays) })
                }
                if (settings.requireRecentCheck) Choice("허용 기간", "${settings.recentDays}일 이내", (1..30).map { "${it}일 이내" }) {
                    changeSettings(true, settings.automatic, true, it.substringBefore("일").toInt())
                }
                if (settings.automatic && data.credentials == null && !demo) Text("자동 제출을 사용하려면 설정에서 로그인 정보를 암호화해 저장해야 해요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        SurfaceCard {
            Text("이번 제출 상태", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (demo) {
                val month = YearMonth.from(demoDate)
                Text("${month.atDay(20)} ~ ${month.atDay(25)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Text(demoValue?.let { "입력 예정 ${decimalText(it)} m³" } ?: "입력할 숫자를 계산하는 중", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("최근 실측 7일 전 · 기존 제출 없음", color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton(demoValue?.let { "${decimalText(it)} m³ 직접 제출" } ?: "직접 제출", Icons.Outlined.CloudUpload, false) {}
            } else if (target == null) {
                Text("공급사에서 검침 기간을 확인해 주세요.", color = Muted)
            } else {
                Text("${target.start} ~ ${target.end}", color = Muted, style = MaterialTheme.typography.bodySmall)
                when {
                    target.submitted -> Text(target.submittedValue?.let { "입력 완료 · ${decimalText(it)} m³" } ?: "입력 완료", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Teal)
                    decision.value != null -> Text("입력 예정 ${decimalText(decision.value)} m³", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    else -> Text("아직 입력할 수 없어요", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (!demo) {
                Text(decision.reason, color = Muted, style = MaterialTheme.typography.bodySmall)
                ActionButton("검침 기간과 제출 상태 새로 확인", Icons.Outlined.Refresh, !busy, refresh)
                if (decision.allowed && decision.value != null) ActionButton("${decimalText(decision.value)} m³ 직접 제출", Icons.Outlined.CloudUpload, !busy) { submit(decision.value) }
            }
        }
        Hint(Icons.Outlined.Security, "자동 제출은 마지막 날에만 실행됩니다. 공급사 상태, 이전 검침값, 최근 실측 시점과 중복 전송 기록을 확인하고 조건이 하나라도 맞지 않으면 보내지 않아요.")
        if (data.submissions.isNotEmpty()) Text("최근 입력 결과", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        data.submissions.sortedByDescending { it.attemptedAt }.take(5).forEach { record ->
            SurfaceCard {
                Text("${record.periodStart} ~ ${record.periodEnd}", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text("${decimalText(record.value)} m³", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(record.detail, color = if (record.status == "confirmed") Teal else Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun HistoryPage(data: AppData, add: () -> Unit, delete: (UsagePeriod) -> Unit, deleteObservation: (Observation) -> Unit) {
    val months = remember(data.periods) { HistorySummary.months(data.periods, YearMonth.from(today())) }
    var selectedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = months.find { it.month.toString() == selectedMonth }
    val chartScroll = rememberScrollState(Int.MAX_VALUE)
    LaunchedEffect(chartScroll.maxValue) { chartScroll.scrollTo(chartScroll.maxValue) }
    Page {
        Title("우리 집 사용 흐름", "청구서에 표시된 실제 사용량을 모아요.")
        SurfaceCard {
            Text("최근 24개월", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val max = months.mapNotNull { it.usage }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            Row(Modifier.fillMaxWidth().height(156.dp).horizontalScroll(chartScroll), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                months.forEach { point ->
                    val chosen = selectedMonth == point.month.toString()
                    val description = buildString {
                        append("${point.month.year}년 ${point.month.monthValue}월, ")
                        append(point.usage?.let { "사용량 ${decimalText(it)} 세제곱미터" } ?: "사용 이력 없음")
                        point.billedAmount?.let { append(", 청구월 합계 ${decimalText(it, 0)}원") }
                    }
                    Column(Modifier.width(30.dp).clickable { selectedMonth = point.month.toString() }
                        .semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.width(24.dp).height(((point.usage ?: 0.0) / max * 112).dp.coerceAtLeast(3.dp))
                            .background(if (point.usage == null) Color(0xFFDCE2DC) else if (chosen) Coral else Teal))
                        Text(if (point.month.monthValue == 1) "${point.month.year}\n1" else "${point.month.monthValue}", fontSize = 9.sp, lineHeight = 10.sp, color = if (chosen) Ink else Muted)
                    }
                }
            }
            if (selected != null) {
                HorizontalDivider()
                Text("${selected.month.year}년 ${selected.month.monthValue}월", fontWeight = FontWeight.Bold)
                Text(selected.usage?.let { "사용량 ${decimalText(it)} m³" } ?: "사용량 이력 없음", color = if (selected.usage == null) Muted else Ink)
                Text(selected.billedAmount?.let { "가스비 ${decimalText(it, 0)}원 · 해당 청구월의 실제 합계" } ?: "가스비 정보 없음", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else Text("막대를 누르면 월별 사용량과 가스비를 볼 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Muted)
            Text("기간별 사용량을 날짜에 나눠 월별로 환산했어요. 회색은 이력이 없는 달이며 가스비는 정확히 일치하는 청구월 합계만 표시해요.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        ActionButton("과거 사용량 추가", Icons.Outlined.Add, onClick = add)
        Hint(Icons.Outlined.Lightbulb, "작년 같은 달과 앞뒤 달의 이력이 있으면 좋아요. 청구월보다 실제 사용 기간을 정확히 입력해 주세요.")
        if (data.periods.isEmpty()) EmptyNote("아직 사용 이력이 없어요", "공급사 홈페이지나 청구서에서 과거 사용량을 확인해 입력해 주세요.")
        data.periods.sortedByDescending { it.end }.forEachIndexed { index, period ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${period.start} ~ ${period.end}", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${decimalText(period.usage)} m³", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (period.billMonth.isNotBlank()) Text("${period.billMonth.take(4)}.${period.billMonth.takeLast(2)} 청구", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { delete(period) }) { Icon(Icons.Outlined.DeleteOutline, "이 사용 이력 삭제") }
                }
                if (period.previous != null && period.current != null) Text("누적 지침 ${decimalText(period.previous)} → ${decimalText(period.current)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (period.amount != null) Text("청구 합계 ${decimalText(period.amount, 0)}원", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (index < data.periods.lastIndex) HorizontalDivider()
        }
        Text("실측 기록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (data.observations.isEmpty()) EmptyNote("아직 실측 기록이 없어요", "검침 화면에서 계량기 숫자를 확인해 주세요.")
        data.observations.sortedByDescending { it.time }.take(40).forEach { observation ->
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dateOf(observation.time).toString(), style = MaterialTheme.typography.labelLarge, color = Muted)
                        Text("${decimalText(observation.reading)} m³", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { deleteObservation(observation) }) { Icon(Icons.Outlined.DeleteOutline, "이 실측 기록 삭제") }
                }
                observation.predicted?.let { predicted -> Text("확인 전 추정과 ${decimalText(observation.reading - predicted)} m³ 차이", style = MaterialTheme.typography.bodySmall, color = Muted) }
                if (observation.meter != data.profile.meter) Badge("이전 계량기")
            }
        }
    }
}

@Composable private fun SettingsPage(data: AppData, enable: () -> Unit, disable: () -> Unit, setTime: (Int, Int) -> Unit,
    login: () -> Unit, forget: () -> Unit, export: () -> Unit, restore: () -> Unit, meter: () -> Unit, erase: () -> Unit, update: () -> Unit, licenses: () -> Unit, open: (String) -> Unit, busy: Boolean) {
    val provider = Providers.get(data.profile.providerId)
    Page {
        Title("내 방식대로", "연결과 기록은 내가 관리해요.")
        SettingsSection("알림") {
            SettingToggle("매주 실측 확인 알림", "계량기 숫자를 확인할 시간을 알려드려요", Icons.Outlined.Notifications,
                data.profile.reminder) { if (it) enable() else disable() }
            val days = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
            SettingChoice("요일", days[data.profile.reminderDay - 1], Icons.Outlined.CalendarToday, days) { setTime(days.indexOf(it) + 1, data.profile.reminderHour) }
            SettingChoice("시간", "${data.profile.reminderHour}시", Icons.Outlined.Schedule, (0..23).map { "${it}시" }) { setTime(data.profile.reminderDay, it.removeSuffix("시").toInt()) }
            Text("한국 시간 기준이며 기기 상태에 따라 알림이 늦어질 수 있어요.", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection("공급사") {
            SettingInfo(provider.name, if (data.credentials == null) "저장된 로그인 정보 없음" else "로그인 정보가 이 기기에 암호화되어 있어요", Icons.Outlined.Apartment)
            if (provider.skens && data.profile.meter != "demo") SettingAction("다시 연결", Icons.Outlined.Login, login, "${provider.name} 계정과 계약을 다시 확인해요", !busy)
            SettingAction("공급사 홈페이지", Icons.Outlined.OpenInNew, { open(provider.website) }, provider.name)
            if (data.credentials != null) SettingAction("로그인 정보 삭제", Icons.Outlined.NoAccounts, forget, "사용 기록은 그대로 유지해요", !busy)
        }
        SettingsSection("내 기록") {
            SettingAction("기록 내보내기", Icons.Outlined.FileUpload, export, "로그인 정보를 제외한 백업 파일을 만들어요")
            SettingAction("백업 가져오기", Icons.Outlined.FileDownload, restore, "현재 기록을 선택한 백업으로 바꿔요")
            SettingAction("새 계량기로 시작", Icons.Outlined.RestartAlt, meter, "이전 실측은 보관해요")
            SettingAction("모든 데이터 삭제", Icons.Outlined.DeleteOutline, erase, "로그인 정보와 기록을 모두 지워요", contentColor = MaterialTheme.colorScheme.error)
        }
        SettingsSection("도움말과 앱 정보") {
            SettingAction("업데이트 확인", Icons.Outlined.SystemUpdate, update, "Google Play에서 최신 버전을 확인해요")
            SettingAction("테스터 그룹", Icons.Outlined.Groups, { open(AppLinks.TESTER_GROUP) }, "테스트 공지와 참여 계정을 관리해요")
            SettingAction("개인정보 처리방침", Icons.Outlined.PrivacyTip, { open(AppLinks.PRIVACY) })
            SettingAction("소스 코드", Icons.Outlined.Code, { open(AppLinks.SOURCE) }, "지원 범위와 개발 내용을 확인해요")
            SettingAction("오류 신고", Icons.Outlined.BugReport, { open(AppLinks.ISSUES) })
            SettingAction("오픈소스 라이선스", Icons.Outlined.Policy, licenses)
        }
        Hint(Icons.Outlined.PrivacyTip, "계정 로그인, 청구 조회와 선택한 검침값 제출만 공급사에 직접 전송해요. 계산은 기기 안에서 이루어지며 광고와 사용자 추적 기능은 없어요.")
        Text("추정은 작년 계절 흐름과 최근 실측을 함께 사용해요. 외부 AI에 계정이나 생활 데이터를 보내지 않으며 정확도 범위를 보장하지 않아요.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("똑똑 자가검침 AI  ${BuildConfig.VERSION_NAME}\n독립적으로 만든 비공식 앱이에요.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Teal, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(content = content)
    }
}
@Composable private fun SettingInfo(text: String, supportingText: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, fontWeight = FontWeight.SemiBold)
            Text(supportingText, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun SettingToggle(text: String, supportingText: String, icon: ImageVector, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, fontWeight = FontWeight.SemiBold)
            Text(supportingText, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = change)
    }
}
@Composable private fun SettingChoice(label: String, value: String, icon: ImageVector, values: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = Muted)
            Spacer(Modifier.width(16.dp))
            Text(label, Modifier.weight(1f), color = Ink)
            Text(value, color = Teal, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
            values.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { change(item); expanded = false }) }
        }
    }
}
@Composable private fun SettingAction(text: String, icon: ImageVector, action: () -> Unit, supportingText: String? = null,
    enabled: Boolean = true, contentColor: Color = MaterialTheme.colorScheme.primary) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor)) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, color = if (enabled) contentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            if (supportingText != null) Text(supportingText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
    }
}
@Composable private fun EmptyNote(title: String, text: String) {
    SurfaceCard { Icon(Icons.Outlined.Eco, null, tint = Teal, modifier = Modifier.size(36.dp)); Text(title, fontWeight = FontWeight.Bold); Text(text, color = Muted) }
}
@Composable private fun Choice(label: String, value: String, values: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Muted)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp)) {
                Text(value, Modifier.weight(1f)); Icon(Icons.Outlined.ExpandMore, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                values.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { change(item); expanded = false }) }
            }
        }
    }
}

@Composable private fun CalibrationDialog(initial: String, estimate: Estimate, data: AppData, close: () -> Unit, save: (String) -> Unit) {
    var value by remember { mutableStateOf(initial.replace(",", "")) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("계량기를 보고 확인했나요?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("지금 계량기의 누적 숫자와 맞춰 주세요. 확인한 값은 오늘의 실제 기록으로 저장해요.")
            OutlinedTextField(value, { value = it; error = null }, label = { Text("실제 계량기 숫자") }, suffix = { Text("m³") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { runCatching { value = decimalText((number(value) - .1).coerceAtLeast(0.0)).replace(",", "") }.onFailure { error = "먼저 숫자를 입력해 주세요." } }, modifier = Modifier.weight(1f)) { Text("−0.1") }
                OutlinedButton(onClick = { runCatching { value = decimalText(number(value) + .1).replace(",", "") }.onFailure { error = "먼저 숫자를 입력해 주세요." } }, modifier = Modifier.weight(1f)) { Text("+0.1") }
            }
            if (estimate.reading != null) Text("확인 전 추정 ${decimalText(estimate.reading)} m³", color = Muted, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("여기서는 실측값만 저장해요. 공급사 입력은 제출 탭에서 값과 기간을 다시 확인한 뒤 진행합니다.", style = MaterialTheme.typography.bodySmall, color = Muted)
        } },
        confirmButton = { TextButton(onClick = { try { Estimator.addObservation(data, number(value)); save(value) } catch (e: Exception) { error = readableError(e) } }) { Text("이 숫자로 확인") } },
        dismissButton = { TextButton(onClick = close) { Text("나중에") } })
}

@Composable private fun HistoryDialog(close: () -> Unit, save: (String, String, String) -> Unit) {
    val month = YearMonth.from(today()).minusYears(1)
    var start by remember { mutableStateOf(month.atDay(1).toString()) }
    var end by remember { mutableStateOf(month.atEndOfMonth().toString()) }
    var usage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("과거 사용량 추가") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("청구서의 사용 기간과 보정 전 사용량(m³)을 입력해 주세요. 지침이 있다면 당월 지침에서 전월 지침을 빼면 돼요.")
            OutlinedTextField(start, { start = it }, label = { Text("사용 시작일 YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(end, { end = it }, label = { Text("사용 종료일 YYYY-MM-DD") }, singleLine = true)
            OutlinedTextField(usage, { usage = it }, label = { Text("기간 사용량") }, suffix = { Text("m³") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("달 전체 사용량만 알고 있다면 해당 월 1일부터 마지막 날까지 입력해 주세요.", color = Muted, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { try {
            UsagePeriod(LocalDate.parse(start).toString(), LocalDate.parse(end).toString(), number(usage)).validate(); save(start, end, usage)
        } catch (_: Exception) { error = "날짜와 사용량을 확인해 주세요. 종료일은 오늘 이후일 수 없어요." } }) { Text("저장") } },
        dismissButton = { TextButton(onClick = close) { Text("취소") } })
}

@Composable private fun LoginDialog(provider: Provider, busy: Boolean, progress: String, progressCurrent: Int, progressTotal: Int, contracts: List<Contract>, error: String?, close: () -> Unit,
    login: (String, String, Boolean) -> Unit, choose: (Contract) -> Unit, open: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (contracts.size > 1) "사용 계약을 선택해 주세요" else "${provider.name} 연결") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (contracts.size > 1) contracts.forEach { contract -> OutlinedButton(onClick = { choose(contract) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(contract.label) } }
            else {
                Text("${provider.name} 홈페이지 계정으로 로그인해요. 기기에서 공급사로 직접 연결해 청구 이력과 검침 기간을 확인하고, 동의한 경우 검침값도 입력할 수 있어요.")
                OutlinedTextField(username, { username = it }, label = { Text("아이디") }, singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, singleLine = true, enabled = !busy,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "비밀번호 숨기기" else "비밀번호 보기") } })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberPassword, { rememberPassword = it }, enabled = !busy); Text("이 기기에 암호화해서 저장", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { open(provider.accountRecovery) }, enabled = !busy) { Text("아이디·비밀번호가 기억나지 않아요") }
                TextButton(onClick = { open(provider.registration) }, enabled = !busy) { Text("${provider.name} 회원가입") }
                if (busy) {
                    if (progressTotal > 0) LinearProgressIndicator(progress = { (progressCurrent.toFloat() / progressTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress.ifBlank { "공급사 정보를 확인하는 중" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (!busy && error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        } }, confirmButton = { if (contracts.size <= 1) TextButton(onClick = { login(username, password, rememberPassword) }, enabled = !busy && username.isNotBlank() && password.isNotBlank()) { Text("로그인하고 조회") } },
        dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("취소") } })
}
