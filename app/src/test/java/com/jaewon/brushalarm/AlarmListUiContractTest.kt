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
    @Test fun addOffersRepeatAndDatedWeekendModes() {
        assertTrue(source.contains("반복 알람 추가"))
        assertTrue(source.contains("주말 1회성 알람 추가"))
        assertTrue(source.contains("DatePickerDialog"))
        assertTrue(source.contains("TimePickerDialog"))
        assertTrue(source.contains("dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)"))
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
    @Test fun returningToListReconcilesEnabledAlarmsAfterPermissionRestore() {
        val resume = source.substringAfter("override fun onResume()")
        assertTrue(resume.contains("MultiAlarmScheduler.rescheduleAll(this)"))
    }
}
