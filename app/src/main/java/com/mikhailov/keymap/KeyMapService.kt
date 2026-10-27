package com.mikhailov.keymap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.File

/**
 * Filters key events. Keys listed in files/keymap.json are consumed (DOWN and UP) and
 * their action runs on DOWN; every other key passes through untouched. The config is read
 * once, when the service connects.
 *
 * Config format: {"<KEYCODE_NAME or number>": "<pkg>/<activity>" | "mute" | "play-pause-if-idle:<pkg>"}
 *
 * play-pause-if-idle pauses or resumes <pkg> with one press when its media session is playing or
 * paused, it is in front, and it saw no key for CONTROLS_HIDE_MS, so the player's controls are
 * hidden. Otherwise the key passes through. Plex needs two center presses for this: one shows
 * the controls, one pauses. Reading sessions needs MediaAccess allowed as a notification listener.
 */
class KeyMapService : AccessibilityService() {

    private var mapping: Map<Int, String> = emptyMap()
    private val swallowed = HashSet<Int>()
    private var lastKeyAt = 0L

    private val configFile get() = File(filesDir, CONFIG_NAME)

    override fun onServiceConnected() {
        if (!configFile.exists()) configFile.writeText(DEFAULT_CONFIG)
        load()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.i(TAG, "connected, flags=${serviceInfo.flags} caps=${serviceInfo.capabilities} mapping=$mapping")
    }

    private fun load() {
        mapping = try {
            parse(configFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "bad config, using default", e)
            parse(DEFAULT_CONFIG)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val lag = SystemClock.uptimeMillis() - event.eventTime
        if (lag > SLOW_MS) Log.w(TAG, "slow: key $code action=${event.action} reached us after ${lag}ms")
        if (event.action == KeyEvent.ACTION_UP) return swallowed.remove(code) || passThrough(event)
        val idle = event.eventTime - lastKeyAt
        val action = mapping[code] ?: return passThrough(event)
        if (action.startsWith(PLAY_PAUSE_IF_IDLE)) {
            if (event.repeatCount > 0) return passThrough(event)
            val player = idlePlayer(action.removePrefix(PLAY_PAUSE_IF_IDLE), idle) ?: return passThrough(event)
            swallowed.add(code)
            if (player.playbackState?.state == PlaybackState.STATE_PLAYING) {
                Log.i(TAG, "pause ${player.packageName}")
                player.transportControls.pause()
            } else {
                Log.i(TAG, "play ${player.packageName}")
                player.transportControls.play()
            }
            return true
        }
        swallowed.add(code)
        if (event.repeatCount == 0) {
            Log.i(TAG, "key $code scan=${event.scanCode}")
            perform(action)
        }
        return true
    }

    private fun perform(action: String) {
        Log.i(TAG, "action $action")
        try {
            if (action == "mute") {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI
                )
                return
            }
            val cn = ComponentName.unflattenFromString(action)
            val intent = if (cn != null) {
                Intent(Intent.ACTION_MAIN).setComponent(cn)
            } else {
                packageManager.getLeanbackLaunchIntentForPackage(action)
                    ?: packageManager.getLaunchIntentForPackage(action) ?: return
            }
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) {
            Log.e(TAG, "action failed: $action", e)
        }
    }

    // Keys we swallow do not count: the app never saw them, so its controls stayed hidden.
    private fun passThrough(event: KeyEvent): Boolean {
        lastKeyAt = event.eventTime
        return false
    }

    // Cheapest checks first: the active window is a call into the app in front. A paused
    // session can outlive the player screen, so the app must also be in front.
    private fun idlePlayer(pkg: String, idle: Long): MediaController? {
        if (idle <= CONTROLS_HIDE_MS) return null
        val sessions = try {
            (getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(this, MediaAccess::class.java))
        } catch (e: SecurityException) {
            Log.e(TAG, "no media session access, allow MediaAccess as a notification listener")
            return null
        }
        val player = sessions.firstOrNull {
            it.packageName == pkg && it.playbackState?.state in ACTIVE_STATES
        } ?: return null
        return if (rootInActiveWindow?.packageName == pkg) player else null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val TAG = "KeyMap"
        const val CONFIG_NAME = "keymap.json"
        const val SLOW_MS = 100L
        const val PLAY_PAUSE_IF_IDLE = "play-pause-if-idle:"
        // Plex hides the player controls about 4.2 s after the last key press.
        const val CONTROLS_HIDE_MS = 4500L
        val ACTIVE_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)

        // Xiaomi RC (Vendor_2717_Product_32b9.kl): KEY_PRESENTATION 425 -> BUTTON_3,
        // KEY_VIDEOPHONE 416 -> BUTTON_6, KEY_WWW 150 -> BUTTON_5, KEY_MENU 139 -> MENU,
        // KEY_PLAY 207 -> BUTTON_7.
        val DEFAULT_CONFIG = """
            {
              "KEYCODE_BUTTON_3": "org.smarttube.beta/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity",
              "KEYCODE_BUTTON_6": "com.netflix.ninja/.MainActivity",
              "KEYCODE_BUTTON_5": "se.hedekonsult.sparkle/.MainActivity",
              "KEYCODE_MENU": "mute",
              "KEYCODE_BUTTON_7": "com.plexapp.android/tv.plex.app.MainActivity"
            }
        """.trimIndent() + "\n"

        fun parse(text: String): Map<Int, String> {
            val json = JSONObject(text)
            val out = HashMap<Int, String>()
            for (k in json.keys()) {
                val code = k.toIntOrNull() ?: KeyEvent.keyCodeFromString(
                    if (k.startsWith("KEYCODE_")) k else "KEYCODE_$k"
                )
                if (code != KeyEvent.KEYCODE_UNKNOWN) out[code] = json.getString(k)
            }
            return out
        }
    }
}
