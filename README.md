# CloverReports

CloverReports — плагин жалоб и модерации для Paper 26.2.

## Возможности

- отправка жалоб через GUI;
- причины жалоб из `reasons.yml`;
- доказательства по URL;
- интерфейс модератора;
- история действий и заметки;
- MySQL и SQLite;
- экспорт логов в CSV/JSON;
- резервное копирование и обслуживание базы данных;
- статистика репортёров.

## Требования

- Minecraft / Paper API 26.2;
- Java 25;
- Gradle Wrapper 9.6.1.

## Сборка

Linux/macOS:

```bash
./gradlew clean test shadowJar
```

Windows:

```bat
gradlew.bat clean test shadowJar
```

Готовый JAR будет находиться в `build/libs/`.

## Установка

1. Соберите проект или скачайте артефакт GitHub Actions.
2. Поместите JAR в папку `plugins` Paper-сервера.
3. Запустите сервер.
4. Настройте `config.yml`, `messages.yml`, `gui.yml` и `reasons.yml`.
5. Перезапустите сервер после изменения настроек, если конкретная настройка не поддерживает reload.

## Основные команды

- `/report` — открыть интерфейс отправки жалобы;
- `/viewreports` — открыть список жалоб;
- `/cloverreports` — административные команды плагина.

Полный список подкоманд и прав см. в `src/main/resources/plugin.yml`.

## Порт на Minecraft 26.2

В ветке 26.2 проект обновлён под современный Paper API:

- `io.papermc.paper:paper-api:26.2.build.117-stable`;
- Java toolchain/release 25;
- `api-version: '26.2'`;
- Gradle 9.6.1;
- Shadow plugin `com.gradleup.shadow` 9.6.1;
- старый `AsyncPlayerChatEvent` заменён на `AsyncChatEvent`;
- текст Adventure `Component` читается через `PlainTextComponentSerializer`;
- удалена неиспользуемая зависимость Authlib.

CI выполняет `clean test shadowJar` на Java 25 и публикует собранный JAR как артефакт workflow.
