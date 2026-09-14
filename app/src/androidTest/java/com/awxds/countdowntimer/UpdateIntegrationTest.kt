package com.awxds.countdowntimer

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String): File {
        val file = File(context.cacheDir, name)
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/$name")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }
    private fun info(file: File) = UpdateInfo(3, "1.1.1", "https://github.com/GodBook/countdown-timer/releases/download/v1.1.1/timer.apk",
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }, file.length(), 26, "test")
    @Test fun validFutureApkAcceptedAndWrongSignerRejected() {
        val manager = UpdateManager(context)
        val valid = fixture("timer-candidate-debug.apk")
        manager.verify(valid, info(valid))
        val otherSigner = fixture("timer-candidate-release.apk")
        val error = assertThrows(IllegalArgumentException::class.java) { manager.verify(otherSigner, info(otherSigner)) }
        assertTrue(error.message!!.contains("签名"))
    }
    @Test fun corruptAndTruncatedFilesRejected() {
        val manager = UpdateManager(context)
        val valid = fixture("timer-candidate-debug.apk")
        val info = info(valid)
        assertThrows(IllegalArgumentException::class.java) { manager.verify(valid, info.copy(sha256 = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { manager.verify(valid, info.copy(size = info.size + 1)) }
    }
    @Test fun parseManifestAndRejectUnsupportedSchema() {
        val file = fixture("timer-candidate-debug.apk")
        val info = info(file)
        val json = """{"schemaVersion":1,"versionCode":3,"versionName":"1.1.1","minSdk":26,"apkUrl":"${info.apkUrl}","sha256":"${info.sha256}","size":${info.size},"notes":"修复"}"""
        assertEquals(3L, UpdateManager.parse(json).versionCode)
        assertThrows(IllegalArgumentException::class.java) { UpdateManager.parse(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
    }
}
