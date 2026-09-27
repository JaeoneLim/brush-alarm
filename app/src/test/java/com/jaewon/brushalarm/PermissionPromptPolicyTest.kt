package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionPromptPolicyTest {
    @Test fun onboardingChecksEveryMissingPermissionInOrder() {
        val all = RequiredPermission.entries.toSet()
        assertEquals(RequiredPermission.CAMERA, nextPermissionToExplain(all, emptySet()))
        assertEquals(RequiredPermission.NOTIFICATIONS,
            nextPermissionToExplain(all, setOf(RequiredPermission.CAMERA)))
        assertEquals(RequiredPermission.EXACT_ALARM,
            nextPermissionToExplain(all, setOf(RequiredPermission.CAMERA, RequiredPermission.NOTIFICATIONS)))
        assertEquals(RequiredPermission.FULL_SCREEN,
            nextPermissionToExplain(all, all - RequiredPermission.FULL_SCREEN))
    }

    @Test fun dismissedPermissionIsNotReopenedOnEveryResume() {
        val missing = setOf(RequiredPermission.EXACT_ALARM)
        assertEquals(RequiredPermission.EXACT_ALARM, nextPermissionToExplain(missing, emptySet()))
        assertNull(nextPermissionToExplain(missing, setOf(RequiredPermission.EXACT_ALARM)))
        assertNull(nextPermissionToExplain(emptySet(), emptySet()))
    }
}
