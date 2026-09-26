package com.jaewon.brushalarm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level safety contracts complement the pure policy tests until a device is available. */
class PreviewWiringContractTest {
    private fun source(name: String) = File("src/main/java/com/jaewon/brushalarm/$name").readText()

    @Test fun mainLaunchesDedicatedPreviewWithoutStartingAlarmService() {
        val entry = source("MainActivity.kt")
            .substringAfter("private fun openScreenPreview()")
            .substringBefore("private fun requestRuntimePermissions()")
        assertTrue(entry.contains("Intent(this, PreviewActivity::class.java)"))
        assertFalse(entry.contains("AlarmRingingService"))
        assertFalse(entry.contains("RecurringAlarmScheduler"))
    }

    @Test fun previewIsNotLockScreenCapableAndCannotBeLaunchedExternally() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".PreviewActivity\"")
            .substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        assertFalse(entry.contains("android:showWhenLocked=\"true\""))
        assertFalse(entry.contains("android:turnScreenOn=\"true\""))
        assertFalse(entry.contains("android:launchMode=\"singleTask\""))
    }

    @Test fun previewDoesNotStartFaceMeshOrTrapBackPress() {
        val activity = source("AlarmActivity.kt")
        assertTrue(activity.contains("if (mode.runBrushingVerification) initializeFaceMesh()"))
        assertTrue(activity.contains("if (mode.blockDismissal)"))
        assertTrue(activity.contains("if (!mode.runBrushingVerification) return"))
        assertTrue(activity.contains("if (!mode.stopRingingOnCompletion) return"))
        assertTrue(activity.contains("if (mode.showExitButton)"))
    }

    @Test fun previewNeverUnbindsOtherActivitiesCameraUseCases() {
        val activity = source("AlarmActivity.kt")
        assertTrue(activity.contains("if (mode.runBrushingVerification) provider.unbindAll()"))
        assertTrue(activity.contains("cameraProvider?.unbind(previewUseCase)"))
        assertTrue(activity.contains("if (mode.runBrushingVerification) cameraProvider?.unbindAll()"))
    }

    @Test fun installablePatchIncrementsVersionWithoutChangingPackage() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionName = \"0.3.1\""))
        assertTrue(gradle.contains("versionCode = 6"))
        assertTrue(gradle.contains("applicationId = \"com.jaewon.brushalarm\""))
    }
}
