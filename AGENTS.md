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
- Keep release history in `docs/version-history.md`. Every release-oriented commit must update the matching version entry for H5, admin, and backend/data changes; do not hardcode the displayed version in either frontend.

## Version Branch Contract

- `master` is the only stable release branch. Do not commit unfinished feature work directly to it.
- `V0.13` permanently preserves the legacy V0.13 code line. After it is frozen, only explicitly authorized blocking security or data-recovery fixes may be applied there.
- `V0.2` is the development and gray-release branch for the new V0.2 H5, backend, admin, content packages, and migrations.
- The current `main` branch must not be deleted or repointed until `V0.13`, `master`, and `V0.2` have been created, verified, and pushed successfully.
- When V0.2 passes local, gray, migration, and production verification, merge or fast-forward the tested `V0.2` commit into `master` and tag the release. Do not simulate a release by renaming branches back and forth.
- V0.13 tables and source remain available for archive access after V0.2 becomes stable; a branch promotion never authorizes rewriting legacy player data.

## Editing Boundary

- On branch `V0.2`, all new gameplay code belongs under `v2/`. The legacy `frontend/`, `backend/`, and `admin/` directories are V0.13 reference implementations and may only receive explicitly requested blocking fixes.
- The V0.2 H5 lives in `v2/frontend`, the game service in `v2/backend`, the management applications in `v2/admin`, and versioned world content in `v2/content`.
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

## Account Turn Quota Contract

- The platform-sponsored turn limit is authoritative in `gameplay_config.account_public_turn_limit`. It is a cumulative per-account limit, does not reset daily, and changing it applies to both existing and new accounts.
- Never hardcode the sponsored turn count in H5 copy. Registration success, Adventure usage, World settings, remaining turns, and exhaustion handling must use the backend `AiSettings.turnLimit`, `usedTurns`, and `remainingTurns`.
- The exhaustion message is managed from the admin Limits page and stored in `gameplay_config.account_public_turn_limit_message`. Player support/QQ copy reuses `gameplay_config.registration_contact_text` so operators maintain contact information in one place.
- Exhaustion is a normal business outcome, not a model failure. The backend returns HTTP 429 with code `PUBLIC_TURN_LIMIT_REACHED`; H5 must show the dedicated ink-style dialog with the configured message, support contact, QQ copy action when a group number is present, and a route to World/key settings. Do not replace it with “天机紊乱”.
- A valid player-owned generation key bypasses the sponsored-turn limit and must not increment sponsored usage. Genuine network/provider/5xx failures may still use the generic “天机紊乱” presentation.

## Deployment and Data Safety

- The public H5 entry is `https://wenxianlu.cn` (also `https://www.wenxianlu.cn`). Alibaba Cloud DNS points both `@` and `www` to the Hong Kong SWAS public IP `8.217.11.8` (instance `c895cacc73c9474a83623882f93853e1`, `Ubuntu-dhmd`, Ubuntu 22.04). Do not change these DNS records or move traffic without explicit user approval.
- The Hong Kong server is an HTTPS edge only: Nginx serves `/var/www/wenxian/current`, and `/api/` proxies to the JD Cloud H5 origin at `http://116.198.232.119:25173`. Java, the admin backend, and MySQL do not run on the Hong Kong server. The admin console remains on the JD Cloud deployment.
- Hong Kong Nginx config is `/etc/nginx/sites-available/wenxian` with a symlink in `sites-enabled`. The Let's Encrypt certificate covers `wenxianlu.cn` and `www.wenxianlu.cn`; files live under `/etc/letsencrypt/live/wenxianlu.cn/`, and `certbot.timer` performs renewal. Never copy or commit the private key. Verify with `nginx -t`, `systemctl status certbot.timer`, and `certbot renew --dry-run` after certificate or Nginx changes.
- The Mac uses the dedicated SSH identity `/Users/ben/.ssh/id_ed25519_wenxian_hk` for `root@8.217.11.8`. This path identifies a local private key and must never be copied into the repository. Keep TCP 80/443 public; restrict TCP 22 to trusted source IPs when operational access allows it.
- A frontend release must update the Hong Kong static directory (prefer an atomic release/symlink deployment) and verify both HTTPS hostnames plus one hashed asset. Backend releases still happen on JD Cloud; an H5 `502` on `/api/` normally means the JD origin/backend is unavailable, not that TLS or the Hong Kong static host failed.
- The shared test database is Alibaba Cloud RDS MySQL 8, database `wenxian`. The H5 backend and admin backend must use the same RDS database through their private external Spring configuration.
- The old Docker MySQL instance on the JD Cloud test server is retained only as a short-term rollback source and must remain stopped during normal operation. Do not start it as the application database or expose its port publicly.
- Server secrets belong only in `/opt/wenxian-test/application-secret.yml` and `/opt/wenxian-admin/application-secret.yml` (or environment variables). Never commit database passwords, API keys, temporary client option files, dumps, or production data.
- Before a database cutover, stop both backends, create an off-server backup, import into an empty target database, and compare every table's exact row count before switching connection settings.
- After database or deployment changes, verify both backend processes, `/api/v1/health`, one database-backed public endpoint, and the admin login route before reopening the service.

## Verification

- For V0.2 backend work, run `mvn test` in `v2/backend`. For V0.13 blocking fixes, run `mvn test` in `backend` and the applicable frontend checks.
- In Codex Desktop, keep the local preview running and verify the actual H5 in the in-app browser at mobile and wide viewport sizes.
- Do not deploy unless the user explicitly asks to publish or share.
