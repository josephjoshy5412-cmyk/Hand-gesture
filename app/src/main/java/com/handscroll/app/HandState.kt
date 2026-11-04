package com.handscroll.app

import android.content.Context

/** Shared live state between the tracking service and the UI. */
object HandState {
    @Volatile var running = false
    @Volatile var handFound = false
    @Volatile var fist = false
    @Volatile var error: String? = null
    @Volatile var lastAction = "-"
}

object Prefs {
    fun sp(c: Context) = c.getSharedPreferences("handscroll", Context.MODE_PRIVATE)
    /** Fraction of the camera frame height the hand must travel to count as a swipe. */
    fun threshold(c: Context) = sp(c).getFloat("threshold", 0.25f)
    fun onlyInstagram(c: Context) = sp(c).getBoolean("only_ig", true)
}
