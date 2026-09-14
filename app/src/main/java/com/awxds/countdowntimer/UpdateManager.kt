package com.awxds.countdowntimer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class UpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateState(val stage: UpdateStage = UpdateStage.IDLE, val info: UpdateInfo? = null,
    val progress: Float = 0f, val message: String = "")

class UpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val directory get() = File(context.filesDir, "updates").apply { mkdirs() }
    private val apk get() = File(directory, "update.apk")
    private val prefs = context.getSharedPreferences("update", 0)
    fun check(automatic: Boolean = false) {
        if (job?.isActive == true || state.value.stage == UpdateStage.READY) return
        if (automatic && (state.value.stage != UpdateStage.IDLE ||
            System.currentTimeMillis() - prefs.getLong("checked", 0) in 0 until 86_400_000)) return
        job = scope.launch {
            mutable.value = UpdateState(UpdateStage.CHECKING)
            try {
                val info = withContext(Dispatchers.IO) { parse(fetchManifest()) }
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                mutable.value = if (info.isNewer(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT))
                    UpdateState(UpdateStage.AVAILABLE, info) else UpdateState(UpdateStage.CURRENT,
                        message = if (info.versionCode > BuildConfig.VERSION_CODE) "新版本需要 Android API ${info.minSdk}，当前设备无法安装。" else "当前已是最新版本。")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = if (automatic) UpdateState() else UpdateState(UpdateStage.ERROR,
                    message = "检查失败，请检查网络后重试。${e.message?.take(140).orEmpty()}")
            }
        }
    }
    fun download() {
        val info = state.value.info ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            mutable.value = UpdateState(UpdateStage.DOWNLOADING, info)
            try {
                withContext(Dispatchers.IO) {
                    val part = File(directory, "update.part")
                    try {
                        val connection = connect(info.apkUrl)
                        try {
                            connection.inputStream.use { input -> part.outputStream().use { output ->
                                val buffer = ByteArray(32768)
                                var count = 0L
                                var lastProgress = -1
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    require(count <= info.size) { "下载文件超出预期大小" }
                                    output.write(buffer, 0, read)
                                    val percent = (count * 100 / info.size).toInt()
                                    if (percent != lastProgress) {
                                        lastProgress = percent
                                        mutable.value = UpdateState(UpdateStage.DOWNLOADING, info, count.toFloat() / info.size)
                                    }
                                }
                            } }
                        } finally { connection.disconnect() }
                        verify(part, info)
                        if (apk.exists()) check(apk.delete()) { "无法替换旧更新包" }
                        check(part.renameTo(apk)) { "无法保存更新包" }
                    } finally { part.delete() }
                }
                mutable.value = UpdateState(UpdateStage.READY, info)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = UpdateState(UpdateStage.ERROR, info, message = "下载或校验失败：${e.message?.take(180)}。可重试。")
            }
        }
    }
    suspend fun installerIntent(): Intent {
        val info = state.value.info ?: error("请先下载更新")
        withContext(Dispatchers.IO) { verify(apk, info) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    @Suppress("DEPRECATION")
    internal fun verify(file: File, info: UpdateInfo) {
        info.validate()
        require(file.length() == info.size) { "文件大小不一致" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) }.equals(info.sha256, true)) { "SHA-256 校验失败" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("无法读取 APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && version == info.versionCode && version > BuildConfig.VERSION_CODE) { "安装包的应用或版本不匹配" }
        fun signatures(pkg: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) pkg.signingInfo?.apkContentsSigners else pkg.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()
        val trusted = signatures(installed)
        require(trusted.isNotEmpty() && signatures(archive) == trusted) { "安装包签名与当前应用不一致" }
    }
    private fun fetchManifest(): String {
        val connection = connect(MANIFEST_URL)
        try {
            return connection.inputStream.use { input ->
                val bytes = input.readBytesLimited(65536)
                String(bytes, Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "更新信息过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun connect(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && url.port == -1 &&
                url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "更新重定向地址无效" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CountdownTimer/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "更新地址无效" }
                url = URL(url, location)
            } else {
                if (code != 200) { connection.disconnect(); error(if (code == 404) "尚未发布更新信息" else "服务器返回 HTTP $code") }
                return connection
            }
        }
        error("更新地址重定向次数过多")
    }
    companion object {
        const val MANIFEST_URL = "https://github.com/GodBook/countdown-timer/releases/latest/download/update.json"
        fun parse(text: String): UpdateInfo {
            val json = JSONObject(text)
            require(json.getInt("schemaVersion") == 1) { "不支持的更新信息格式" }
            return UpdateInfo(json.getLong("versionCode"), json.getString("versionName"), json.getString("apkUrl"),
                json.getString("sha256"), json.getLong("size"), json.getInt("minSdk"), json.optString("notes").take(4000)).validate()
        }
    }
}
