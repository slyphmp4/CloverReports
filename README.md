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
./gradlew clean test shadowJar
```

Windows:

```powershell
.\gradlew.bat clean test shadowJar
```

Готовый JAR появится в `build/libs/`.

## Установка

1. Соберите проект или возьмите готовый JAR из GitHub Actions.
2. Поместите JAR в папку `plugins/` сервера.
3. Запустите сервер на Java 25.
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
- Gradle wrapper обновлён до 9.6.1;
- Shadow переведён на актуальный plugin id `com.gradleup.shadow`;
- старый `AsyncPlayerChatEvent` заменён на `AsyncChatEvent`;
- ввод из компонентного чата переводится в plain text через `PlainTextComponentSerializer`, совместимый с Adventure 5;
- удалена неиспользуемая зависимость Authlib;
- тест валидации ресурсов обновлён под `api-version: 26.2`.
