# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Правила работы (от владельца проекта)

- Оспаривай мои решения. Если подход плохой — скажи прямо. Не соглашайся просто потому что я попросил.
- Каждый ответ структурируй: что сделано, что нужно от меня и какой следующий шаг.
- После каждой задачи предлагай, что улучшить или автоматизировать.
- Если есть вопрос или нужно что-то уточнить — обязательно спрашивай.

## Project

WhiteTool — a Spigot/Paper Minecraft plugin that bridges the server with a Telegram bot: whitelist management from a Telegram group (group admins only), full command set in bot DMs (`/cmd`, live console streaming via `/console on`, `/online`), all gated by Telegram user IDs from `config.yml`.

## Build

No system-wide Maven; a portable copy lives in `.tools/`:

```powershell
& ".tools\apache-maven-3.9.6\bin\mvn.cmd" -q clean package
```

Output: `target\WhiteTool-1.0.0.jar`. There are no tests. To try changes, drop the jar into a server's `plugins/` folder; on the server console `/whitetool reload` restarts the bot with a fresh config.

## Compatibility constraints (the non-obvious part)

The jar must run on Spigot/Paper 1.13–1.21, so everything is compiled with `--release 8` against the **oldest** library versions the server ships (all dependencies are `provided`; nothing is shaded):

- **Gson 2.8.0** — no `JsonParser.parseString()`; use `new JsonParser().parse(...)`. Don't bump the pom version: newer Gson APIs will compile but crash on 1.13–1.17 servers.
- **log4j-core** — `ConsoleWatcher` deliberately uses the deprecated 4-arg `AbstractAppender` constructor because it exists across log4j 2.8–2.x. Builder-style APIs are not safe here.
- No external HTTP/Telegram libraries — `TelegramBot` is a hand-rolled long-polling client on `HttpURLConnection` precisely to avoid shading. It supports a configurable `api-base-url` (reverse proxy to bypass RU hosting blocks of `api.telegram.org`) and an optional SOCKS/HTTP `proxy` (with auth via a JVM-global `Authenticator` scoped to `RequestorType.PROXY`).

## Architecture

Four classes in `ru.whitetool`, one direction of flow:

`TelegramBot` (long-poll thread) → `CommandRouter.handleMessage` → Bukkit.

- **Threading is the core invariant**: Telegram polling and message sending happen on a dedicated daemon thread; anything touching Bukkit API must be wrapped in `Bukkit.getScheduler().runTask(...)` (see `CommandRouter`). Sending Telegram messages is safe from any thread.
- **`ConsoleWatcher`** is a log4j appender attached to the root logger. It feeds two consumers: a bounded buffer flushed to `/console on` subscribers every few seconds (scheduled in `WhiteToolPlugin`), and short-lived "collectors" that capture console output for a couple of seconds after a `/cmd` so the bot can reply with the result. `append()` early-returns before formatting when there are no subscribers/collectors (hot path under heavy logging), and uses a thread-safe `DateTimeFormatter` instead of a synchronized `SimpleDateFormat`.
- **Command hiding (`SuppressFilter`)**: bot-issued commands must not be visible to server staff reading the console/log. `CommandRouter` wraps every `Bukkit.dispatchCommand` in `console.beginSuppress()/endSuppress()` (a thread-local flag on the main thread). `ConsoleWatcher` installs `SuppressFilter` on every *other* root appender (console + file), which returns `Result.DENY` while the flag is set — so those lines never reach the log, but the watcher's own appender (no filter on itself) still captures them for the Telegram reply. **Known limit — do not oversell hiding:** it relies on the command's log output being emitted synchronously on the main thread. Servers with fully async log4j logging, or external log readers (CoreProtect, etc.), can still surface the output. This is best-effort stealth, not a guarantee.
- **Feedback-loop guard**: `ConsoleWatcher.append` drops any log line from the plugin itself (logger name or `[WhiteTool]` prefix). Without this, a failing Telegram send would log a warning, which would be forwarded to Telegram, fail again, and loop. Keep the `[WhiteTool]` prefix on every log message the plugin emits.
- **Authorization model — stealth is intentional**: the bot must never reveal its capabilities to strangers. Unauthorized users get **complete silence** (no "access denied" replies) both in DMs and in the group. DM commands require the sender's Telegram user ID to be in the owner list, which is **hardcoded in `WhiteToolPlugin.ADMIN_IDS`** (deliberately not read from config, so it can't be swapped by editing config.yml on the server); the console/`/cmd` features are a secret emergency channel for the owner. `group-chat-id` and `allow-group-admins` stay in config.yml. Group commands (`/whitelist`/`/unwhitelist` only, and only in the configured `group-chat-id`) additionally accept Telegram group admins when `allow-group-admins` is true (checked live via `getChatMember`). `/id` answers only `admin-ids` users (in group it works even before `group-chat-id` is configured, so the owner can discover the group id). Don't add commands or error replies that leak the bot's existence to non-admins.
- Whitelist changes go through `Bukkit.dispatchCommand(console, "whitelist add …")` rather than the OfflinePlayer API (works uniformly in online/offline mode); nicknames are validated against `whitelist.nick-pattern` first since they are interpolated into a console command.

## Language

User-facing strings (Telegram replies, config comments, log messages) are in Russian — keep new ones consistent.
