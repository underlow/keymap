package com.mikhailov.keymap

import android.service.notification.NotificationListenerService

/**
 * Does nothing. Being an allowed notification listener is what lets KeyMapService read other
 * apps' media sessions (MediaSessionManager.getActiveSessions). Grant once over adb:
 * cmd notification allow_listener com.mikhailov.keymap/com.mikhailov.keymap.MediaAccess
 */
class MediaAccess : NotificationListenerService()
