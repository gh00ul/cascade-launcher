package com.gh00ul.cascade.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject

enum class SwipeDownAction { NOTIFICATIONS, SEARCH }

/** Text over the wallpaper: follow the wallpaper's brightness, or force white / dark. */
enum class TextColor { AUTO, LIGHT, DARK }

data class LauncherSettings(
    /** App keys in the order they appear on the home screen. */
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val renames: Map<String, String> = emptyMap(),
    val showIcons: Boolean = true,
    val monochromeIcons: Boolean = false,
    val showNotificationPreviews: Boolean = true,
    val showMediaControls: Boolean = true,
    val textColor: TextColor = TextColor.AUTO,
    val swipeDownAction: SwipeDownAction = SwipeDownAction.NOTIFICATIONS,
    val favoritesSeeded: Boolean = false,
    val notificationPromptDismissed: Boolean = false,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<LauncherSettings> = state.asStateFlow()

    fun update(transform: (LauncherSettings) -> LauncherSettings) = write(state.updateAndGet(transform))

    fun toggleFavorite(key: String) = update {
        it.copy(favorites = if (key in it.favorites) it.favorites - key else it.favorites + key)
    }

    fun setFavorites(keys: List<String>) = update { it.copy(favorites = keys) }

    fun setHidden(key: String, hidden: Boolean) = update {
        it.copy(hidden = if (hidden) it.hidden + key else it.hidden - key)
    }

    fun rename(key: String, label: String?) = update {
        it.copy(renames = if (label.isNullOrBlank()) it.renames - key else it.renames + (key to label.trim()))
    }

    private fun read() = LauncherSettings(
        favorites = sp.getString(FAVORITES, null)?.let(::parseList) ?: emptyList(),
        hidden = sp.getString(HIDDEN, null)?.let(::parseList)?.toSet() ?: emptySet(),
        renames = sp.getString(RENAMES, null)?.let(::parseMap) ?: emptyMap(),
        showIcons = sp.getBoolean(SHOW_ICONS, true),
        monochromeIcons = sp.getBoolean(MONOCHROME, false),
        showNotificationPreviews = sp.getBoolean(PREVIEWS, true),
        showMediaControls = sp.getBoolean(MEDIA, true),
        textColor = sp.getString(TEXT_COLOR, null)?.let { name -> TextColor.entries.firstOrNull { it.name == name } } ?: TextColor.AUTO,
        swipeDownAction = sp.getString(SWIPE_DOWN, null)
            ?.let { name -> SwipeDownAction.entries.firstOrNull { it.name == name } }
            ?: SwipeDownAction.NOTIFICATIONS,
        favoritesSeeded = sp.getBoolean(SEEDED, false),
        notificationPromptDismissed = sp.getBoolean(NOTIFICATION_PROMPT, false),
    )

    private fun write(s: LauncherSettings) {
        sp.edit()
            .putString(FAVORITES, JSONArray(s.favorites).toString())
            .putString(HIDDEN, JSONArray(s.hidden.toList()).toString())
            .putString(RENAMES, JSONObject(s.renames).toString())
            .putBoolean(SHOW_ICONS, s.showIcons)
            .putBoolean(MONOCHROME, s.monochromeIcons)
            .putBoolean(PREVIEWS, s.showNotificationPreviews)
            .putBoolean(MEDIA, s.showMediaControls)
            .putString(TEXT_COLOR, s.textColor.name)
            .putString(SWIPE_DOWN, s.swipeDownAction.name)
            .putBoolean(SEEDED, s.favoritesSeeded)
            .putBoolean(NOTIFICATION_PROMPT, s.notificationPromptDismissed)
            .apply()
    }

    private companion object {
        const val FAVORITES = "favorites"
        const val HIDDEN = "hidden"
        const val RENAMES = "renames"
        const val SHOW_ICONS = "show_icons"
        const val MONOCHROME = "monochrome_icons"
        const val PREVIEWS = "notification_previews"
        const val MEDIA = "media_controls"
        const val TEXT_COLOR = "text_color"
        const val SWIPE_DOWN = "swipe_down"
        const val SEEDED = "favorites_seeded"
        const val NOTIFICATION_PROMPT = "notification_prompt_dismissed"
    }
}

private fun parseList(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    List(array.length()) { array.getString(it) }
}.getOrDefault(emptyList())

private fun parseMap(json: String): Map<String, String> = runCatching {
    val obj = JSONObject(json)
    obj.keys().asSequence().associateWith { obj.getString(it) }
}.getOrDefault(emptyMap())
