# GraceHomeUiState 拆分设计（refactor4 设计稿 —— 实施待定案）

- 日期：2026-10-14
- 状态：**设计稿，未实施**（结论见 §4 建议）
- 前置：refactor1~3 已完成（壁纸效果层 / WidgetController / GraceButtonTransitionState 抽离）

---

## 1. 现状与问题

`GraceHomeUiState` 单一 data class（24 字段 + 4 派生属性）经一个 StateFlow 下发，`_uiState.update` 共 24 处。任一字段变化（如通知快照推送）→ 新 data class 实例 ≠ 旧实例 → StateFlow 发射 → Route 全量重组。

**量化现状**：
- Route 对 `state.settings` 读取 48 处（占绝对主导），folders/apps 各 7 处，weather 4 处，notifications 3 处，其余字段各 1-2 处
- `GraceHomeUiState` 消费方仅 3 个文件：GraceLauncherRoute / GraceFavoritesShim / VM 自身（设置页用的是独立的 LauncherUiState，不受影响）
- 高频发射源：通知快照（桥推送，每次通知变化）、媒体会话（切歌）、itemIcons（图标更新）、apps（应用增删）

## 2. 字段 → 消费面分组

| 组 | 字段 | 变化频率 | 主要消费点 |
|---|---|---|---|
| **settings** | settings（GraceHomeSettings） | 低（设置页编辑时） | Route 48 处 + HomeScreen/AppDrawerScreen 参数 |
| **data（应用数据面）** | apps / favoriteOrder / folders / folderWidgets / popups / savedShortcutApps / itemIcons / graceButtonApp / hiddenAppKeys / workProfiles / workProfileApps / isLoadingApps / appLoadFailed / hasShortcutAccess | 中（应用增删/图标更新） | Route 列表 + HomeScreen/AppDrawerScreen |
| **bridge（桥快照面，高频）** | notifications / homeMedia / hasMediaAccess | **高**（通知/媒体推送） | HomeScreen 行角标 / 弹层附加行 / 媒体行 |
| **agenda（日程天气面）** | events / scheduleStatus / weather | 中低（观察者/刷新） | AgendaSheet / 时钟行 |

## 3. 分阶段实施方案（若定案实施）

**Phase 1 — VM 子流暴露（零行为变化）**：
`_uiState` 保持不动；新增派生流 `bridgeFlow = _uiState.map { BridgeState(it.notifications, it.homeMedia, it.hasMediaAccess) }.distinctUntilChanged()`，agendaFlow/dataFlow 同理。风险零，但消费方迁移前无收益（备案）。

**Phase 2 — 叶子级收集（核心收益，侵入面大）**：
- Route 拆 3 个 collectAsState：主状态（settings+data）、bridgeState、agendaState
- `notifications` / `homeMedia` / `weather` 等参数改为叶子组件（HomeScreen 行 / AgendaSheet / 弹层）**直接 collect 对应子流**，Route 不再中转
- 估测改动点：Route ~20 处 + HomeScreen/AppDrawerScreen 参数签名 ~10 处 + GraceFavoritesShim ~5 处
- 收益：通知风暴/切歌/天气刷新不再触发列表主树重组

**Phase 3 — VM update 点迁移**：24 处 `_uiState.update` 按组拆到对应 MutableStateFlow；`uiState` 聚合属性降级为废弃兼容层后删除。

## 4. 建议（当前不实施的理由）

1. **热路径已治理**：perf 批已落 rowGestures remember / 通知编码移 Default / 时钟节拍组件化 —— 当前无实测卡顿指向 UiState 粒度。
2. **settings 低频**是结构利好：占读取量 76% 的 settings 在桌面使用期不变，data class 的 equals 让未变字段引用稳定，子组件 skippable 性已部分生效。
3. **回归面与收益不成比**：Phase 2 触碰全部视觉路径，装机五轮调试的交互（手势/过渡/抽屉渐入）全在改动半径内，且收益（重组次数）无 profiler 数据支撑。
4. **触发条件（满足其一再做）**：a) profiler 实测桌面重组热点归因到 UiState 粒度；b) 新增高频状态面（如实时天气轮询）放大问题；c) 大版本功能开发顺路重构。

**结论**：设计定稿归档；实施挂起，等 profiler 证据或新功能顺路。PORTING 遗留项从"待办"改记"设计定稿、实施挂起"。
