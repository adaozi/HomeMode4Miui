/*
 * C 组（C1）降级版许可数据源 —— 原路径 GraceLauncher-src data/licenses/LicensesRepository.kt。
 *
 * 原版数据来自 aboutlibraries 插件在构建期生成的 R.raw.aboutlibraries 依赖清单 JSON
 * （com.mikepenz:aboutlibraries）。本仓构建链未引入该插件，改用静态清单：
 * 与 build.gradle 依赖一一对应、手工核对名称/版本/许可证。新增构建依赖时需同步维护。
 * 许可全文不随附（原版经 licenses URL 在线查看），AppLicense（GPL-3.0 全文）走
 * assets/licenses/GPL-3.0.txt。
 */
package com.galaxyrio.gracelauncher.data.licenses

import android.content.Context

internal data class LicenseLink(val name: String, val url: String?)

internal data class LibraryLicense(
    val id: String,
    val name: String,
    val artifactId: String,
    val version: String?,
    val developers: List<String>,
    val website: String?,
    val licenses: List<LicenseLink>,
) {
    fun matches(query: String): Boolean {
        val term = query.trim()
        return term.isEmpty() || listOfNotNull(name, artifactId, version, website)
            .plus(developers).plus(licenses.map { it.name })
            .any { it.contains(term, ignoreCase = true) }
    }
}

internal class LicensesRepository(context: Context) {

    suspend fun getLibraries(): List<LibraryLicense> = Libraries

    private companion object {
        const val APACHE = "Apache License 2.0"
        fun apache(url: String) = listOf(LicenseLink(APACHE, url))

        val Libraries: List<LibraryLicense> = listOf(
            LibraryLicense("org.jetbrains.kotlin:kotlin-stdlib", "Kotlin Standard Library", "kotlin-stdlib", "2.4.20",
                listOf("JetBrains"), "https://kotlinlang.org/", apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("org.jetbrains.kotlinx:kotlinx-coroutines", "kotlinx.coroutines", "kotlinx-coroutines-core", null,
                listOf("JetBrains"), "https://github.com/Kotlin/kotlinx.coroutines",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.compose", "Jetpack Compose (BOM 2026.09.00)", "compose", "2026.09.00",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack/compose",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.compose.material3:material3", "Compose Material 3", "material3", "1.5.0-alpha26",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack/compose",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.activity:activity-compose", "Activity Compose", "activity-compose", "1.13.0",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.navigation:navigation-compose", "Navigation Compose", "navigation-compose", "2.9.8",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.core:core-ktx", "Core KTX", "core-ktx", "1.19.0",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.lifecycle", "AndroidX Lifecycle", "lifecycle", "2.9.4",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.savedstate:savedstate", "AndroidX Saved State", "savedstate", "1.3.3",
                listOf("The Android Open Source Project"), "https://developer.android.com/jetpack",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("androidx.room:room-runtime", "AndroidX Room", "room-runtime", "2.8.4",
                listOf("The Android Open Source Project"), "https://developer.android.com/training/data-storage/room",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("com.google.devtools.ksp", "Kotlin Symbol Processing API", "symbol-processing-api", null,
                listOf("Google"), "https://github.com/google/ksp",
                apache("https://www.apache.org/licenses/LICENSE-2.0")),
            LibraryLicense("com.materialkolor:material-kolor", "Material Kolor", "material-kolor", "4.1.1",
                listOf("Michael Cook"), "https://github.com/kOMPotik/materialkolor",
                listOf(LicenseLink("MIT License", "https://github.com/kOMPotik/materialkolor/blob/main/LICENSE"))),
            LibraryLicense("com.galaxyrio.gracelauncher", "GraceLauncher", "gracelauncher", "1.1.4",
                listOf("Galaxy-rio"), "https://github.com/Galaxy-rio/GraceLauncher",
                listOf(LicenseLink("GNU General Public License v3.0", "https://www.gnu.org/licenses/gpl-3.0.html"))),
        ).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
