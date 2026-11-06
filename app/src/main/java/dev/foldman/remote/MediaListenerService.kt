package dev.foldman.remote

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself: Android only lets apps with an enabled notification listener read other
 * apps' media sessions, which is how Foldman follows YT Music, Apple Music and Spotify.
 */
class MediaListenerService : NotificationListenerService()
