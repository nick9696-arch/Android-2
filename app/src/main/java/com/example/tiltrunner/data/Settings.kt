package com.example.tiltrunner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tiltrunner")

class Settings(private val context: Context) {
    private val highScoreKey = intPreferencesKey("high_score")
    private val jumpThresholdKey = floatPreferencesKey("jump_threshold")

    val highScore: Flow<Int> = context.dataStore.data.map { it[highScoreKey] ?: 0 }
    val jumpThreshold: Flow<Float> = context.dataStore.data.map { it[jumpThresholdKey] ?: DEFAULT_JUMP_THRESHOLD }

    suspend fun saveHighScore(score: Int) = context.dataStore.edit { prefs ->
        if (score > (prefs[highScoreKey] ?: 0)) prefs[highScoreKey] = score
    }

    suspend fun saveJumpThreshold(value: Float) = context.dataStore.edit { it[jumpThresholdKey] = value }

    companion object {
        const val DEFAULT_JUMP_THRESHOLD = 8f
    }
}
