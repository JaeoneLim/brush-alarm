package com.jaewon.brushalarm

/** One explanation per missing permission per activity launch, including after returning from Settings. */
enum class RequiredPermission { CAMERA, NOTIFICATIONS, EXACT_ALARM, FULL_SCREEN }

fun nextPermissionToExplain(
    missing: Set<RequiredPermission>, alreadyPrompted: Set<RequiredPermission>
): RequiredPermission? = RequiredPermission.entries.firstOrNull { it in missing && it !in alreadyPrompted }
