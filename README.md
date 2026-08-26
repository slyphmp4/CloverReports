# CloverReports

CloverReports — Paper-плагин для системы жалоб и модерации на Minecraft-сервере.

Эта ветка проекта портирована на **Minecraft / Paper 26.2** и **Java 25**.

## Возможности

- отправка жалоб через GUI (`/report <игрок>`);
- просмотр активных дел и истории (`/viewreports`, алиасы `/rs`, `/reports`);
- модераторские действия через GUI;
- заметки модераторов и журнал действий;
- прикрепление URL-доказательств;
- экспорт логов;
- SQLite и MySQL;
- резервные копии базы данных;
- настраиваемые сообщения, причины репортов и GUI.

## Требования

- Minecraft / Paper **26.2**;
- Java **25**.

## Сборка

Linux/macOS:

```bash
./gradlew clean build --dependency-verification strict --warning-mode all
```

Windows:

```powershell
.\gradlew.bat clean build --dependency-verification strict --warning-mode all
```

Готовый JAR появится в `build/libs/`. Задача `verifyJarContents` побайтно сверяет классы и ресурсы проекта с содержимым shaded JAR и создаёт полный манифест SHA-256 в `build/reports/jar-content-manifest.sha256`; `writeArtifactChecksum` дополнительно создаёт `build/checksums/SHA256SUMS`.

## Установка

1. Соберите проект или возьмите готовый JAR из GitHub Actions.
2. Поместите JAR в папку `plugins/` сервера.
3. Запустите сервер на Java 25 с `--enable-native-access=ALL-UNNAMED` (это штатно требуется SQLite JDBC для загрузки нативной библиотеки без предупреждений).
4. После первого запуска настройте файлы в `plugins/CloverReports/`.

## Основные команды

| Команда | Назначение |
| --- | --- |
| `/report <игрок>` | Подать жалобу через GUI |
| `/viewreports` | Просмотр дел и истории |
| `/cloverreports reload` | Перезагрузить конфигурацию |
| `/cloverreports backup` | Создать резервную копию |
| `/cloverreports note` | Работа с заметками модераторов |
| `/cloverreports logs` | Просмотр логов |
| `/cloverreports export` | Экспорт логов |

## Что изменено для 26.2

- Paper API перенесён со старой ветки `1.16.5` на `io.papermc.paper:paper-api:26.2`;
- `api-version` обновлён до `26.2`;
- toolchain и bytecode target обновлены до Java 25;
- Gradle wrapper обновлён до 9.7.1;
- Shadow переведён на актуальный plugin id `com.gradleup.shadow`;
- на Paper ввод из чата обрабатывается через `AsyncChatEvent`, а для Cardboard 26.x добавлен совместимый fallback через `AsyncPlayerChatEvent`;
- Paper GUI использует Adventure Component API; Cardboard GUI проходит через изолированный compatibility factory из-за возвращающего `null` Adventure-overload в Cardboard 26.x;
- служебный ввод доказательств и модераторских заметок перехватывается на `LOWEST`, до чат-форматтеров вроде CloverChat, чтобы сообщение не уходило в обычный чат;
- ввод из компонентного чата переводится в plain text через `PlainTextComponentSerializer`, совместимый с Adventure 5;
- удалена неиспользуемая зависимость Authlib;
- тест валидации ресурсов обновлён под `api-version: 26.2`.

## Обновление с предыдущих версий

- Миграции старой схемы БД выполняются транзакционно и отмечаются в `cloverreports_meta`; повторный запуск не дублирует дела, заметки или репорты.
- Старые секции `messages`, `gui`, `report.reasons` и `actions.ban-reason` переносятся в актуальные файлы при первом обнаружении, после чего удаляются из исходного места.
- Удалять миграции до окончания поддержки обновления с `v1.2.x` нельзя: они являются частью совместимости, а не runtime fallback.

## Производительность

- tab completion никогда не обращается к JDBC на основном потоке: подсказки и количества страниц читаются из атомарного снимка, обновляемого асинхронно;
- `/cloverreports reload` подключает и мигрирует новую БД вне основного потока, после чего атомарно заменяет готовый пул;
- при выключении освобождение review lease и закрытие пула выполняются последовательно в отдельном lifecycle executor.

## Security hardening

- неизвестные offline-ники не принимаются: UUID должен быть получен онлайн или ранее зарегистрирован плагином (`report.require-known-player`);
- cooldown проверяется до запросов статистики, а постоянные квоты ограничивают число активных дел и жалоб за окно времени;
- глобальный лимит очереди и квоты применяются внутри DB-транзакции с общей блокировкой, в том числе при нескольких Paper-серверах на одной MySQL;
- `PENDING`-дела старше `cleanup.pending-days` удаляются ежедневно, но дело с действующей модераторской сессией не затрагивается;
- evidence URL по умолчанию разрешены только для HTTPS и доверенных платформ из `report.evidence.allowed-hosts`; пустой список безопасно откатывается к встроенному allowlist, а явный opt-out — `allow-any-host: true`;
- удалённый MySQL нельзя запустить с `mysql.use-ssl: false`; для remote host используется `sslMode=VERIFY_IDENTITY`;
- SQLite, backup и export пути ограничены директорией плагина;
- Gradle проверяет SHA-256 всех зависимостей, wrapper имеет официальный hash, а GitHub Actions закреплены полными commit SHA;
- release workflow публикует JAR, `SHA256SUMS`, манифест содержимого и GitHub build-provenance attestation.

Для релизного JAR сначала проверьте `SHA256SUMS`, затем provenance:

```bash
sha256sum --check SHA256SUMS
gh attestation verify CloverReports-*.jar --repo slyphmp4/CloverReports
```
