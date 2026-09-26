package com.jaewon.brushalarm

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

class MainActivity : AppCompatActivity() {
    private lateinit var alarmList: LinearLayout
    private lateinit var status: TextView
    private val ink = Color.rgb(244, 242, 252)
    private val muted = Color.rgb(151, 149, 164)
    private val violet = Color.rgb(167, 151, 255)
    private val days = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    private val labels = listOf("일", "월", "화", "수", "목", "금", "토")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmRingingService.createChannel(this)
        setContentView(buildContent())
        requestRuntimePermissions()
        refreshList()
    }

    private fun buildContent(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(110))
            setBackgroundColor(Color.rgb(16, 17, 25))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "양치 알람"; textSize = 30f; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "+"; textSize = 25f; contentDescription = "알람 추가"
            setTextColor(ink); background = rounded(Color.rgb(45, 45, 57), 28)
            setOnClickListener { showAddMenu(this) }
        }, LinearLayout.LayoutParams(dp(64), dp(56)))
        header.addView(Button(this).apply {
            text = "⋮"; textSize = 26f; contentDescription = "알람 메뉴"
            setTextColor(ink); background = rounded(Color.rgb(45, 45, 57), 28)
            setOnClickListener { showOptions(this) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        body.addView(header)
        body.addView(TextView(this).apply {
            text = "울리기 전에는 언제든 켜거나 끌 수 있습니다. 울리면 30초 양치 후 종료됩니다."
            textSize = 14f; setTextColor(muted); setPadding(0, dp(16), 0, dp(20))
        })
        alarmList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(35, 35, 46), 28)
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        body.addView(alarmList, matchWidth())
        status = TextView(this).apply {
            textSize = 13f; setTextColor(muted); setPadding(0, dp(22), 0, 0)
        }
        body.addView(status, matchWidth())
        return FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(16, 17, 25))
            addView(ScrollView(this@MainActivity).apply { addView(body) }, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(this@MainActivity).apply {
                text = "◉  알람"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(ink); background = rounded(Color.rgb(48, 47, 63), 30)
                contentDescription = "알람 목록"
            }, FrameLayout.LayoutParams(dp(142), dp(54), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(20)
            })
        }
    }

    private fun refreshList(message: String? = null) {
        if (!::alarmList.isInitialized) return
        val entries = MultiAlarmStore(this).load()
            .sortedWith(compareBy<AlarmEntry> { it.hour }.thenBy { it.minute }.thenBy { it.id })
        alarmList.removeAllViews()
        if (entries.isEmpty()) alarmList.addView(TextView(this).apply {
            text = "등록된 알람이 없습니다. + 버튼으로 추가하세요."
            textSize = 16f; setTextColor(muted); setPadding(dp(8), dp(32), dp(8), dp(32))
        })
        entries.forEachIndexed { index, entry ->
            if (index > 0) alarmList.addView(View(this).apply { setBackgroundColor(Color.rgb(66, 65, 77)) },
                LinearLayout.LayoutParams(-1, dp(1)))
            alarmList.addView(alarmRow(entry), matchWidth())
        }
        val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val fullScreen = Build.VERSION.SDK_INT < 34 ||
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        status.text = buildString {
            message?.let { append(it).append("\n") }
            append(if (hasCameraPermission()) "카메라 ✓" else "카메라 ✗")
            append(" · ")
            append(if (exact) "정확한 알람 ✓" else "정확한 알람 ✗")
            append(" · ")
            append(if (fullScreen) "전체 화면 ✓" else "전체 화면 ✗")
        }
    }

    private fun alarmRow(entry: AlarmEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(18), dp(5), dp(12))
        }
        val upper = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        upper.addView(TextView(this).apply {
            text = if (entry.hour < 12) "오전" else "오후"; textSize = 15f
            setTextColor(if (entry.enabled) ink else muted); setPadding(0, dp(13), dp(8), 0)
        })
        upper.addView(TextView(this).apply {
            val hour = (entry.hour % 12).let { if (it == 0) 12 else it }
            text = "%d:%02d".format(hour, entry.minute); textSize = 44f
            setTextColor(if (entry.enabled) ink else muted)
            contentDescription = "${text} 알람 편집"
            setOnClickListener { editAlarm(entry) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        upper.addView(Switch(this).apply {
            isChecked = entry.enabled
            contentDescription = "${entry.hour}시 ${entry.minute}분 알람 ${if (entry.enabled) "켜짐" else "꺼짐"}"
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(violet, Color.rgb(101, 100, 115)))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnCheckedChangeListener { _, checked -> toggleAlarm(entry, checked) }
        })
        row.addView(upper, matchWidth())
        entry.date?.let { date ->
            row.addView(TextView(this).apply {
                text = "${date.monthValue}월 ${date.dayOfMonth}일 (${labels[days.indexOf(date.dayOfWeek)]}) · 1회성"
                textSize = 16f; setTextColor(if (entry.enabled) ink else muted)
                setPadding(dp(44), 0, 0, dp(8)); setOnClickListener { editAlarm(entry) }
            })
        } ?: run { row.addView(weekdayStrip(entry), matchWidth()) }
        row.addView(Button(this).apply {
            text = "⋮"; textSize = 21f; contentDescription = "알람 작업 메뉴"
            setOnClickListener { showRowActions(this, entry) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.END })
        return row
    }

    private fun weekdayStrip(entry: AlarmEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(44), 0, 0, dp(6))
        days.forEachIndexed { index, day ->
            val selected = day in entry.weekdays
            addView(TextView(this@MainActivity).apply {
                text = (if (selected) "•\n" else " \n") + labels[index]
                textSize = 14f; gravity = Gravity.CENTER
                setTextColor(if (selected && entry.enabled) violet else muted)
                contentDescription = "${labels[index]}요일 ${if (selected) "반복" else "미선택"}"
                setOnClickListener { editAlarm(entry) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun showRowActions(anchor: View, entry: AlarmEntry) {
        PopupMenu(this, anchor).apply {
            menu.add("편집").setOnMenuItemClickListener { editAlarm(entry); true }
            if (entry.date == null && entry.enabled) {
                menu.add("다음 1회 건너뛰기").setOnMenuItemClickListener { skipAlarm(entry); true }
            }
            menu.add("삭제").setOnMenuItemClickListener { deleteAlarm(entry); true }
            show()
        }
    }

    private fun showAddMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("반복 알람 추가").setOnMenuItemClickListener { chooseTime(null, false); true }
            menu.add("주말 1회성 알람 추가").setOnMenuItemClickListener { chooseTime(null, true); true }
            show()
        }
    }

    private fun showOptions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("지금 알람 테스트").setOnMenuItemClickListener { triggerTestAlarm(); true }
            menu.add("알람 화면 미리보기").setOnMenuItemClickListener { openScreenPreview(); true }
            menu.add("필수 시스템 권한 확인").setOnMenuItemClickListener { openMissingSystemPermission(); true }
            show()
        }
    }

    private fun editAlarm(entry: AlarmEntry) = chooseTime(entry, entry.date != null)

    private fun chooseTime(entry: AlarmEntry?, oneOff: Boolean) {
        val now = ZonedDateTime.now()
        TimePickerDialog(this, { _, hour, minute ->
            if (oneOff) chooseWeekend(entry, hour, minute) else chooseWeekdays(entry, hour, minute)
        }, entry?.hour ?: now.hour, entry?.minute ?: now.minute, true).show()
    }

    private fun chooseWeekdays(entry: AlarmEntry?, hour: Int, minute: Int) {
        val selected = BooleanArray(days.size) { index ->
            if (entry == null) days[index] in DayOfWeek.MONDAY..DayOfWeek.FRIDAY
            else days[index] in entry.weekdays
        }
        AlertDialog.Builder(this).setTitle("반복 요일 선택")
            .setMultiChoiceItems(labels.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("취소", null).setPositiveButton("저장") { _, _ ->
                val weekdays = days.filterIndexed { index, _ -> selected[index] }.toSet()
                if (weekdays.isEmpty()) refreshList("요일을 하나 이상 선택하세요.")
                else saveAlarm(entry, hour, minute, weekdays, null)
            }.show()
    }

    private fun chooseWeekend(entry: AlarmEntry?, hour: Int, minute: Int) {
        val now = ZonedDateTime.now()
        val default = entry?.date?.takeIf { !it.isBefore(now.toLocalDate()) }
            ?: nextWeekendDate(now, hour, minute)
        DatePickerDialog(this, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            if (date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) {
                refreshList("토요일 또는 일요일 날짜를 선택하세요.")
            } else if (!date.atTime(hour, minute).atZone(now.zone).isAfter(ZonedDateTime.now())) {
                refreshList("미래 시각을 선택하세요.")
            } else saveAlarm(entry, hour, minute, emptySet(), date)
        }, default.year, default.monthValue - 1, default.dayOfMonth).apply {
            datePicker.minDate = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        }.show()
    }

    private fun saveAlarm(entry: AlarmEntry?, hour: Int, minute: Int,
                          weekdays: Set<DayOfWeek>, date: LocalDate?) {
        val shouldEnable = entry?.enabled ?: true
        if (shouldEnable && !canEnableAlarm()) return
        val store = MultiAlarmStore(this)
        val changed = if (entry == null) store.add(hour, minute, weekdays, date, enabled = false)
        else entry.copy(hour = hour, minute = minute, weekdays = weekdays, date = date,
            enabled = shouldEnable, scheduledAtMillis = entry.scheduledAtMillis)
        if (!shouldEnable) {
            store.update(changed)
            refreshList("꺼진 상태로 알람을 수정했습니다.")
            return
        }
        val scheduled = if (entry != null) MultiAlarmScheduler.replaceEdited(this, changed)
            else MultiAlarmScheduler.activate(this, changed)
        if (!scheduled) {
            if (entry == null) {
                refreshList("알람 예약에 실패했습니다. 꺼진 상태로 저장했습니다.")
            } else refreshList("새 예약에 실패했습니다. 이전 예약이 유지됩니다.")
        } else refreshList("알람을 저장했습니다.")
    }

    private fun toggleAlarm(entry: AlarmEntry, checked: Boolean) {
        val store = MultiAlarmStore(this)
        if (!checked) {
            store.update(entry.copy(enabled = false, scheduledAtMillis = null))
            MultiAlarmScheduler.cancel(this, entry.id)
            refreshList("알람을 껐습니다.")
            return
        }
        if (entry.date == null && entry.weekdays.isEmpty()) {
            refreshList("요일을 하나 이상 선택하세요.")
            return
        }
        if (nextTriggerMillis(entry.copy(enabled = true), System.currentTimeMillis()) == null) {
            refreshList("지난 1회성 알람입니다. 날짜를 편집해 다시 켜세요.")
            return
        }
        if (!canEnableAlarm()) { refreshList("권한을 허용한 뒤 다시 켜세요."); return }
        if (!MultiAlarmScheduler.activate(this, entry)) {
            refreshList("예약에 실패해 알람을 껐습니다.")
        } else refreshList("알람을 켰습니다.")
    }

    private fun skipAlarm(entry: AlarmEntry) {
        if (MultiAlarmScheduler.skipNext(this, entry.id)) refreshList("다음 알람 1회를 건너뛰었습니다.")
        else refreshList("이미 건너뛴 일정이 있거나 다음 알람을 변경할 수 없습니다.")
    }

    private fun deleteAlarm(entry: AlarmEntry) {
        AlertDialog.Builder(this).setTitle("알람 삭제")
            .setMessage("${entry.hour}시 ${entry.minute}분 알람을 삭제할까요?")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                MultiAlarmStore(this).delete(entry.id)
                MultiAlarmScheduler.cancel(this, entry.id)
                refreshList("알람을 삭제했습니다.")
            }.show()
    }

    private fun canEnableAlarm(): Boolean {
        if (!hasCameraPermission()) {
            requestRuntimePermissions(); refreshList("카메라 권한이 필요합니다."); return false
        }
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            openExactAlarmSettings(); refreshList("정확한 알람 권한이 필요합니다."); return false
        }
        return true
    }

    private fun triggerTestAlarm() {
        if (!hasCameraPermission()) {
            requestRuntimePermissions(); refreshList("카메라 권한이 필요합니다."); return
        }
        ContextCompat.startForegroundService(this, Intent(this, AlarmRingingService::class.java))
        startActivity(Intent(this, AlarmActivity::class.java))
    }

    private fun openScreenPreview() {
        startActivity(Intent(this, PreviewActivity::class.java))
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openMissingSystemPermission() {
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            openExactAlarmSettings(); return
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            return
        }
        requestRuntimePermissions(); refreshList()
    }

    private fun openExactAlarmSettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(-1, -2)
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    override fun onResume() {
        super.onResume()
        if (::alarmList.isInitialized) {
            MultiAlarmScheduler.rescheduleAll(this)
            refreshList()
        }
    }
}
