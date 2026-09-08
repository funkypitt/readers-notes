package com.freedomfighter.readersnotes.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    /** WebDAV account: server root (kDrive: https://<id>.connect.kdrive.infomaniak.com), folder path, credentials. */
    val server: String = "",
    val folder: String = "Notes",
    val username: String = "",
    val password: String = "",
    val syncOnOpen: Boolean = true
) {
    val configured: Boolean get() = server.isNotBlank()
    /** The folder URL, always ending with "/". */
    val folderUrl: String get() = server.trim().trimEnd('/') + "/" + folder.trim().trim('/').split("/").joinToString("/") { encodeSegment(it) } + "/"
}

fun encodeSegment(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2F", "/")

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        server = sp.getString("server", "") ?: "",
        folder = sp.getString("folder", "Notes") ?: "Notes",
        username = sp.getString("username", "") ?: "",
        password = sp.getString("password", "") ?: "",
        syncOnOpen = sp.getBoolean("sync_on_open", true)
    )
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setAlign(a: Align) = sp.edit().putString("align", a.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setAccount(server: String, folder: String, username: String, password: String) =
        sp.edit().putString("server", server.trim()).putString("folder", folder.trim().ifBlank { "Notes" }).putString("username", username.trim()).putString("password", password).apply()
    fun setSyncOnOpen(v: Boolean) = sp.edit().putBoolean("sync_on_open", v).apply()
    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }
}
