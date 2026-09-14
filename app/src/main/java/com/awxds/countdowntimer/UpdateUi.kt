package com.awxds.countdowntimer

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun UpdateButton(resumeVersion: Int, timer: TimerState) {
    val context = LocalContext.current
    val manager = (context.applicationContext as TimerApp).updates
    val update by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var show by rememberSaveable { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    val canInstall = remember(resumeVersion) { context.packageManager.canRequestPackageInstalls() }
    LaunchedEffect(resumeVersion) { manager.check(automatic = true) }
    TextButton(onClick = {
        show = true; installError = null
        if (update.stage in setOf(UpdateStage.IDLE, UpdateStage.CURRENT, UpdateStage.ERROR)) manager.check()
    }) {
        Text(when (update.stage) {
            UpdateStage.AVAILABLE, UpdateStage.READY -> "发现新版本"
            UpdateStage.DOWNLOADING -> "下载 ${(update.progress * 100).toInt()}%"
            else -> "检查更新"
        })
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("应用更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}")
                when (update.stage) {
                    UpdateStage.CHECKING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查 GitHub Releases…") }
                    UpdateStage.AVAILABLE -> Text("新版本 ${update.info?.versionName} · ${(update.info?.size ?: 0) / 1024 / 1024} MB")
                    UpdateStage.DOWNLOADING -> { LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth()); Text("正在下载，关闭此窗口后会继续。") }
                    UpdateStage.READY -> Text("下载完成，文件及签名校验通过。安装需系统确认。")
                    UpdateStage.CURRENT, UpdateStage.ERROR -> Text(update.message)
                    UpdateStage.IDLE -> Text("从公开 GitHub 仓库检查更新。")
                }
                update.info?.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (update.stage == UpdateStage.READY) {
                    if (timer.phase == Phase.RUNNING || timer.phase == Phase.PAUSED || timer.ringing)
                        Text("请先结束或重置当前倒计时，再安装更新。")
                    else if (!canInstall) Text("首次安装需要允许此应用安装更新包，授权后返回这里点击安装。")
                }
                installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            when (update.stage) {
                UpdateStage.AVAILABLE -> TextButton(onClick = { manager.download() }) { Text("下载更新") }
                UpdateStage.READY -> TextButton(enabled = !installing && timer.phase !in setOf(Phase.RUNNING, Phase.PAUSED) && !timer.ringing,
                    onClick = {
                        installError = null
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                                .onFailure { installError = "无法打开安装权限设置，请在系统设置中手动允许。" }
                        } else scope.launch {
                            installing = true
                            try {
                                val intent = manager.installerIntent()
                                val latest = context.timer.state.value
                                if (latest.phase in setOf(Phase.RUNNING, Phase.PAUSED) || latest.ringing)
                                    installError = "请先结束当前倒计时。"
                                else context.startActivity(intent)
                            } catch (e: Exception) { installError = "无法安装：${e.message?.take(150)}" }
                            finally { installing = false }
                        }
                    }) { Text(if (canInstall) "安装更新" else "允许安装更新") }
                UpdateStage.ERROR, UpdateStage.IDLE -> TextButton(onClick = { manager.check() }) { Text("重试") }
                else -> Unit
            }
        }, dismissButton = { TextButton(onClick = { show = false }) { Text("关闭") } })
}
