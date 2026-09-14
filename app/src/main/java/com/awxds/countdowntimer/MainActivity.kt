package com.awxds.countdowntimer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme(primary = Color(0xFFA3D1BA))
              else lightColorScheme(primary = Color(0xFF386A53))
            MaterialTheme(colorScheme = scheme) { TimerScreen(timer, resumed.intValue) }
        }
    }
    override fun onResume() {
        super.onResume()
        timer.reconcile()
        resumed.intValue++
    }
}

@Composable
private fun TimerScreen(controller: TimerController, resumeVersion: Int) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var hours by rememberSaveable { mutableStateOf((state.durationMs / 3_600_000).toString()) }
    var minutes by rememberSaveable { mutableStateOf((state.durationMs / 60_000 % 60).toString()) }
    var seconds by rememberSaveable { mutableStateOf((state.durationMs / 1000 % 60).toString()) }
    var mode by rememberSaveable { mutableStateOf(state.mode) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionVersion++
    }
    val notificationsAllowed = remember(resumeVersion, permissionVersion) { controller.notifications.allowed() }
    val channelsEnabled = remember(resumeVersion, permissionVersion) { controller.notifications.channelsEnabled() }
    val exactAllowed = remember(resumeVersion, permissionVersion) { controller.canSchedule() }
    LaunchedEffect(notificationsAllowed, exactAllowed) { error = null }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("ui", 0)
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed && !prefs.getBoolean("notificationAsked", false)) {
            prefs.edit().putBoolean("notificationAsked", true).apply()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(state.phase, state.generation) {
        while (state.phase == Phase.RUNNING) {
            now = SystemClock.elapsedRealtime()
            if (state.remaining(now) == 0L) controller.finish(state.generation)
            delay(200)
        }
        now = SystemClock.elapsedRealtime()
    }
    val editable = state.phase == Phase.IDLE || state.phase == Phase.FINISHED
    val inputDuration = ((hours.toLongOrNull() ?: 0) * 3600 + (minutes.toLongOrNull() ?: 0) * 60 + (seconds.toLongOrNull() ?: 0)) * 1000
    val displayed = if (state.phase == Phase.IDLE) inputDuration else state.remaining(now)
    val progress = if (state.phase == Phase.IDLE) 1f else (displayed.toFloat() / state.durationMs).coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    fun notificationSettings() {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    fun requireAccess(): Boolean {
        if (!controller.canSchedule()) { error = "请先开启精确闹钟权限，再开始计时。"; return false }
        if (!controller.notifications.allowed()) { error = "请先允许通知，以便在后台查看倒计时和停止提醒。"; return false }
        return true
    }
    Scaffold(bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(onClick = {
                    error = null
                    when (state.phase) {
                        Phase.RUNNING -> controller.pause()
                        Phase.PAUSED -> if (requireAccess() && !controller.resume()) error = "无法恢复，请检查精确闹钟权限。"
                        else -> if (inputDuration !in 1000..TimerState.MAX_DURATION) error = "请设置至少 1 秒的倒计时。"
                            else if (requireAccess() && !controller.start(inputDuration, mode)) error = "无法开始，请检查精确闹钟权限。"
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(when (state.phase) { Phase.RUNNING -> "暂停"; Phase.PAUSED -> "继续"; Phase.IDLE -> "开始计时"; Phase.FINISHED -> "再次开始" }, fontSize = 18.sp)
                }
                if (!editable || state.phase == Phase.FINISHED) {
                    OutlinedButton(onClick = {
                        hours = (state.durationMs / 3_600_000).toString()
                        minutes = (state.durationMs / 60_000 % 60).toString()
                        seconds = (state.durationMs / 1000 % 60).toString()
                        controller.reset()
                    }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp)) { Text("重置") }
                }
                if (state.ringing) TextButton(onClick = { controller.stopRinging(dismiss = true) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("停止响铃") }
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("计时器", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("为眼前的事，留一段时间", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { notificationSettings() }) { Text("通知设置") }
                    UpdateButton(resumeVersion, state)
                }
            }
            Spacer(Modifier.height(24.dp))
            if (!notificationsAllowed || !channelsEnabled || !exactAllowed) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (!exactAllowed) "允许精确提醒" else "开启计时器通知", fontWeight = FontWeight.SemiBold)
                        Text(if (!exactAllowed) "需要精确闹钟权限，才能在熄屏后按时提醒。" else "通知用于显示剩余时间和结束提醒，请允许通知及对应通知类别。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            if (!controller.canSchedule() && Build.VERSION.SDK_INT >= 31)
                                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                            else notificationSettings()
                        }) { Text("前往设置") }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            BoxWithConstraints(Modifier.sizeIn(maxWidth = if (editable) 210.dp else 280.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                val clockSize = ((maxWidth.value - 48) / (4.9f * LocalDensity.current.fontScale)).coerceAtMost(38f).sp
                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    drawArc(track, -90f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                    if (progress > 0) drawArc(primary, -90f, 360f * progress, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                    Text(when (state.phase) { Phase.IDLE -> "准备开始"; Phase.RUNNING -> "专注进行中"; Phase.PAUSED -> "已暂停"; Phase.FINISHED -> "时间到" },
                        style = MaterialTheme.typography.labelLarge, color = primary)
                    Spacer(Modifier.height(10.dp))
                    Text(formatTime(displayed), fontSize = clockSize, fontWeight = FontWeight.Medium, maxLines = 1,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { contentDescription = "剩余时间 ${formatTime(displayed)}" })
                    Spacer(Modifier.height(8.dp))
                    Text(if (state.phase == Phase.IDLE) "小时 : 分钟 : 秒" else "设定 ${formatTime(state.durationMs)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
            if (editable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DurationField("小时", hours, 99, Modifier.weight(1f)) { hours = it; error = null }
                    DurationField("分钟", minutes, 59, Modifier.weight(1f)) { minutes = it; error = null }
                    DurationField("秒", seconds, 59, Modifier.weight(1f)) { seconds = it; error = null }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 10, 25).forEach { value ->
                        OutlinedButton(onClick = { hours = "0"; minutes = value.toString(); seconds = "0"; error = null },
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)) { Text("${value}分") }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text("结束时提醒", style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                var menuOpen by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(mode.label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Text("⌄", modifier = Modifier.padding(start = 8.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        AlertMode.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label) }, onClick = { mode = option; menuOpen = false })
                        }
                    }
                }
            } else Text(state.mode.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("熄屏后继续计时 · 手机重启后取消", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (state.phase == Phase.FINISHED && state.mode.visual && !state.dismissed) {
        AlertDialog(onDismissRequest = { controller.stopRinging(dismiss = true) }, title = { Text("时间到") },
            text = { Text("${formatTime(state.durationMs)} 的倒计时已结束。") },
            confirmButton = { TextButton(onClick = { controller.stopRinging(dismiss = true) }) { Text(if (state.ringing) "停止响铃" else "知道了") } })
    }
}

@Composable
private fun DurationField(label: String, value: String, max: Int, modifier: Modifier, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { input ->
        if (input.length <= 2 && input.all { it in '0'..'9' } && (input.toIntOrNull() ?: 0) <= max) change(input)
    }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}
