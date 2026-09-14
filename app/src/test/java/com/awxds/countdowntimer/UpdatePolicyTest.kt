package com.awxds.countdowntimer

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val valid = UpdateInfo(3, "1.2.0", "https://github.com/GodBook/countdown-timer/releases/download/v1.2.0/timer.apk", "a".repeat(64), 7_000_000, 26, "更新")
    @Test fun sameOrOlderAndUnsupportedVersionsDoNotUpdate() {
        assertTrue(valid.validate().isNewer(2, 36))
        assertFalse(valid.isNewer(3, 36))
        assertFalse(valid.isNewer(4, 36))
        assertFalse(valid.copy(minSdk = 37).isNewer(2, 36))
    }
    @Test fun rejectUntrustedUrls() {
        listOf("http://github.com/GodBook/countdown-timer/releases/download/v1.2.0/timer.apk",
            "https://evil.example/timer.apk", "https://github.com/other/repo/releases/download/v1/timer.apk",
            "https://github.com.evil.example/GodBook/countdown-timer/releases/download/v1/timer.apk",
            "https://user@github.com/GodBook/countdown-timer/releases/download/v1/timer.apk").forEach {
                assertThrows(IllegalArgumentException::class.java) { valid.copy(apkUrl = it).validate() }
            }
    }
    @Test fun rejectInvalidDigestAndOversizedDownloads() {
        assertThrows(IllegalArgumentException::class.java) { valid.copy(sha256 = "wrong").validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(size = 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(size = UpdateInfo.MAX_APK_SIZE + 1).validate() }
    }
}
