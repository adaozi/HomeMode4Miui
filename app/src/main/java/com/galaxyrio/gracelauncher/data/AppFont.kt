/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/LauncherSettings.kt
 *   内的 AppFont 枚举。P1 抽取为独立文件（仅随 LauncherFonts 使用）；
 *   LauncherSettings 本体 P3 随 Room 一并搬入。
 */
package com.galaxyrio.gracelauncher.data

enum class AppFont(val id: String?) {
    Default(null), System("system"), NotoSans("noto_sans"), Sacramento("sacramento"),
    Bokor("bokor"), Plaster("plaster"), Monoton("monoton"), LuckiestGuy("luckiest_guy"),
}
