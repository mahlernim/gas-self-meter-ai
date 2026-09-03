package dev.mahlernim.gasselfmeter

import android.Manifest
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
    var login by remember { mutableStateOf(false) }
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
                Welcome(vm.busy, { vm.manual(it) }, { login = true }, { vm.loadDemo() }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, ::open)
            } else when (tab) {
                0 -> Dashboard(data, estimate, now, { calibration = decimalText(estimate.reading).takeIf { estimate.reading != null } ?: "" }, { tab = 2 }, { vm.refresh() }, vm.busy)
                1 -> SubmissionPage(data, vm.selfReadTarget, now, vm.busy, vm::checkSubmissionStatus, { value -> submitValue = value }, vm::setSubmissionSettings)
                2 -> HistoryPage(data, { addHistory = true }, { period -> confirmation = "period:${data.periods.indexOf(period)}" }, { observation -> confirmation = "observation:${observation.time}" })
                3 -> SettingsPage(data, {
                    if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.setReminder(true, data.profile.reminderDay, data.profile.reminderHour)
                }, { vm.setReminder(false, data.profile.reminderDay, data.profile.reminderHour) },
                    { day, hour -> vm.setReminder(data.profile.reminder, day, hour) },
                    { login = true }, { vm.forgetCredentials() },
                    { export.launch("gas-self-meter-${today()}.json") }, { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    { confirmation = "meter" }, { confirmation = "erase" }, { licenses = true }, ::open, vm.busy)
            }
        }
    }
    calibration?.let { initial -> CalibrationDialog(initial, estimate, data, { calibration = null }) { value ->
        if (vm.calibrate(value)) calibration = null
    } }
    submitValue?.let { value ->
        AlertDialog(onDismissRequest = { submitValue = null }, title = { Text("검침값을 공급사에 입력할까요?") },
            text = { Text("부산도시가스에 ${decimalText(value)} m³를 입력합니다. 전송 직전에 기간과 기존 제출 여부를 다시 확인하며, 결과가 불확실하면 자동으로 다시 보내지 않습니다.") },
            confirmButton = { TextButton(onClick = { vm.submitReading(value); submitValue = null }) { Text("${decimalText(value)} m³ 입력") } },
            dismissButton = { TextButton(onClick = { submitValue = null }) { Text("취소") } })
    }
    if (addHistory) HistoryDialog({ addHistory = false }) { start, end, value ->
        vm.addPeriod(start, end, value)
        if (vm.message == "사용 이력을 저장했어요.") addHistory = false
    }
    if (login) LoginDialog(vm.busy, vm.progress, vm.progressCurrent, vm.progressTotal, vm.contracts, vm.loginError, { if (!vm.busy) { login = false; vm.cancelLogin() } },
        { u, p, remember -> vm.login(u, p, remember) }, { vm.selectContract(it) }, ::open)
    LaunchedEffect(data.profile.syncTime) { if (data.profile.syncTime != null && vm.contracts.isEmpty()) login = false }
    LaunchedEffect(vm.busy) { if (!vm.busy && data.ready && data.profile.syncTime != null && vm.contracts.isEmpty() && vm.message?.contains("개월") == true) login = false }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}
@Composable private fun Title(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Ink)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable private fun ActionButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
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

