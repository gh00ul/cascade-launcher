package com.gh00ul.cascade.testing

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Process
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import com.gh00ul.cascade.data.AppEntry
import com.gh00ul.cascade.data.IconImage

/** Fake installed apps for tests: no PackageManager or LauncherApps involved. */
object FakeApps {
    /** User 10, like a work profile on a real phone. */
    val workUser: UserHandle get() = UserHandle.getUserHandleForUid(1_000_000)

    /** An app the way AppRepository would list it: key `<package>/<class>#<user serial>`, A–Z section from the label. */
    fun app(label: String, pkg: String, cls: String = "$pkg.Main", work: Boolean = false, originalLabel: String = label): AppEntry {
        val component = ComponentName(pkg, cls)
        val first = label.first().uppercaseChar()
        return AppEntry(
            key = "${component.flattenToString()}#${if (work) 10 else 0}",
            label = label,
            originalLabel = originalLabel,
            component = component,
            user = if (work) workUser else Process.myUserHandle(),
            isWork = work,
            isManagedProfile = work,
            section = if (first in 'A'..'Z') first.toString() else "#",
        )
    }

    private fun pkg(label: String) = "com.example." + label.lowercase().filter { it.isLetterOrDigit() }.let { if (it.first().isDigit()) "game$it" else it }

    /** Label to icon colour. */
    private val catalog = linkedMapOf(
        "2048" to 0xFFEDC22E, "Authenticator" to 0xFF5F6368, "Books" to 0xFF1E88E5, "Browser" to 0xFF0B8043,
        "Calculator" to 0xFF455A64, "Calendar" to 0xFF4285F4, "Camera" to 0xFF37474F, "Clock" to 0xFF3949AB,
        "Contacts" to 0xFF1A73E8, "Docs" to 0xFF4C8BF5, "Files" to 0xFF00897B, "Fitness" to 0xFFE53935,
        "Home" to 0xFFFB8C00, "Keyboard" to 0xFF546E7A, "Lens" to 0xFF8E24AA, "Mail" to 0xFFD93025,
        "Maps" to 0xFF34A853, "Messages" to 0xFF1976D2, "Music" to 0xFFFF5722, "News" to 0xFF5C6BC0,
        "Notes" to 0xFFF9AB00, "Phone" to 0xFF188038, "Photos" to 0xFFEA4335, "Podcasts" to 0xFF7B1FA2,
        "Recorder" to 0xFFC2185B, "Settings" to 0xFF607D8B, "Tasks" to 0xFF1565C0, "Translate" to 0xFF4285F4,
        "Wallet" to 0xFF202124, "Weather" to 0xFF29B6F6,
    )

    val mail = app("Mail", pkg("Mail"))
    val workMail = app("Mail", pkg("Mail"), work = true)
    val messages = app("Messages", pkg("Messages"))
    val music = app("Music", pkg("Music"))
    val phone = app("Phone", pkg("Phone"))
    val camera = app("Camera", pkg("Camera"))
    val photos = app("Photos", pkg("Photos"))

    private val fixed = listOf(mail, workMail, messages, music, phone, camera, photos).associateBy { it.key }

    /** Every app, sorted for the A–Z list: "#" first, then by label (main profile before work). */
    val all: List<AppEntry> = (catalog.keys.map { label -> fixed.values.firstOrNull { it.label == label && !it.isWork } ?: app(label, pkg(label)) } + workMail)
        .sortedWith(compareBy<AppEntry>({ it.section != "#" }, { it.label.lowercase() }, { it.isWork }))

    /** The home screen favorites, top to bottom. */
    val favorites = listOf(phone, messages, mail, camera, photos, music)

    /** A real phone's worth of apps, sorted, where some letters hold many: S has 16, G 12, T 8. No icons. */
    val crowded: List<AppEntry> = listOf(
        "Amazon", "Android Auto", "AR Zone", "Authenticator", "Bixby", "Calculator", "Calendar", "Camera", "Cascade",
        "Chrome", "Clock", "CloudMail", "Contacts", "Discord", "Drive", "Facebook", "Files", "Galaxy Store",
        "Galaxy Wearable", "Gallery", "Game Launcher", "Gemini", "Gmail", "Google", "Google Home", "Google One",
        "Google Play Games", "Google Play Store", "Google TV", "Instagram", "Keep", "Maps", "Meet", "Messages",
        "Messenger", "My Files", "Netflix", "Outlook", "Phone", "Photos", "PodBattery", "Reddit", "Samsung Free",
        "Samsung Health", "Samsung Internet", "Samsung Members", "Samsung Notes", "Samsung Pass", "Samsung Wallet",
        "Secure Folder", "Settings", "Shazam", "Slack", "Smart Switch", "SmartThings", "Snapchat", "Spotify", "Steam",
        "Tasks", "Teams", "Telegram", "Threads", "TikTok", "Tips", "Translate", "Twitch", "Uber", "Venmo", "Verizon", "VLC",
        "Waze", "Wear OS", "Weather", "WhatsApp", "Wikipedia", "X", "Yelp", "YouTube", "YouTube Music", "Zillow", "Zoom",
    ).map { app(it, pkg(it)) }.sortedBy { it.label.lowercase() }

    fun byLabel(label: String, work: Boolean = false) = all.first { it.label == label && it.isWork == work }

    /**
     * A squircle in the app's colour with a white monogram. 168 px covers the XL home size (56 dp) at xxhdpi;
     * smaller sizes scale down. Work apps get a small orange badge, like the system's profile badge.
     */
    fun iconBitmap(label: String, color: Long = catalog[label] ?: 0xFF607D8B, sizePx: Int = 168, work: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = sizePx.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() }
        canvas.drawRoundRect(RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.96f), s * 0.3f, s * 0.3f, paint)
        // A lighter top half, so the icons don't look flat.
        paint.color = 0x22FFFFFF
        canvas.drawRoundRect(RectF(s * 0.04f, s * 0.04f, s * 0.96f, s * 0.5f), s * 0.3f, s * 0.3f, paint)
        val monogram = if (label.first().isDigit()) label.take(2) else label.take(1).uppercase()
        paint.apply {
            this.color = 0xFFFFFFFF.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textSize = s * if (monogram.length > 1) 0.36f else 0.48f
            textAlign = Paint.Align.CENTER
        }
        val baseline = s / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(monogram, s / 2f, baseline, paint)
        if (work) {
            paint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(s * 0.8f, s * 0.8f, s * 0.2f, paint)
            paint.color = 0xFFF57C00.toInt()
            canvas.drawCircle(s * 0.8f, s * 0.8f, s * 0.16f, paint)
        }
        return bitmap
    }

    /** Icons for every app in [all], keyed like AppRepository's icon map. */
    fun icons(sizePx: Int = 168): Map<String, IconImage> =
        all.associate { it.key to IconImage(iconBitmap(it.label, sizePx = sizePx, work = it.isWork).asImageBitmap(), isGlyph = false) }
}
