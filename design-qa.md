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
