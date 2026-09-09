package com.example.opendash.media

import android.app.PendingIntent
import android.graphics.Bitmap

data class NowPlaying(
    val title: String,
    val album: String,
    val artist: String,
    val art: Bitmap? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canSeek: Boolean = false,
)

data class IncomingCall(
    val caller: String,
    val incoming: Boolean = true,
    val answerIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
    /** Contact/avatar bitmap extracted from the system call notification when available. */
    val photo: Bitmap? = null,
)
