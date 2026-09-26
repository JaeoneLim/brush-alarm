package com.jaewon.brushalarm

/** Preview is a separate activity, never an intent extra on the real alarm component. */
enum class AlarmScreenMode(
    val showCamera: Boolean,
    val showExitButton: Boolean,
    val wakeAndUnlockScreen: Boolean,
    val blockDismissal: Boolean,
    val runBrushingVerification: Boolean,
    val stopRingingOnCompletion: Boolean,
) {
    ALARM(true, false, true, true, true, true),
    PREVIEW(true, true, false, false, false, false),
}
