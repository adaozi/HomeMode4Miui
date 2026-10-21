/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/LauncherApp.kt
 * P1 移植：逐字拷贝，未改动。（user/privateSpace 等字段在宿主进程内恒为默认值，
 *   保留以维持后续阶段 P4/P6 的模型兼容。）
 */
package com.galaxyrio.gracelauncher.data

import android.content.ComponentName
import android.icu.text.AlphabeticIndex
import android.icu.text.Transliterator
import android.icu.util.ULocale
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import java.text.Normalizer
import java.util.Locale

data class LauncherApp(
    val componentName: ComponentName,
    val label: String,
    val icon: ImageBitmap?,
    val monochromeIcon: ImageBitmap? = null,
    val originalLabel: String = label,
    val iconPackPackage: String? = null,
    val monochromeScale: Float = 1.4f,
    val shortcut: LauncherShortcut? = null,
    /** Describe the original app icon, independently of packs and designer overrides. */
    val isSystemApp: Boolean = false,
    val isAdaptiveIcon: Boolean = false,
    /** Preserve the enabled-pack match when a designer source changes the displayed artwork. */
    val themeIconPackPackage: String? = iconPackPackage,
    val folderId: String? = null,
    /** null preserves the original personal-profile identities. */
    val user: android.os.UserHandle? = null,
    val userSerial: Long? = null,
    val isPrivateSpace: Boolean = false,
    val showPrivateIndicator: Boolean = true,
    /** First installation, not the last update; used by the built-in recent folder. */
    val firstInstallTime: Long = 0L,
    val isLauncherSettings: Boolean = false,
    val isWorkProfile: Boolean = false,
    val showWorkIndicator: Boolean = true,
    /** Per-item icon-display override saved in the designer; null follows the shared setting. */
    val iconDisplay: IconDisplaySettings? = null,
    /** Dominant color of the original app icon, before any pack/design. Null means neutral/unavailable. */
    val originalIconColor: Int? = null,
    /** 上游同步 1.2.0：该文件夹项是否以横向应用堆（App Stack）形式渲染。 */
    val isAppStack: Boolean = false,
) {
    val key: String = folderId?.let { "folder:$it" } ?: shortcut?.key
        ?: userSerial?.let { "profile:$it:${componentName.flattenToString()}" } ?: componentName.flattenToString()
    val packageName: String = componentName.packageName
    val sortKey: String = appSortKey(label)
    val section: String = sectionForSortKey(sortKey)
}

val LauncherAlphabet: List<String> = ('A'..'Z').map(Char::toString) + "#"

private val combiningMarks = Regex("\\p{Mn}+")
private val spacing = Regex("\\s+")
private val sortKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
}

/** One cached key drives both the A–Z section and the order within it. */
@Synchronized
fun appSortKey(label: String): String = sortKeys.getOrPut(label) {
    val normalized = Normalizer.normalize(label.trim(), Normalizer.Form.NFKC)
    val romanized = when {
        normalized.all { it.code < 128 } -> normalized
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> RomanizedLabels.transform(normalized)
        else -> normalized
    }
    val folded = Normalizer.normalize(romanized, Normalizer.Form.NFKD)
        .replace(combiningMarks, "").replace(spacing, "").lowercase(Locale.ROOT)
    // Android 9 has no public Transliterator. Its native Chinese index still
    // supplies pinyin initials; other untransliterated scripts retain a letter
    // section instead of all being dumped into '#'. No hidden API or font table.
    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && folded.isNotEmpty() &&
        Character.UnicodeScript.of(folded.codePointAt(0)) == Character.UnicodeScript.HAN
    ) {
        val bucket = legacyChineseIndex.getBucket(legacyChineseIndex.getBucketIndex(folded))?.label.orEmpty()
        if (bucket.length == 1 && bucket[0] in 'A'..'Z') bucket.lowercase(Locale.ROOT) + folded else folded
    } else folded
}

private val legacyChineseIndex by lazy {
    AlphabeticIndex<Any>(ULocale.SIMPLIFIED_CHINESE).addLabels(ULocale.ENGLISH).buildImmutableIndex()
}

@RequiresApi(Build.VERSION_CODES.Q)
private object RomanizedLabels {
    // Han uses pinyin; kana, Hangul, Cyrillic, Greek, etc. use ICU's script
    // transliterations. Latin-ASCII also handles accents, ligatures and stroke letters.
    private val transliterator = runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    fun transform(label: String): String = transliterator?.transliterate(label) ?: label
}

fun sectionForLabel(label: String): String = sectionForSortKey(appSortKey(label))

private fun sectionForSortKey(key: String): String {
    if (key.isEmpty()) return "#"
    val initial = key.uppercase(Locale.ROOT).codePointAt(0)
    return if (Character.isLetter(initial)) String(Character.toChars(initial)) else "#"
}

/** Stable across refreshes and renames, regardless of the device UI language. */
val LauncherAppOrder: Comparator<LauncherApp> = compareBy<LauncherApp> { it.section == "#" }
    .thenBy { it.section }.thenBy { it.sortKey }.thenBy { it.label }.thenBy { it.key }
