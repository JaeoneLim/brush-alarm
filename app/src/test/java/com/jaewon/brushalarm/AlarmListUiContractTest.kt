package com.jaewon.brushalarm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmListUiContractTest {
    private val source by lazy { File("src/main/java/com/jaewon/brushalarm/MainActivity.kt").readText() }
    @Test fun listOffersIndependentSwitchAndDeletePerAlarm() {
        assertTrue(source.contains("MultiAlarmStore(this).load()"))
        assertTrue(source.contains("setOnCheckedChangeListener { _, checked -> toggleAlarm(entry, checked) }"))
        assertTrue(source.contains("menu.add(\"삭제\").setOnMenuItemClickListener { deleteAlarm(entry); true }"))
        assertTrue(source.contains("date?.let"))
    }
    @Test fun addUsesSinglePickerWithOptionalRepeatDaysAndTodayOnlyForOneOff() {
        val content = source.substringAfter("private fun buildContent()").substringBefore("private fun refreshList(")
        assertTrue(content.contains("setOnClickListener { chooseTime(null) }"))
        assertFalse(source.contains("showAddMenu("))
        assertFalse(source.contains("DatePickerDialog"))
        val dayPicker = source.substringAfter("private fun chooseWeekdays(").substringBefore("private fun saveAlarm(")
        assertTrue(dayPicker.contains("if (weekdays.isEmpty())"))
        assertTrue(dayPicker.contains("oneOffDateForToday("))
        assertTrue(dayPicker.contains("saveAlarm(entry, hour, minute, weekdays, null)"))
    }
    @Test fun listHasNoFakeBottomAlarmTabOrOversizedSpacer() {
        val content = source.substringAfter("private fun buildContent()").substringBefore("private fun refreshList(")
        assertFalse(content.contains("◉  알람"))
        assertFalse(content.contains("dp(110)"))
        assertTrue(content.contains("return ScrollView(this).apply"))
        val row = source.substringAfter("private fun alarmRow(").substringBefore("private fun weekdayStrip(")
        assertTrue(row.contains("val lower = LinearLayout(this)"))
        assertTrue(row.contains("lower.addView(Button(this)"))
    }
    @Test fun compactRowKeepsAccessibleTouchTargets() {
        val row = source.substringAfter("private fun alarmRow(").substringBefore("private fun showRowActions(")
        assertTrue(row.contains("LinearLayout.LayoutParams(dp(48), dp(48))"))
        assertTrue(row.contains("LinearLayout.LayoutParams(0, dp(48), 1f)"))
        assertTrue(row.contains("setOnClickListener { editAlarm(entry) }"))
    }
    @Test fun permissionStatusRemainsEasyToTapWhenOnlyOneLine() {
        val status = source.substringAfter("status = TextView(this).apply {")
            .substringBefore("body.addView(status")
        assertTrue(status.contains("minHeight = dp(48)"))
        assertTrue(status.contains("setOnClickListener { openMissingSystemPermission() }"))
    }
    @Test fun alarmOptionsRemainSeparateFromScheduling() {
        assertTrue(source.contains("PopupMenu"))
        assertTrue(source.contains("openScreenPreview()"))
        assertTrue(source.contains("triggerTestAlarm()"))
        assertFalse(source.contains("enabledSwitch"))
    }
    @Test fun newAndOffToOnRowsCommitEnabledOnlyAfterInstallation() {
        val save = source.substringAfter("private fun saveAlarm(").substringBefore("private fun toggleAlarm(")
        val toggle = source.substringAfter("private fun toggleAlarm(").substringBefore("private fun skipAlarm(")
        assertTrue(save.contains("store.add(hour, minute, weekdays, date, enabled = false)"))
        assertTrue(save.contains("MultiAlarmScheduler.activate(this, changed)"))
        assertFalse(save.contains("MultiAlarmScheduler.schedule(this, changed)"))
        assertTrue(toggle.contains("MultiAlarmScheduler.activate(this, entry)"))
        assertFalse(toggle.contains("store.update(enabled)"))
    }
    @Test fun enabledEditInstallsReplacementBeforePersistingNewRow() {
        val edit = source.substringAfter("private fun saveAlarm(").substringBefore("private fun toggleAlarm(")
        assertTrue(edit.contains("MultiAlarmScheduler.replaceEdited(this, changed)"))
        assertFalse(edit.contains("also(store::update)"))
        val scheduler = File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        val replace = scheduler.substringAfter("fun replaceEdited(context: Context, edited: AlarmEntry").substringBefore("fun skipNext(")
        assertTrue(replace.contains("AlarmCrashProtocol.replaceEdited("))
        assertTrue(replace.contains("store.commitEditedSchedule("))
    }
    @Test fun editingDisabledAlarmDoesNotTurnItOn() {
        assertTrue(source.contains("val shouldEnable = entry?.enabled ?: true"))
        assertTrue(source.contains("enabled = shouldEnable, scheduledAtMillis = entry.scheduledAtMillis"))
        val edit = source.substringAfter("private fun saveAlarm(").substringBefore("private fun toggleAlarm(")
        assertFalse(edit.contains("MultiAlarmScheduler.cancel(this, entry.id)"))
        assertTrue(source.contains("if (!shouldEnable) {"))
        assertTrue(source.contains("store.update(changed)"))
    }
    @Test fun emptyMigratedWeekdayRowCannotBeEnabledBeforeEditing() {
        val toggle = source.substringAfter("private fun toggleAlarm(entry: AlarmEntry, checked: Boolean)")
            .substringBefore("private fun skipAlarm(")
        assertTrue(toggle.contains("entry.date == null && entry.weekdays.isEmpty()"))
        assertTrue(toggle.contains("요일을 하나 이상 선택하세요."))
        assertTrue(toggle.indexOf("entry.date == null && entry.weekdays.isEmpty()") <
            toggle.indexOf("nextTriggerMillis(entry.copy(enabled = true)"))
    }
    @Test fun recurringRowRetainsSkipNextOnce() {
        assertTrue(source.contains("menu.add(\"다음 1회 건너뛰기\").setOnMenuItemClickListener { skipAlarm(entry); true }"))
        assertTrue(source.contains("MultiAlarmScheduler.skipNext(this, entry.id)"))
    }
    @Test fun rowActionsFitNarrowScreensInOverflowMenu() {
        assertTrue(source.contains("private fun showRowActions(anchor: View, entry: AlarmEntry)"))
        assertTrue(source.contains("menu.add(\"다음 1회 건너뛰기\")"))
        assertTrue(source.contains("menu.add(\"삭제\")"))
        assertFalse(source.contains("val controls = LinearLayout(this).apply { gravity = Gravity.END }"))
    }
    @Test fun missingPermissionsAreExplainedOnLaunchWithoutResumeSettingsLoop() {
        val resume = source.substringAfter("override fun onResume()")
        assertTrue(resume.contains("guideMissingPermissionsOnce()"))
        val guide = source.substringAfter("private fun guideMissingPermissionsOnce()")
            .substringBefore("private fun requestRuntimePermissions(")
        assertTrue(guide.contains("nextPermissionToExplain(missing, promptedPermissions)"))
        assertTrue(guide.contains("promptedPermissions.add(next)"))
        assertTrue(guide.contains("missing.joinToString"))
        assertTrue(guide.contains("나중에 눌러도 목록 아래 권한 상태에서 설정할 수 있습니다."))
        assertTrue(guide.contains("설정으로 이동"))
        assertTrue(guide.contains("나중에"))
        assertTrue(source.contains("canUseFullScreenIntent()"))
        assertTrue(source.contains("canScheduleExactAlarms()"))
    }
    @Test fun returningToListReconcilesEnabledAlarmsAfterPermissionRestore() {
        val resume = source.substringAfter("override fun onResume()")
        assertTrue(resume.contains("MultiAlarmScheduler.rescheduleAll(this)"))
    }
}
