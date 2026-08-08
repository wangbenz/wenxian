# Responsive H5 Game Agent Guide

## Product Contract

- This deliverable is a real responsive H5 game, not a phone mockup.
- Render directly into the browser viewport. Do not add `PhoneFrame`, `MobileRuntime`, an iPhone/Android bezel, a simulated status bar, a device picker, or a simulated keyboard.
- Mobile browsers are the primary target. Use `100dvh`, native text inputs, and CSS safe-area environment variables.
- On wider screens, keep the paper background full-viewport and constrain the readable game content to a centered column.
- Selected visual direction: `墨卷纪事` — warm xuan-paper, restrained ink wash, celadon accents, cinnabar risk states, and literary Chinese typography.
- The core loop must support both quick choices and free-text actions.
- The current gameplay is a multi-world, light-state, strong-memory narrative game. Product releases do not create a new gameplay engine, API namespace, Java package, or table set by default.
- Agent-generated items, abilities, and important characters are allowed. Java protects identity, lifecycle, time, persistence, and security boundaries instead of imposing an incomplete combat/economy simulation.
- Keep product documentation compact: `docs/product-direction.md` records active direction and abandoned proposals, `docs/game-design.md` defines gameplay, `docs/architecture.md` defines Agent/API/migration boundaries, and `docs/data-dictionary.md` is the single Chinese data dictionary.
- Keep release history in `docs/version-history.md`. Every release-oriented commit must update the matching version entry for H5, admin, and backend/data changes; do not hardcode the displayed version in either frontend.

## Editing Boundary

- The V0.2 gameplay/architecture proposal is abandoned and must not be revived as an implementation plan. The default V0.14 UI only reuses its proven H5 visual shell in `frontend/src/Prototype.tsx`; this visual reuse does not carry over V0.2 combat, economy, profession, map, event, or content-production rules. V0.14 state is mapped through a thin API/view adapter, while `?edition=v013` keeps the old backend compatibility path. Do not create a second look-alike V0.14 shell. Shared styling remains in `frontend/src/prototype.css`.
- `frontend/src/App.tsx` is the deliberately minimal H5 entry and should render `Prototype` directly.
- Treat `frontend/src/main.tsx`, `frontend/src/styles.css`, `frontend/vite.config.ts`, `frontend/worker/index.js`, and `frontend/scripts/prepare-sites-build.mjs` as runtime files. Edit them only when the requested runtime or deployment behavior requires it.
- The old `frontend/src/mobile/` package and device assets remain only as unused template material; do not reconnect them to the app.
- The deployed V0.14 gameplay still lives in `backend/src/main/java/com/wenxian/game/v014/` and `v014_*`; treat it as a compatibility source during migration. New long-lived work targets the neutral `story` domain described in `docs/architecture.md`. Do not add `v015_*`, `V015*`, or `/api/v1/v015/*` business structures.
- `V014ModelGateway` is the model boundary. LangChain4j may implement transport/adapters, but it must not become the source of truth or bypass reducers/transactions.
- If an intentional runtime change modifies a locked file, run `npm run update:runtime-lock`, then `npm run check:runtime`.

## Architecture and Chinese Documentation

- Product release, story engine, engine version, state schema version, and prompt version are separate concepts. Never infer one from another.
- A new feature must identify its domain owner, authoritative data source, state transitions, Agent/Java/H5 boundaries, old-save behavior, rollback path, admin observability, and verification before implementation.
- New stable APIs, Java business classes, and database tables must use domain names rather than product release names.
- Every new or changed MySQL table must have a Chinese table comment. Important columns and business-protecting indexes must have Chinese comments explaining meaning and constraints.
- Every new Service, Engine, Repository, Migrator, and non-trivial DTO must have Chinese Javadoc describing responsibility, authority, transaction boundary, and failure behavior.
- Complex TypeScript state, compatibility adapters, and retry/reconciliation branches require concise Chinese comments. Do not add comments that merely repeat obvious code.
- Every schema or key-field change must update `docs/data-dictionary.md` in the same commit. API or migration-boundary changes must also update `docs/architecture.md`.
- Current production Hotfixes and unified-story refactors must use separate commits/branches; do not mix an incident fix with migration work.

## Analytics Contract

- 管理台统计属于独立观察层，不得通过跨版本业务表 `UNION` 作为长期查询方案。当前游戏只写中立 `analytics_*` 事实与汇总结构，旧版表只允许离线回算。
- “活跃玩家”必须来自创建命卷、成功推进、修正或整理实体等有效行为；登录和长期会话不能冒充活跃或在线。
- 模型请求、最终失败操作和成功游戏回合是三种不同事实，不得互相替代。重试可以增加模型请求数，但不能重复增加成功回合。
- 统计写入和汇总失败不得阻塞、回滚或延长玩家的命卷事务；管理台必须优先读取带索引的日汇总，历史明细按需加载。
- 统计事件不得保存邮箱、API Key、完整 Prompt 或剧情正文。所有指标必须声明北京时间口径、实时/历史回算来源和计算版本。

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

- Run `mvn test` in `backend`, then `npm run check:runtime`, `npm run build`, and `npm run test:sites` in `frontend` before handoff.
- In Codex Desktop, keep the local preview running and verify the actual H5 in the in-app browser at mobile and wide viewport sizes.
- Do not deploy unless the user explicitly asks to publish or share.
