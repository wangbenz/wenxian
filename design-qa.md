# Product Design QA — 月下宣纸

- Source visual truth: `/Users/ben/.codex/generated_images/019f9715-313f-7790-a6d4-54af2cb4b645/exec-1fe322ad-d4fb-4cc4-ae18-31317b2071a1.png`
- Implementation screenshot: `qa/implementation-night-character-mobile.png`
- Normalized comparison: `qa/night-character-comparison.jpg`
- Viewport: 390 × 844 CSS px; browser capture device scale factor 0.75.
- Pixel dimensions: source 853 × 1844; raw implementation 520 × 1125 from the in-app browser capture provider; both comparison panes normalized to 390 × 844.
- State: signed-in existing save, character record, `data-theme="night"`.

**Findings**

- No remaining P0/P1/P2 issue. The implementation preserves the selected visual's warm-charcoal xuan-paper ground, ivory display typography, celadon state accents, fine low-contrast rules, restrained red details and square translucent record cards.
- Fonts and typography: the existing Song/Kaiti stack remains consistent with the light product and the selected mock. Titles, metadata and body copy retain distinct optical hierarchy; long choice labels and descriptions wrap instead of truncating.
- Spacing and layout rhythm: mobile content stays inside the 390 px viewport without horizontal overflow; persistent header and bottom navigation remain stable; record cards use the same compact vertical cadence as the reference.
- Colors and visual tokens: the night token set is independently defined rather than inverted. Foreground/background contrast remains readable while avoiding pure white and pure black.
- Image quality and asset fidelity: `xuan-paper-night.png` is a project-owned raster asset generated for the selected moonlit direction. It retains a quiet center for reading and places moon/mountain/alley detail at the edge; no placeholder illustration or code-drawn substitute remains.
- Copy and content: theme labels, read-only record terminology and natural-language action hint match the actual game behavior.

**Focused region evidence**

- Character abilities and inventory were inspected in the rendered DOM: both contain zero action buttons, confirming the secondary page is view-only.
- Adventure choice cards were inspected at mobile width: complete label and hint text wrap naturally, with zero horizontal-overflow elements.
- Composer focus was tested: bottom navigation is removed while focused, the composer uses the visual-viewport keyboard offset, and the draft is retained until a successful action response.

**Comparison history**

1. Initial browser pass found a P2 light-scene strip on the night World page and a P1 focus callback error caused by reading a released React event target.
2. Fixed the World scene art to use the moonlit raster asset and captured the textarea node before the delayed `scrollIntoView` callback.
3. Post-fix browser pass: 390 × 844 and 900 × 900 CSS layouts reported no horizontal overflow; a fresh tab reported no console errors and no native JavaScript dialog.

**Primary interactions tested**

- Adventure/character/world tab navigation.
- System/paper/moon-night theme controls and persisted moon-night state.
- Character and inventory read-only behavior.
- Mobile composer focus, draft entry, navigation hiding and keyboard-offset positioning.
- Wide responsive layout.

**Follow-up Polish**

- P3: once a dedicated Chinese webfont is selected for production licensing/performance, replace platform-dependent Kaiti/Song fallbacks for even closer cross-device typography.

final result: passed

## 追加回归：夜间可读性与移动输入

状态：**通过**

对比依据：用户提供的 iPhone 夜间模式截图，与本地 `390 × 844` 移动视口构建截图。浏览器截图按设备像素密度输出，已归一化后并排检查：[对比图](./design-qa-comparison.jpg)。

## 检查结果

- 四个主页面标题、剧情正文、卡片正文和底部导航在月夜背景上恢复清晰层级。
- 背景宣纸图降噪加深，正文区域增加独立深色阅读层，不再与山水纹理争夺对比度。
- 禁用状态的「行动」按钮仍可识别，但不会误导为可点击状态。
- 邮箱和邀请码改用系统无衬线字体，数字、`@` 与英文域名不再使用楷体造型。
- 移动输入采用 `visualViewport` 计算键盘占用空间；输入框聚焦时滚入可视区域，首页行动框随键盘上移。
- `390 × 844` 视口下未发现标题裁切、正文横向溢出或操作区遮挡。

## 自动验证

- `npm run build`：通过
- `npm run test:sites`：4/4 通过
- `git diff --check`：通过

## 全页面月夜兼容性回归

状态：**通过**

证据目录：[`qa/night-audit-current`](./qa/night-audit-current)。本轮先记录线上问题态，再使用本地最新构建复核修复态。

1. 命卷创建、命格与天赋：世界简介、选项说明、禁用按钮恢复可读；选中态仍以青绿区分。
2. 历练：首页剧情、信号提示、选项卡和行动框保持深色阅读层，不受通用段落灰色影响。
3. 角色：人物摘要、天赋、行囊和已登场角色正文统一继承月夜正文色。
4. 纪事：统一世界时说明与事件摘要不再落回浅色主题的深灰文字。
5. 世界：世界说明、规则、线索卡、外观切换和存档操作均使用月夜表面色。
6. 弹窗与终局：取消按钮、存档提示、死亡统计和转世操作不再出现亮色底与低对比文字。
7. 加载与输入：命卷加载遮罩、输入框、占位文字和禁用主按钮均保留可辨识层级。

实现约束：游戏运行区内普通段落默认继承当前主题；所有月夜兼容覆盖集中放在组件规则之后，避免后声明的浅色组件样式反向覆盖主题。
