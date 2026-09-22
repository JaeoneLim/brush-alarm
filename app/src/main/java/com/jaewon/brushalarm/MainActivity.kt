package com.jaewon.brushalarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var picker: TimePicker

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmRingingService.createChannel(this)
        setContentView(buildContent())
        requestRuntimePermissions()
        updatePermissionStatus()
    }

    private fun buildContent(): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = "양치 알람"
                textSize = 30f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "카메라가 실제 양치 동작을 30초 동안 확인해야 알람이 멈춥니다.\n앱 내부 뒤로가기는 차단되지만 Android 시스템의 강제 종료까지 막을 수는 없습니다."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, padding / 2, 0, padding / 2)
            })
            picker = TimePicker(context).apply {
                setIs24HourView(true)
                val soon = Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }
                hour = soon.get(Calendar.HOUR_OF_DAY)
                minute = soon.get(Calendar.MINUTE)
            }
            addView(picker)
            addView(Button(context).apply {
                text = "이 시간으로 알람 설정"
                setOnClickListener { scheduleSelectedAlarm() }
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
                setPadding(0, padding / 2, 0, 0)
            }
            addView(status, matchWidth())
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun scheduleSelectedAlarm() {
        if (!hasCameraPermission()) {
            requestRuntimePermissions()
            status.text = "카메라 권한을 먼저 허용해 주세요."
            return
        }
        val trigger = nextAlarmMillis(System.currentTimeMillis(), picker.hour, picker.minute)
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) {
            openExactAlarmSettings()
            status.text = "정확한 알람 권한을 허용한 뒤 다시 설정해 주세요."
            return
        }
        val operation = PendingIntent.getBroadcast(
            this,
            1001,
            Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showIntent = PendingIntent.getActivity(
            this,
            1002,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, showIntent), operation)
        status.text = "설정됨: ${DateFormat.getDateTimeInstance().format(Date(trigger))}"
    }

    private fun triggerTestAlarm() {
        if (!hasCameraPermission()) {
            requestRuntimePermissions()
            status.text = "카메라 권한을 먼저 허용해 주세요."
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, AlarmRingingService::class.java))
        startActivity(Intent(this, AlarmActivity::class.java))
    }

    private fun updatePermissionStatus() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val notificationManager = getSystemService(NotificationManager::class.java)
        val fullScreen = Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()
        status.text = buildString {
            append(if (hasCameraPermission()) "카메라 ✓" else "카메라 ✗")
            append(" · ")
            append(if (alarmManager.canScheduleExactAlarms()) "정확한 알람 ✓" else "정확한 알람 ✗")
            append(" · ")
            append(if (fullScreen) "전체 화면 ✓" else "전체 화면 ✗")
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
        updatePermissionStatus()
    }

    private fun openExactAlarmSettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updatePermissionStatus()
    }
}
