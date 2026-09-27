package com.jaewon.brushalarm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowSecurityContractTest {
    @Test fun pullRequestAndReleaseBuildsRejectNetworkPermissions() {
        val ci = File("../.github/workflows/android.yml").readText()
        val release = File("../.github/workflows/release.yml").readText()
        assertTrue(ci.contains("verifyNoNetworkPermissions"))
        assertTrue(release.contains("verifyNoNetworkPermissions"))
    }
}
