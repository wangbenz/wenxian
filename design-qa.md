# Design QA

## Evidence

- Source visual truth: `/Users/ben/.codex/generated_images/019f9715-313f-7790-a6d4-54af2cb4b645/call_czDdWnC3fO2vpgwjbvaO0uMv.png`
- Final implementation screenshot: `/Users/ben/Documents/修仙世界游戏demo/h5-demo/implementation-mobile-screen-final.png`
- Final side-by-side comparison: `/Users/ben/Documents/修仙世界游戏demo/h5-demo/design-comparison-final.png`
- Browser-rendered full preview: `/Users/ben/Documents/修仙世界游戏demo/h5-demo/implementation-full-v4.png`
- Legacy comparison viewport: `393 × 852` CSS pixels. The current runtime is a responsive H5 and no longer renders inside a device frame.
- Current responsive checks: narrow viewport and wide viewport; page shell fills the viewport, has no horizontal overflow, and readable content is capped at `760px` on wide screens.
- State: new save, `赌坊后巷`, keyboard closed, `历练` selected.

## Full-view Comparison Evidence

The final comparison shows the same core composition as the selected source: warm xuan-paper background, ink-wash alley on the right, compact title/time header, scene heading, literary narrative, two-tone consequence strip, three stacked actions, free-action composer, and four-item bottom navigation.

The selected visual hierarchy is now rendered directly into the browser viewport. There is no iPhone bezel, camera cutout, simulated status bar, device picker, or simulated keyboard.

## Required Fidelity Surfaces

- Fonts and typography: Chinese display text uses local Kaiti fallbacks and body text uses Songti fallbacks. Hierarchy, wrapping, and line height match the literary source. The exact brush font differs slightly because the source lettering is image-generated; this is acceptable P3 drift.
- Spacing and layout rhythm: final capture keeps all three decisions fully visible above the fixed composer. Header, narrative, consequence strip, choices, composer, and navigation remain clearly separated.
- Colors and visual tokens: warm ivory paper, celadon actions, gray ink, and cinnabar danger states match the selected direction with sufficient contrast.
- Image quality and asset fidelity: the visible paper and alley are a dedicated generated raster asset at `852 × 1846`; no placeholder, CSS illustration, handcrafted SVG, emoji, or screenshot-stretch substitute is used.
- Copy and content: scene title, narrative, consequences, action labels, composer placeholder, and navigation match the selected source. Short action hints are an intentional product addition for decision clarity.

## Focused Region Comparison

A separate focused crop was not needed: the normalized `393 × 852` side-by-side board keeps header, narrative, all action labels, composer, and navigation readable in a single view. The action-list region was nevertheless checked explicitly for obstruction after the first pass.

## Comparison History

### Pass 1 — blocked

- [P1] The third action was partially hidden behind the fixed composer.
- [P2] The ink-wash alley was too dark and competed with narrative text.

Fixes made:

- Reduced header and vertical section heights, tightened narrative rhythm, and lowered action-card height so all three decisions fit above the composer.
- Reduced background art opacity from `0.80` to `0.62`.
- Added scroll remounting per tab/turn and keyboard blur handling so the initial state returns to the top with the keyboard closed.

Post-fix evidence: `implementation-full-v4.png`, `implementation-mobile-screen-final.png`, and `design-comparison-final.png`.

### Pass 2 — passed as visual prototype

No actionable P0/P1/P2 visual mismatch remained, but the page was still embedded in a phone mockup.

### Pass 3 — responsive H5 correction

- Removed `MobileRuntime` / `PhoneFrame` from the app entry.
- Replaced simulated scrolling and text input with native browser behavior.
- Added safe-area handling, `100dvh` viewport sizing, mobile full-width layout, and a centered `760px` wide-screen content column.
- Verified the DOM contains zero phone bezel, camera, or device-picker elements.
- Verified focusing the free-action input hides the bottom navigation and produces no console errors.

## Primary Interactions Tested

- Clicked `请求赊药`; verified scene changed to `一纸药约` and save synchronization toast appeared.
- Opened `角色`; verified 许百川’s state changed to `等待你的答复`.
- Opened `纪事`; verified `以命赊药` appeared on the unified timeline.
- Submitted free action `先回家照看妹妹`; verified scene changed to `西屋灯冷`.
- Opened `世界` and used `重开这一局`; verified the new save returned to `赌坊后巷`.
- Browser console warnings/errors checked: none.

## Follow-up Polish

- [P3] A licensed or custom brush-calligraphy font could match the generated title more exactly.

final result: passed as responsive H5
