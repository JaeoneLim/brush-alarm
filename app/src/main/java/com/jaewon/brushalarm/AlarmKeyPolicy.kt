package com.jaewon.brushalarm

import android.view.KeyEvent

fun shouldBlockAlarmKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
    keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
    keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
