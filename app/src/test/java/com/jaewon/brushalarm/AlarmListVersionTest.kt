package com.jaewon.brushalarm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmListVersionTest {
    @Test fun upgradeHasNewVersionAndSamePackage() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"0.4.0\""))
        assertTrue(gradle.contains("versionCode = 7"))
        assertTrue(gradle.contains("applicationId = \"com.jaewon.brushalarm\""))
    }
}
