package com.jaewon.brushalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var picker: TimePicker
    private lateinit var enabledSwitch: Switch
    private val weekdayButtons = linkedMapOf<DayOfWeek, ToggleButton>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmRingingService.createChannel(this)
        setContentView(buildContent())
        requestRuntimePermissions()
        renderStatus()
    }

    private fun buildContent(): View {
        val stored = AlarmPreferences(this).load()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "양치 알람"
                textSize = 30f
                gravity = Gravity.CENTER
            }, matchWidth())
            addView(TextView(context).apply {
                text = "선택한 요일의 지정 시각마다 알람이 울립니다. 카메라가 실제 양치 동작을 30초 동안 확인해야 멈춥니다.\n앱 내부 뒤로가기는 차단되지만 Android 시스템의 강제 종료까지 막을 수는 없습니다."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, padding / 2)
            })
            picker = TimePicker(context).apply {
                setIs24HourView(true)
                hour = stored.schedule.hour
                minute = stored.schedule.minute
            }
            addView(picker)
            addView(TextView(context).apply {
                text = "반복 요일"
                textSize = 18f
                gravity = Gravity.CENTER
            }, matchWidth())
            addView(weekdayRow(stored.schedule.weekdays, listOf(
                DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화",
                DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
            )), matchWidth())
            addView(weekdayRow(stored.schedule.weekdays, listOf(
                DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토",
                DayOfWeek.SUNDAY to "일",
            )), matchWidth())
            enabledSwitch = Switch(context).apply {
                text = "반복 알람 사용"
                isChecked = stored.schedule.enabled
                gravity = Gravity.CENTER
            }
            addView(enabledSwitch, matchWidth())
            addView(Button(context).apply {
                text = "일정 저장 및 적용"
                setOnClickListener { applySchedule() }
            }, matchWidth())
            addView(Button(context).apply {
                text = "다음 알람 1회 건너뛰기"
                setOnClickListener { skipNextOnce() }
            }, matchWidth())
            addView(Button(context).apply {
                text = "지금 알람 테스트"
                setOnClickListener { triggerTestAlarm() }
            }, matchWidth())
            addView(Button(context).apply {
                text = "필수 시스템 권한 확인"
                setOnClickListener { openMissingSystemPermission() }
            }, matchWidth())
            status = TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, padding)
            }
            addView(status, matchWidth())
        }
        return ScrollView(this).apply { addView(content) }
    }

    private fun weekdayRow(
        selected: Set<DayOfWeek>,
        days: List<Pair<DayOfWeek, String>>,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        days.forEach { (day, label) ->
            val button = ToggleButton(context).apply {
                textOn = label
                textOff = label
                text = label
                isChecked = day in selected
            }
            weekdayButtons[day] = button
            addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun selectedWeekdays(): Set<DayOfWeek> = weekdayButtons
        .filterValues { it.isChecked }
        .keys
        .toSet()

    private fun selectedSchedule() = AlarmSchedule(
        hour = picker.hour,
        minute = picker.minute,
        weekdays = selectedWeekdays(),
        enabled = enabledSwitch.isChecked,
    )

    private fun applySchedule() {
        val schedule = selectedSchedule()
        val preferences = AlarmPreferences(this)
        if (!schedule.enabled) {
            preferences.saveSchedule(schedule)
            preferences.saveRuntime(null, null)
            RecurringAlarmScheduler.cancel(this)
            renderStatus("반복 알람을 사용 중지했습니다.")
            return
        }
        if (schedule.weekdays.isEmpty()) {
            renderStatus("요일을 하나 이상 선택해 주세요. 일정은 저장되지 않았습니다.")
            return
        }
        if (!hasCameraPermission()) {
            requestRuntimePermissions()
            renderStatus("카메라 권한을 먼저 허용해 주세요.")
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            openExactAlarmSettings()
            renderStatus("정확한 알람 권한을 허용한 뒤 다시 적용해 주세요.")
            return
        }

        preferences.saveSchedule(schedule)
        val plan = planNextAlarm(schedule, System.currentTimeMillis(), skippedAtMillis = null)!!
        val scheduled = RecurringAlarmScheduler.schedule(this, plan.nextAtMillis)
        preferences.saveRuntime(if (scheduled) plan.nextAtMillis else null, null)
        renderStatus(if (scheduled) "반복 일정을 저장했습니다." else "정확한 알람을 예약하지 못했습니다.")
    }

    private fun skipNextOnce() {
        val preferences = AlarmPreferences(this)
        val state = preferences.load()
        if (!state.schedule.enabled) {
            renderStatus("반복 알람이 꺼져 있어 건너뛸 일정이 없습니다.")
            return
        }
        val now = System.currentTimeMillis()
        if (!canSkipNextOnce(state.skippedAtMillis, now)) {
            renderStatus("이미 다음 알람 1회를 건너뛴 상태입니다. 그 시각이 지난 뒤 다시 사용할 수 있습니다.")
            return
        }
        val skipped = state.scheduledAtMillis
        if (skipped == null || skipped <= now) {
            renderStatus("예약된 다음 알람이 없어 건너뛸 수 없습니다. 일정을 다시 적용해 주세요.")
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            openExactAlarmSettings()
            renderStatus("정확한 알람 권한이 없어 다음 알람을 바꿀 수 없습니다.")
            return
        }
        val following = nextSelectedOccurrenceMillis(
            nowMillis = skipped,
            hour = state.schedule.hour,
            minute = state.schedule.minute,
            weekdays = state.schedule.weekdays,
        )
        val scheduled = RecurringAlarmScheduler.schedule(this, following)
        if (scheduled) {
            val skippedLocalDate = Instant.ofEpochMilli(skipped)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            preferences.saveRuntime(following, skipped, skippedLocalDate)
            renderStatus("${format(skipped)} 알람을 1회 건너뛰고 ${format(following)}로 변경했습니다.")
        } else {
            renderStatus("다음 알람을 변경하지 못했습니다.")
        }
    }

    private fun triggerTestAlarm() {
        if (!hasCameraPermission()) {
            requestRuntimePermissions()
            renderStatus("카메라 권한을 먼저 허용해 주세요.")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, AlarmRingingService::class.java))
        startActivity(Intent(this, AlarmActivity::class.java))
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun renderStatus(message: String? = null) {
        if (!::status.isInitialized) return
        val state = AlarmPreferences(this).load()
        val alarmManager = getSystemService(AlarmManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
        status.text = buildString {
            if (message != null) append(message).append("\n")
            append(if (hasCameraPermission()) "카메라 ✓" else "카메라 ✗")
            append(" · ")
            append(if (alarmManager.canScheduleExactAlarms()) "정확한 알람 ✓" else "정확한 알람 ✗")
            append(" · ")
            append(if (fullScreen) "전체 화면 ✓" else "전체 화면 ✗")
            append("\n")
            if (!state.schedule.enabled) {
                append("반복 알람: 사용 안 함")
            } else if (state.scheduledAtMillis == null) {
                append("반복 알람: 사용 중 · 예약 없음")
            } else {
                state.skippedAtMillis?.let { append("건너뜀: ${format(it)}\n") }
                append("다음 알람: ${format(state.scheduledAtMillis)}")
            }
        }
    }

    private fun openMissingSystemPermission() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            openExactAlarmSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            return
        }
        requestRuntimePermissions()
        renderStatus()
    }

    private fun openExactAlarmSettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun format(millis: Long): String =
        DateFormat.getDateTimeInstance().format(Date(millis))

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && hasCameraPermission()) {
            val state = AlarmPreferences(this).load()
            val alarmManager = getSystemService(AlarmManager::class.java)
            val now = System.currentTimeMillis()
            if (state.schedule.enabled && state.schedule.weekdays.isNotEmpty() &&
                alarmManager.canScheduleExactAlarms()
            ) {
                when (alarmResumeDecision(state.scheduledAtMillis, now)) {
                    AlarmResumeDecision.RESCHEDULE ->
                        RecurringAlarmScheduler.rescheduleStored(this, now)
                    AlarmResumeDecision.DELIVER_DUE -> sendBroadcast(
                        Intent(this, AlarmReceiver::class.java)
                            .setAction(RecurringAlarmScheduler.ACTION_RECURRING_ALARM)
                            .putExtra(
                                RecurringAlarmScheduler.EXTRA_SCHEDULED_AT,
                                requireNotNull(state.scheduledAtMillis),
                            ),
                    )
                }
            }
        }
        renderStatus()
    }
}
