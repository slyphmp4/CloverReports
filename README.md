<div align="center">

# CloverReports

**A report and moderation workflow for Paper servers — from player submission to staff review, evidence, actions and audit history.**

[![Build](https://github.com/slyphmp4/CloverReports/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/slyphmp4/CloverReports/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/slyphmp4/CloverReports?style=flat-square)](https://github.com/slyphmp4/CloverReports/releases)
[![Java](https://img.shields.io/badge/Java-25-555?style=flat-square)](https://openjdk.org/)
[![Paper](https://img.shields.io/badge/Paper-26.2-555?style=flat-square)](https://papermc.io/)
[![Author](https://img.shields.io/badge/author-slyph-555?style=flat-square)](https://github.com/slyphmp4)

[Releases](https://github.com/slyphmp4/CloverReports/releases) · [Builds](https://github.com/slyphmp4/CloverReports/actions) · [Changelog](https://github.com/slyphmp4/CloverReports/blob/main/CHANGELOG.md) · [Issues](https://github.com/slyphmp4/CloverReports/issues)

</div>

---

## Overview

CloverReports is built around **cases**, not a disposable list of `/report` messages.

Players submit reports through a GUI. Related reports are collected into a case, staff review the case through a separate interface, and moderation actions, notes and evidence stay attached to the history instead of disappearing after the report is closed.

That makes it suitable for a small survival server as well as a multi-server setup sharing one MySQL database.

### What it covers

| Stage | CloverReports provides |
| --- | --- |
| Submission | Report GUI, configurable reasons, evidence links and cooldowns |
| Queue | Active cases, pagination, per-player history and staff notifications |
| Review | Review sessions, evidence viewing, moderator notes and case context |
| Action | Teleport, punishment command and case closing from the GUI |
| Audit | Action logs, case history and JSON/CSV export |
| Storage | Local SQLite or shared MySQL with migrations and backups |
| Safety | Quotas, URL validation, known-player checks and path restrictions |

---

## Requirements

| Component | Version / notes |
| --- | --- |
| Minecraft / Paper | **26.2** |
| Java | **25** |
| Paper API build target | `26.2.build.117-stable` by default |

CloverReports bundles the JDBC libraries it needs for SQLite and MySQL.

When using Java 25 with the bundled SQLite driver, start the server with:

```text
--enable-native-access=ALL-UNNAMED
```

This allows SQLite JDBC to load its native library without the Java native-access warning.

---

## Installation

1. Download a verified JAR from [Releases](https://github.com/slyphmp4/CloverReports/releases), or build it from source.
2. Put the JAR into the server's `plugins/` directory.
3. Start Paper 26.2 on Java 25.
4. Let CloverReports create its files under `plugins/CloverReports/`.
5. Review `config.yml`, `reasons.yml`, `gui.yml` and `messages.yml` before opening the system to players.

For a local server, the default configuration works with SQLite and does not require MySQL.

---

## Player flow

A report begins with:

```text
/report <player>
```

The command opens the submission interface rather than expecting players to memorize reason IDs or command syntax.

A typical flow is:

```text
Player selects a target
        ↓
Chooses a configured reason
        ↓
Optionally attaches evidence
        ↓
Report is added to a case
        ↓
Staff reviews the case
        ↓
Case is resolved, acted on or closed
        ↓
History and moderation logs remain available
```

By default, CloverReports requires a target to be either online or already known to the plugin. This prevents arbitrary fake offline names from filling the database.

---

## Commands

| Command | Description |
| --- | --- |
| `/report <player>` | Open the report submission GUI |
| `/viewreports` | Open active report cases |
| `/viewreports <page>` | Open a specific page |
| `/viewreports history ...` | Browse resolved/history data with filters |
| `/viewreports player ...` | Browse cases for a player |
| `/cloverreports reload` | Reload configuration and storage settings |
| `/cloverreports backup` | Create a database backup |
| `/cloverreports note ...` | Work with moderator notes |
| `/cloverreports logs ...` | Browse moderation logs |
| `/cloverreports export ...` | Export log data |

Aliases:

```text
/viewreports → /rs, /reports
/cloverreports → /cr
```

Tab completion is implemented for report targets, history filters and administrative subcommands without performing synchronous JDBC queries on the server thread.

---

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `cloverreports.report` | Everyone | Submit reports |
| `cloverreports.report.evidence` | Everyone | Attach evidence URLs |
| `cloverreports.report.cooldown.bypass` | OP | Bypass report cooldown |
| `cloverreports.report.false.bypass` | OP | Bypass false-report restrictions |
| `cloverreports.view` | OP | View cases and history |
| `cloverreports.evidence.view` | OP | View attached evidence |
| `cloverreports.notify` | OP | Receive report notifications |
| `cloverreports.reload` | OP | Reload CloverReports |
| `cloverreports.backup` | OP | Create a backup |
| `cloverreports.note` | OP | Manage moderator notes |
| `cloverreports.note.clear-all` | OP | Remove all notes from a case |
| `cloverreports.logs` | OP | View report/moderation logs |
| `cloverreports.export` | OP | Export logs |
| `cloverreports.action.delete` | OP | Close/delete a case through the GUI |
| `cloverreports.action.teleport` | OP | Teleport through the moderation GUI |
| `cloverreports.action.ban` | OP | Run the configured punishment action |

---

## Configuration

CloverReports splits configuration by responsibility instead of keeping one giant YAML file.

```text
plugins/CloverReports/
├── config.yml
├── gui.yml
├── messages.yml
├── reasons.yml
├── reports.db          # default local database
├── backups/
└── exports/            # when log exports are created
```

### `config.yml`

Controls server identity, storage, report limits, evidence policy, staff-review behavior, cleanup and exports.

The default storage setup is intentionally simple:

```yaml
storage:
  type: "local"

sqlite:
  file: "reports.db"
```

For a shared network, switch to MySQL and configure the `mysql` section.

### `reasons.yml`

Defines the reasons players can choose in the report submission GUI.

### `gui.yml`

Controls menu titles, items, names, lore and layout for the player and moderator interfaces.

### `messages.yml`

Contains user-facing messages and moderation text separately from technical settings.

---

## Evidence policy

Evidence URLs are treated as untrusted input.

The default policy requires HTTPS, port 443 and a known host:

```yaml
report:
  evidence:
    max-url-length: 2048
    require-https: true
    allow-any-host: false
    allowed-ports: [443]
    allowed-hosts:
      - "youtube.com"
      - "youtu.be"
      - "imgur.com"
      - "streamable.com"
      - "medal.tv"
      - "clips.twitch.tv"
      - "cdn.discordapp.com"
      - "media.discordapp.net"
```

The allowlist is configurable. Setting `allow-any-host: true` is an explicit opt-out from the host restriction rather than an accidental empty-list fallback.

---

## Abuse controls

The report path has several independent limits so a single account cannot cheaply fill the moderation queue.

Defaults include:

```yaml
report:
  cooldown-seconds: 60
  attempt-min-interval-ms: 1500
  max-reports-per-case: 100
  max-active-cases: 2000
  max-active-cases-per-reporter: 10
  max-reports-per-window: 30
  quota-window-seconds: 86400
  require-known-player: true
```

CloverReports also has configurable false-report handling based on reviewed-report statistics.

On MySQL, queue limits and quotas are enforced inside database transactions so several Paper servers sharing one database do not each apply an independent local limit.

---

## Moderation workflow

Staff can work from the report list instead of switching between unrelated commands.

The review system supports:

- active and historical cases;
- review-session leases so concurrent staff work is handled consistently;
- moderator notes;
- attached evidence;
- teleport actions;
- configurable punishment commands;
- explicit close reasons;
- persistent action logs.

Input used for evidence and moderator notes is captured before normal chat formatting, so it does not leak into ordinary chat when CloverChat or another formatter is installed.

---

## Storage

### SQLite

`storage.type: local` uses a SQLite database inside the CloverReports data directory. It is the default and best choice for a single server.

### MySQL

MySQL is intended for shared or remote storage. CloverReports uses HikariCP and performs schema migration before swapping a reloaded pool into active use.

For remote MySQL hosts, insecure `use-ssl: false` is rejected. The connection path is designed around verified TLS rather than silently downgrading a remote database connection.

### Migrations

Older database schemas are migrated transactionally and migration state is recorded so a restart does not duplicate reports, notes or cases.

Legacy configuration sections from older releases are also moved into the current split files when applicable.

---

## Cleanup and retention

Resolved data does not have to grow forever. The defaults are:

```yaml
cleanup:
  enabled: true
  pending-days: 14
  resolved-days: 30
  logs-days: 90
```

A pending case with an active moderation review is protected from cleanup while that review is valid.

---

## Log export

Moderation logs can be exported in structured formats including JSON and CSV. Export size and batch behavior are bounded in configuration:

```yaml
export:
  max-rows: 100000
  batch-size: 1000
```

Generated paths are restricted to the CloverReports data directory rather than accepting arbitrary filesystem locations.

---

## Build and release integrity

The project uses Gradle dependency verification, reproducible archive ordering and additional checks around the shaded JAR.

Linux / macOS:

```bash
./gradlew clean build --dependency-verification strict --warning-mode all
```

Windows:

```powershell
.\gradlew.bat clean build --dependency-verification strict --warning-mode all
```

The build verifies that project classes and resources inside the shaded JAR match the local build outputs and writes a SHA-256 content manifest.

Release artifacts also include checksums. When provenance is available, the published JAR can be verified with GitHub CLI:

```bash
sha256sum --check SHA256SUMS
gh attestation verify CloverReports-*.jar --repo slyphmp4/CloverReports
```

---

## Project notes

CloverReports is written for the current Paper 26.2 API and Java 25. The compatibility layer also isolates the inventory/chat differences needed by Cardboard-based 26.x environments instead of spreading compatibility branches throughout the moderation logic.

For release history, see [CHANGELOG.md](https://github.com/slyphmp4/CloverReports/blob/main/CHANGELOG.md).

CloverReports is maintained by **slyph**.
