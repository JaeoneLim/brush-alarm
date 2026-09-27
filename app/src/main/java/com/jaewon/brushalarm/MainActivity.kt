package com.jaewon.brushalarm

import android.Manifest
import android.app.AlarmManager

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
    private val promptedPermissions = mutableSetOf<RequiredPermission>()
    private var permissionPromptVisible = false
    private var permissionRequestInProgress = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestInProgress = false
        refreshList()
        guideMissingPermissionsOnce()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmRingingService.createChannel(this)
        setContentView(buildContent())
        refreshList()
    }

    private fun buildContent(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            setBackgroundColor(Color.rgb(16, 17, 25))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "양치 알람"; textSize = 26f; setTextColor(ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "+"; textSize = 25f; contentDescription = "알람 추가"
            setTextColor(ink); background = rounded(Color.rgb(45, 45, 57), 28)
            setOnClickListener { chooseTime(null) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(Button(this).apply {
            text = "⋮"; textSize = 26f; contentDescription = "알람 메뉴"
            setTextColor(ink); background = rounded(Color.rgb(45, 45, 57), 28)
            setOnClickListener { showOptions(this) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        body.addView(header)
        body.addView(TextView(this).apply {
            text = "울리기 전 ON/OFF · 울리면 30초 양치 후 종료"
            textSize = 13f; setTextColor(muted); setPadding(0, dp(6), 0, dp(10))
        })
        alarmList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(35, 35, 46), 20)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        body.addView(alarmList, matchWidth())
        status = TextView(this).apply {
            textSize = 13f; setTextColor(muted); setPadding(0, dp(10), 0, 0)
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openMissingSystemPermission() }
        }
        body.addView(status, matchWidth())
        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 17, 25))
            addView(body)
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
        val missing = missingRequiredPermissions()
        status.text = buildString {
            message?.let { append(it).append("\n") }
            append(if (missing.isEmpty()) "필수 권한 준비됨 ✓" else "필수 권한 ${missing.size}개 필요 · 눌러서 설정")
        }
    }

    private fun alarmRow(entry: AlarmEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(6))
        }
        val upper = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        upper.addView(TextView(this).apply {
            text = if (entry.hour < 12) "오전" else "오후"; textSize = 15f
            setTextColor(if (entry.enabled) ink else muted); setPadding(0, dp(8), dp(8), 0)
        })
        upper.addView(TextView(this).apply {
            val hour = (entry.hour % 12).let { if (it == 0) 12 else it }
            text = "%d:%02d".format(hour, entry.minute); textSize = 38f
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
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
        val lower = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        entry.date?.let { date ->
            lower.addView(TextView(this).apply {
                text = if (date == LocalDate.now()) "오늘 · 1회" else
                    "${date.monthValue}월 ${date.dayOfMonth}일 (${labels[days.indexOf(date.dayOfWeek)]}) · 1회"
                textSize = 14f; setTextColor(if (entry.enabled) ink else muted)
                minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(42), 0, 0, 0); setOnClickListener { editAlarm(entry) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        } ?: run { lower.addView(weekdayStrip(entry), LinearLayout.LayoutParams(0, -2, 1f)) }
        lower.addView(Button(this).apply {
            text = "⋮"; textSize = 21f; contentDescription = "알람 작업 메뉴"
            setOnClickListener { showRowActions(this, entry) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(lower, matchWidth())
        return row
    }

    private fun weekdayStrip(entry: AlarmEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(42), 0, 0, 0)
        contentDescription = "반복 요일 ${entry.weekdays.joinToString { labels[days.indexOf(it)] }} 알람 편집"
        setOnClickListener { editAlarm(entry) }
        days.forEachIndexed { index, day ->
            val selected = day in entry.weekdays
            addView(TextView(this@MainActivity).apply {
                text = labels[index]
                textSize = 14f; gravity = Gravity.CENTER
                setTextColor(if (selected && entry.enabled) violet else muted)
                contentDescription = "${labels[index]}요일 ${if (selected) "반복" else "미선택"}"
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
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


    private fun showOptions(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("지금 알람 테스트").setOnMenuItemClickListener { triggerTestAlarm(); true }
            menu.add("알람 화면 미리보기").setOnMenuItemClickListener { openScreenPreview(); true }
            menu.add("필수 시스템 권한 확인").setOnMenuItemClickListener { openMissingSystemPermission(); true }
            show()
        }
    }

    private fun editAlarm(entry: AlarmEntry) = chooseTime(entry)

    private fun chooseTime(entry: AlarmEntry?) {
        val now = ZonedDateTime.now()
        TimePickerDialog(this, { _, hour, minute ->
            chooseWeekdays(entry, hour, minute)
        }, entry?.hour ?: now.hour, entry?.minute ?: now.minute, true).show()
    }

    private fun chooseWeekdays(entry: AlarmEntry?, hour: Int, minute: Int) {
        val selected = BooleanArray(days.size) { index ->
            entry != null && days[index] in entry.weekdays
        }
        AlertDialog.Builder(this).setTitle("요일 반복 · 미선택 시 오늘 한 번")
            .setMessage(if (entry?.date?.isAfter(LocalDate.now()) == true)
                "기존 날짜 지정 알람은 반복 요일을 선택하지 않으면 날짜가 유지됩니다." else
                "요일을 선택하지 않으면 오늘 선택한 시각에 한 번만 울립니다. 지난 시각은 예약할 수 없습니다.")
            .setMultiChoiceItems(labels.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("취소", null).setPositiveButton("저장") { _, _ ->
                val weekdays = days.filterIndexed { index, _ -> selected[index] }.toSet()
                if (weekdays.isEmpty()) {
                    val date = entry?.date?.takeIf { it.isAfter(LocalDate.now()) }
                        ?: oneOffDateForToday(System.currentTimeMillis(), hour, minute)
                    if (date == null) refreshList("오늘 남은 시각을 선택하세요. 내일로 자동 이월하지 않습니다.")
                    else saveAlarm(entry, hour, minute, weekdays, date)
                } else saveAlarm(entry, hour, minute, weekdays, null)
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
            refreshList("지난 1회성 알람입니다. 새 알람을 추가하세요.")
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
        if (missingRequiredPermissions().isNotEmpty()) {
            guideMissingPermissionsOnce()
            refreshList("알람을 켜려면 필수 권한을 허용하세요.")
            return false
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

    private fun missingRequiredPermissions(): Set<RequiredPermission> = buildSet {
        if (!hasCameraPermission()) add(RequiredPermission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) add(RequiredPermission.NOTIFICATIONS)
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            add(RequiredPermission.EXACT_ALARM)
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
            add(RequiredPermission.FULL_SCREEN)
    }

    private fun guideMissingPermissionsOnce() {
        if (permissionPromptVisible || permissionRequestInProgress || !::alarmList.isInitialized) return
        val missing = missingRequiredPermissions()
        val next = nextPermissionToExplain(missing, promptedPermissions) ?: return
        promptedPermissions.add(next)
        permissionPromptVisible = true
        val missingNames = missing.joinToString(" · ") {
            when (it) {
                RequiredPermission.CAMERA -> "카메라"
                RequiredPermission.NOTIFICATIONS -> "알림"
                RequiredPermission.EXACT_ALARM -> "정확한 알람"
                RequiredPermission.FULL_SCREEN -> "전체 화면"
            }
        }
        val (title, message) = when (next) {
            RequiredPermission.CAMERA -> "카메라 권한" to "양치 완료를 확인할 때 카메라가 필요합니다."
            RequiredPermission.NOTIFICATIONS -> "알림 권한" to "알람이 울릴 때 알림을 표시하려면 허용해 주세요."
            RequiredPermission.EXACT_ALARM -> "정확한 알람 권한" to "설정에서 정확한 알람을 허용해야 선택한 시각에 예약할 수 있습니다."
            RequiredPermission.FULL_SCREEN -> "전체 화면 알림 권한" to "잠금 화면 위에 알람 화면을 표시하려면 허용해 주세요."
        }
        AlertDialog.Builder(this).setTitle(title).setMessage(
            "$message\n\n아직 필요한 권한: $missingNames\n나중에 눌러도 목록 아래 권한 상태에서 설정할 수 있습니다.")
            .setNegativeButton("나중에") { _, _ -> permissionPromptVisible = false }
            .setPositiveButton(if (next == RequiredPermission.CAMERA || next == RequiredPermission.NOTIFICATIONS)
                "권한 허용" else "설정으로 이동") { _, _ ->
                permissionPromptVisible = false
                when (next) {
                    RequiredPermission.CAMERA -> requestRuntimePermission(Manifest.permission.CAMERA)
                    RequiredPermission.NOTIFICATIONS -> requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
                    RequiredPermission.EXACT_ALARM -> openExactAlarmSettings()
                    RequiredPermission.FULL_SCREEN -> openFullScreenSettings()
                }
            }
            .setOnDismissListener { permissionPromptVisible = false }
            .show()
    }

    private fun requestRuntimePermission(permission: String) {
        permissionRequestInProgress = true
        permissionLauncher.launch(arrayOf(permission))
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionRequestInProgress = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun openMissingSystemPermission() {
        if (!hasCameraPermission()) {
            requestRuntimePermission(Manifest.permission.CAMERA); return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS); return
        }
        if (!getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            openExactAlarmSettings(); return
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            openFullScreenSettings(); return
        }
        refreshList("필수 권한이 모두 허용되었습니다.")
    }

    private fun openFullScreenSettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
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
            guideMissingPermissionsOnce()
        }
    }
}
