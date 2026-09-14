package com.awxds.countdowntimer

import java.net.URI

data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String,
    val sha256: String, val size: Long, val minSdk: Int, val notes: String) {
    fun validate(): UpdateInfo {
        require(versionCode > 0 && versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "更新版本格式无效" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "更新文件校验信息无效" }
        require(size in 1..MAX_APK_SIZE && minSdk >= 26) { "更新文件大小或系统要求无效" }
        val uri = URI(apkUrl)
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.path.startsWith("/GodBook/countdown-timer/releases/download/") && uri.path.endsWith(".apk") &&
            uri.query == null && uri.fragment == null) { "更新地址不属于官方仓库" }
        return this
    }
    fun isNewer(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    companion object { const val MAX_APK_SIZE = 100L * 1024 * 1024 }
}