@Composable private fun Welcome(busy: Boolean, onManual: (String) -> Unit, onLogin: () -> Unit, onDemo: () -> Unit, onImport: () -> Unit, open: (String) -> Unit) {
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
                Hint(Icons.Outlined.CloudDownload, "부산도시가스 계정으로 청구 이력을 가져올 수 있어요.")
                ActionButton("부산도시가스 연결하기", Icons.Outlined.Login, !busy, onLogin)
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
    Page {
        Title("검침값 입력", "검침 기간을 확인하고 앱이 계산한 누적 지침을 부산도시가스에 입력해요.")
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("공급사 검침값 입력 사용", fontWeight = FontWeight.Bold)
                    Text("실제 공급사 기록을 변경하는 기능이에요", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.enabled, onCheckedChange = { changeSettings(it, settings.automatic, settings.requireRecentCheck, settings.recentDays) })
            }
            if (settings.enabled) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("검침 기간 마지막 날 자동 입력", fontWeight = FontWeight.Medium)
                        Text("당일 최신 상태를 다시 확인한 뒤 한 번만 전송해요", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.automatic, onCheckedChange = { changeSettings(true, it, settings.requireRecentCheck, settings.recentDays) })
                }
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
                if (settings.automatic && data.credentials == null) Text("자동 입력을 사용하려면 설정에서 로그인 정보를 암호화해 저장해야 해요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        SurfaceCard {
            Text("이번 제출 상태", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (target == null) {
                Text("공급사에서 검침 기간을 확인해 주세요.", color = Muted)
            } else {
                Text("${target.start} ~ ${target.end}", color = Muted, style = MaterialTheme.typography.bodySmall)
                when {
                    target.submitted -> Text(target.submittedValue?.let { "입력 완료 · ${decimalText(it)} m³" } ?: "입력 완료", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Teal)
                    decision.value != null -> Text("입력 예정 ${decimalText(decision.value)} m³", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    else -> Text("아직 입력할 수 없어요", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(decision.reason, color = Muted, style = MaterialTheme.typography.bodySmall)
            ActionButton("검침 기간과 제출 상태 새로 확인", Icons.Outlined.Refresh, !busy, refresh)
            if (decision.allowed && decision.value != null) ActionButton("${decimalText(decision.value)} m³ 직접 입력", Icons.Outlined.CloudUpload, !busy) { submit(decision.value) }
        }
        Hint(Icons.Outlined.Security, "자동 입력은 마지막 날에만 실행됩니다. 공급사 상태, 이전 검침값, 최근 실측 시점과 중복 전송 기록을 확인하고 조건이 하나라도 맞지 않으면 보내지 않아요.")
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
    Page {
        Title("우리 집 사용 흐름", "청구서에 표시된 실제 사용량을 모아요.")
        SurfaceCard {
            Text("최근 12개월", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val months = (1L..12L).map { YearMonth.from(today()).minusMonths(it) }.reversed()
            val values = months.map { m -> Estimator.monthlyRate(data.periods, m)?.times(m.lengthOfMonth()) }
            val max = values.filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
                months.forEachIndexed { i, month ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.fillMaxWidth().height(((values[i] ?: 0.0) / max * 113).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(if (values[i] == null) Color(0xFFDCE2DC) else if (i == 11) Coral else Teal)
                            .semantics { contentDescription = "${month} ${values[i]?.let { decimalText(it) + " 세제곱미터" } ?: "이력 없음"}" })
                        Text("${month.monthValue}", fontSize = 10.sp, color = Muted)
                    }
                }
            }
            Text("m³ · 기간별 사용량을 날짜에 나눠 월별로 환산했어요. 회색은 이력이 없는 달이에요.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        ActionButton("과거 사용량 추가", Icons.Outlined.Add, onClick = add)
        Hint(Icons.Outlined.Lightbulb, "작년 같은 달과 앞뒤 달의 이력이 있으면 좋아요. 청구월보다 실제 사용 기간을 정확히 입력해 주세요.")
        if (data.periods.isEmpty()) EmptyNote("아직 사용 이력이 없어요", "공급사 홈페이지나 청구서에서 과거 사용량을 확인해 입력해 주세요.")
        data.periods.sortedByDescending { it.end }.forEach { period ->
            SurfaceCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${period.start} ~ ${period.end}", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text("${decimalText(period.usage)} m³", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        if (period.billMonth.isNotBlank()) Text("${period.billMonth.take(4)}년 ${period.billMonth.takeLast(2)}월 청구", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { delete(period) }) { Icon(Icons.Outlined.DeleteOutline, "이 사용 이력 삭제") }
                }
                if (period.previous != null && period.current != null) Text("누적 지침 ${decimalText(period.previous)} → ${decimalText(period.current)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                if (period.amount != null) Text("청구 합계 ${decimalText(period.amount, 0)}원 · 같은 청구월의 합계", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
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
    login: () -> Unit, forget: () -> Unit, export: () -> Unit, restore: () -> Unit, meter: () -> Unit, erase: () -> Unit, licenses: () -> Unit, open: (String) -> Unit, busy: Boolean) {
    Page {
        Title("내 방식대로", "연결과 기록은 내가 관리해요.")
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("매주 계량기 실측 확인 알림", fontWeight = FontWeight.Bold); Text("계량기 숫자를 확인하고 입력할 시간을 알려드려요", color = Muted, style = MaterialTheme.typography.bodySmall) }
                Switch(checked = data.profile.reminder, onCheckedChange = { if (it) enable() else disable() })
            }
            val days = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")
            Choice("요일", days[data.profile.reminderDay - 1], days) { setTime(days.indexOf(it) + 1, data.profile.reminderHour) }
            Choice("시간", "${data.profile.reminderHour}시", (0..23).map { "${it}시" }) { setTime(data.profile.reminderDay, it.removeSuffix("시").toInt()) }
            Text("한국 시간 기준이에요. 배터리 절약이나 기기 상태에 따라 알림이 늦어질 수 있어요.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        SurfaceCard {
            Text("공급사 연결", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(Providers.get(data.profile.providerId).name)
            Text(if (data.credentials == null) "저장된 로그인 정보 없음" else "로그인 정보가 이 기기에 암호화되어 있어요", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (data.profile.providerId == "busan" && data.profile.meter != "demo") ActionButton("부산도시가스 다시 연결", Icons.Outlined.Login, !busy, login)
            TextButton(onClick = { open(Providers.get(data.profile.providerId).website) }) { Text("공급사 홈페이지 열기") }
            if (data.credentials != null) TextButton(onClick = forget, enabled = !busy) { Text("로그인 정보만 삭제") }
        }
        SurfaceCard {
            Text("내 기록", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            SettingAction("기록 내보내기", Icons.Outlined.FileUpload, export)
            Text("백업에는 사용 이력과 확인 기록이 들어가요. 로그인 정보는 제외돼요. 내보낸 파일은 직접 안전하게 보관해 주세요.", color = Muted, style = MaterialTheme.typography.bodySmall)
            SettingAction("백업 가져오기", Icons.Outlined.FileDownload, restore)
            SettingAction("계량기를 교체했어요", Icons.Outlined.RestartAlt, meter)
            SettingAction("모든 데이터 삭제 / 새로 시작", Icons.Outlined.DeleteOutline, erase)
        }
        SurfaceCard {
            Text("추정은 이렇게 해요", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("작년 같은 달의 일평균 사용량과 앞뒤 달의 흐름을 연결해요. 최근 28일 안에 실제로 확인한 두 숫자가 있으면 사용 속도를 보정해요. 확인 후 시간이 지날수록 계절 흐름의 비중이 커져요.", color = Muted)
            Text("작년 이력이 없으면 최근 실측 두 번으로 최대 14일 동안 추정해요. 난방 방식이나 거주 인원이 달라지면 차이가 커질 수 있어요. 정확도 범위를 보장하지 않아요.", color = Muted)
            Text("‘AI’는 기기 안에서 계산하는 적응형 추정 모델을 뜻해요. 외부 AI에 계정이나 생활 데이터를 보내지 않아요.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Hint(Icons.Outlined.PrivacyTip, "계정 로그인, 청구 조회와 선택한 검침값 입력만 공급사에 직접 전송해요. 계산은 기기 안에서 이루어지며 광고와 사용자 추적 기능은 없어요.")
        TextButton(onClick = { open("https://github.com/mahlernim/gas-self-meter-ai") }) { Text("GitHub · 지원 범위와 개인정보 안내") }
        TextButton(onClick = { open("https://groups.google.com/g/gas-self-meter-ai") }) { Text("테스터 그룹") }
        TextButton(onClick = { open("https://github.com/mahlernim/gas-self-meter-ai/issues") }) { Text("오류 신고") }
        TextButton(onClick = licenses) { Text("오픈소스 라이선스") }
        Text("똑똑 자가검침 AI  ${BuildConfig.VERSION_NAME}\n독립적으로 만든 비공식 앱이에요.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingAction(text: String, icon: ImageVector, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text(text, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null)
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

@Composable private fun LoginDialog(busy: Boolean, progress: String, progressCurrent: Int, progressTotal: Int, contracts: List<Contract>, error: String?, close: () -> Unit,
    login: (String, String, Boolean) -> Unit, choose: (Contract) -> Unit, open: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(if (contracts.size > 1) "사용 계약을 선택해 주세요" else "부산도시가스 연결") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (contracts.size > 1) contracts.forEach { contract -> OutlinedButton(onClick = { choose(contract) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(contract.label) } }
            else {
                Text("부산도시가스 홈페이지 계정으로 로그인해요. 기기에서 공급사로 직접 연결해 청구 이력과 검침 기간을 확인하고, 동의한 경우 검침값도 입력할 수 있어요.")
                OutlinedTextField(username, { username = it }, label = { Text("아이디") }, singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, singleLine = true, enabled = !busy,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "비밀번호 숨기기" else "비밀번호 보기") } })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberPassword, { rememberPassword = it }, enabled = !busy); Text("이 기기에 암호화해서 저장", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { open("https://www.skens.com/busan/login/find.do") }, enabled = !busy) { Text("아이디·비밀번호가 기억나지 않아요") }
                TextButton(onClick = { open("https://www.skens.com/busan/join/type.do") }, enabled = !busy) { Text("부산도시가스 회원가입") }
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
