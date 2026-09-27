package com.jaewon.brushalarm

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class MultiAlarmStoreTest {
    @Test fun previouslySavedDisabledAlarmSurvivesMigrationAsDisabledRow() {
        val disabled = StoredAlarmState(
            AlarmSchedule(6, 50, setOf(DayOfWeek.SUNDAY), false), null, null, null)
        val migrated = migrateLegacyEntry(disabled, hasSavedSchedule = true)
        assertNotNull(migrated)
        assertFalse(migrated!!.enabled)
        assertEquals(6, migrated.hour)
        assertNull(migrated.scheduledAtMillis)
        assertNull(migrateLegacyEntry(disabled, hasSavedSchedule = false))
    }
    @Test fun disabledLegacyScheduleWithNoWeekdaysRemainsEditableAfterMigration() {
        val disabled = StoredAlarmState(AlarmSchedule(6, 50, emptySet(), false), null, null, null)
        val migrated = migrateLegacyEntry(disabled, hasSavedSchedule = true)
        assertNotNull(migrated)
        assertEquals(emptySet<DayOfWeek>(), migrated!!.weekdays)
        assertFalse(migrated.enabled)
        assertNull(migrated.scheduledAtMillis)
        assertNull(nextTriggerMillis(migrated, 0L))
        assertEquals(migrated, decodeAlarmEntry(encodeAlarmEntry(migrated)))
        assertNull(migrateLegacyEntry(disabled, hasSavedSchedule = false))
        assertThrows(IllegalArgumentException::class.java) { migrated.copy(enabled = true).validate() }
    }

    @Test fun editingAnEnabledRowInvalidatesOldCommitSoResumeRebuildsNewTime() {
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmStore.kt").readText()
        val update = source.substringAfter("fun update(entry: AlarmEntry)").substringBefore("fun consumeSkipped(")
        assertTrue(update.contains("prior.hour != entry.hour"))
        assertTrue(update.contains("editor.remove(\"committed_${'$'}{entry.id}\")"))
    }

    @Test fun deletionSharesTheDeliveryLockSoQueuedServiceCannotRace() {
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmStore.kt").readText()
        assertTrue(source.contains("fun delete(id: Int) = synchronized(multiAlarmStoreLock)"))
    }

    @Test fun codecRoundTripsRecurringAndDateEntries() {
        val entries = listOf(
            AlarmEntry(17, 6, 45, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), null, true, 123456L),
            AlarmEntry(18, 9, 5, emptySet(), LocalDate.parse("2026-10-03"), false, null),
        )
        entries.forEach { assertEquals(it, decodeAlarmEntry(encodeAlarmEntry(it))) }
    }

    @Test fun codecPersistsLogicalSkipDate() {
        val entry = AlarmEntry(2, 7, 0, setOf(DayOfWeek.SUNDAY), null, true, 345L,
            123L, LocalDate.parse("2026-10-04"))
        assertEquals(entry, decodeAlarmEntry(encodeAlarmEntry(entry)))
    }

    @Test fun codecRefusesCorruptOrInvalidEntries() {
        assertNull(decodeAlarmEntry("garbage"))
        assertNull(decodeAlarmEntry("4|24|10|SATURDAY||true|"))
        assertNull(decodeAlarmEntry("4|9|10|MONDAY|2026-10-05|true|"))
    }
}
