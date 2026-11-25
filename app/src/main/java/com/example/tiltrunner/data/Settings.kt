package com.example.tiltrunner.data

import android.content.Context

/** Highscore und Sprung-Empfindlichkeit, gespeichert in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("tiltrunner", Context.MODE_PRIVATE)

    var highScore: Int
        get() = prefs.getInt(KEY_HIGH_SCORE, 0)
        private set(value) = prefs.edit().putInt(KEY_HIGH_SCORE, value).apply()

    var jumpThreshold: Float
        get() = prefs.getFloat(KEY_JUMP_THRESHOLD, DEFAULT_JUMP_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_JUMP_THRESHOLD, value).apply()

    /** Speichert den Wert nur, wenn er den bisherigen Rekord übertrifft. */
    fun saveHighScore(score: Int) {
        if (score > highScore) highScore = score
    }

    companion object {
        const val DEFAULT_JUMP_THRESHOLD = 8f
        private const val KEY_HIGH_SCORE = "high_score"
        private const val KEY_JUMP_THRESHOLD = "jump_threshold"
    }
}
