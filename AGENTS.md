# Responsive H5 Game Agent Guide

## Product Contract

- This deliverable is a real responsive H5 game, not a phone mockup.
- Render directly into the browser viewport. Do not add `PhoneFrame`, `MobileRuntime`, an iPhone/Android bezel, a simulated status bar, a device picker, or a simulated keyboard.
- Mobile browsers are the primary target. Use `100dvh`, native text inputs, and CSS safe-area environment variables.
- On wider screens, keep the paper background full-viewport and constrain the readable game content to a centered column.
- Selected visual direction: `墨卷纪事` — warm xuan-paper, restrained ink wash, celadon accents, cinnabar risk states, and literary Chinese typography.
- The core loop must support both quick choices and free-text actions.
- A single world timeline is authoritative. Every named character has an independent record and evolves against the same timeline.
- Keep product documentation compact: one game-design document and one Agent/data architecture document.

## Editing Boundary

- Build game UI in `frontend/src/Prototype.tsx` and `frontend/src/prototype.css`.
- `frontend/src/App.tsx` is the deliberately minimal H5 entry and should render `Prototype` directly.
- Treat `frontend/src/main.tsx`, `frontend/src/styles.css`, `frontend/vite.config.ts`, `frontend/worker/index.js`, and `frontend/scripts/prepare-sites-build.mjs` as runtime files. Edit them only when the requested runtime or deployment behavior requires it.
- The old `frontend/src/mobile/` package and device assets remain only as unused template material; do not reconnect them to the app.
- The authoritative game rules live in `backend/src/main/java/com/wenxian/game/`. Do not add a second game engine to the frontend.
- If an intentional runtime change modifies a locked file, run `npm run update:runtime-lock`, then `npm run check:runtime`.

## Interaction Rules

- Keep the scene content vertically scrollable while the action composer and primary navigation remain fixed.
- Use browser-native focus and keyboard behavior. When the composer is focused, hide the bottom navigation so it does not compete with the soft keyboard.
- Every control must remain keyboard accessible and have an accessible name.
- Preserve server persistence with a read-only browser cache when offline. Never run a local rules Agent in place of DeepSeek.

## Verification

- Run `mvn test` in `backend`, then `npm run check:runtime`, `npm run build`, and `npm run test:sites` in `frontend` before handoff.
- In Codex Desktop, keep the local preview running and verify the actual H5 in the in-app browser at mobile and wide viewport sizes.
- Do not deploy unless the user explicitly asks to publish or share.
