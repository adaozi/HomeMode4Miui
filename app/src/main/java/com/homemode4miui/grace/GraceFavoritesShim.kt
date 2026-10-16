/*
 * hotfix8 —— 宿主进程"编辑收藏"适配层（C 组 LauncherUiState.kt 头注释预留的
 * GraceFavoritesShim）。原版编辑收藏页（overlays/FavoritesScreen）以
 * LauncherUiState/LauncherActions 聚合表面为入参，宿主桌面（GraceHomeViewModel +
 * GracePopupActions）没有这对模型 —— 本 shim 从 GraceHomeUiState 投影出仅覆盖
 * 收藏编辑面的最小 LauncherUiState，动作面直连宿主 VM（FavoritesStore 同进程）。
 *
 * folders 阶段升级：
 *   - folders 投影 → favoriteItems 含 inFavorites 文件夹项（原版语义），
 *     FavoriteFoldersScreen 文件夹收藏开关直连宿主 Room（setFolderFavorites）
 *   - 添加快捷方式面接通：shortcuts/cachedShortcuts 直连宿主 ShortcutRepository
 *     （宿主持 HOME 角色，hasShortcutHostPermission 成立，原版 DefaultLauncherRequired
 *     降级解除）；ShortcutPickerScreen 的收藏开关经 toggle 落 FavoritesStore
 *
 * 仍降级（登记于 PORTING_GraceLauncher.md）：
 *   - settings 用默认 LauncherSettings()（编辑页仅消费 privateSpace 展示位，默认关闭）；
 *   - shortcut 成员不做图标装饰（图标设计器对快捷方式的覆盖随后续阶段）。
 */
package com.homemode4miui.grace

import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState

internal object GraceFavoritesShim {

    /** GraceHomeUiState → 收藏编辑面最小 LauncherUiState（apps/favorites/folders 全量投影）。 */
    fun uiState(state: GraceHomeUiState): LauncherUiState = LauncherUiState(
        apps = state.apps,
        folders = state.folders,
        favoriteKeys = state.favoriteOrder.toSet(),
        favoriteOrder = state.favoriteOrder,
        isLoadingApps = state.isLoadingApps,
        appLoadFailed = state.appLoadFailed,
        isLoadingSettings = false,
        settingsLoadFailed = false,
        hasShortcutAccess = state.hasShortcutAccess,
    )

    /**
     * 收藏编辑动作面：reorder/toggle 直连宿主 VM；快捷方式查询直连 ShortcutRepository
     * （宿主 HOME 角色）；文件夹收藏开关直连宿主 Room。
     */
    fun actions(viewModel: GraceHomeViewModel): LauncherActions = LauncherActions(
        shortcuts = viewModel::loadShortcuts,
        cachedShortcuts = viewModel::cachedShortcuts,
        setFolderFavorites = viewModel::setFolderFavorites,
    )
}
