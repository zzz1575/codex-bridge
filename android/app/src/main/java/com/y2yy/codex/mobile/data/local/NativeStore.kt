package com.y2yy.codex.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.y2yy.codex.mobile.security.EncryptedVault
import com.y2yy.codex.mobile.data.ServiceTiers
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.nativePreferences by preferencesDataStore(
    name = "native_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Page preferences and bounded, encrypted JSON snapshots for immediate offline rendering. */
class NativeStore internal constructor(
    context: Context,
    private val database: NativeDatabase,
    private val preferences: DataStore<Preferences>
) {
    constructor(context: Context) : this(
        context,
        NativeDatabase.get(context),
        context.applicationContext.nativePreferences
    )

    private val vault = EncryptedVault(context)
    private val preferenceValues = preferences.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val lastSession: Flow<String> = preferenceValues
        .map { it[LAST_SESSION].orEmpty() }
        .distinctUntilChanged()

    val darkTheme: Flow<Boolean> = preferenceValues
        .map { it[DARK_THEME] ?: false }
        .distinctUntilChanged()

    /** Opening history must never overwrite this new-session preference. */
    val preferredServiceTier: Flow<String> = preferenceValues
        .map { ServiceTiers.normalized(it[PREFERRED_SERVICE_TIER]) }
        .distinctUntilChanged()

    suspend fun setLastSession(id: String) {
        preferences.edit { it[LAST_SESSION] = id }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        preferences.edit { it[DARK_THEME] = dark }
    }

    suspend fun setPreferredServiceTier(tier: String) {
        val valid = ServiceTiers.requireValid(tier)
        preferences.edit { it[PREFERRED_SERVICE_TIER] = valid }
    }

    suspend fun cache(key: String, json: String) = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "Cache key must not be blank" }
        database.cache().save(
            CacheEntry(key, vault.encrypt("cache:$key", json), System.currentTimeMillis())
        )
    }

    suspend fun cached(key: String): String? = withContext(Dispatchers.IO) {
        val entry = database.cache().find(key) ?: return@withContext null
        vault.decrypt("cache:$key", entry.encryptedPayload).also {
            if (it == null) database.cache().delete(key)
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        database.cache().clear()
    }

    companion object {
        private val LAST_SESSION = stringPreferencesKey("last_session")
        private val DARK_THEME = booleanPreferencesKey("dark_theme")
        private val PREFERRED_SERVICE_TIER = stringPreferencesKey("preferred_service_tier")
    }
}
