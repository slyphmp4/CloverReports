# Changelog

## 1.4.0 — 2026-11-20 (port in progress)

- Компиляция и выпуск артефакта переведены на Paper API `26.3.build.255-stable` и Java 25.
- Сохранён минимум `api-version: '26.2'` для совместимости с серверами 26.2.
- CI и release pipeline теперь проверяют 26.2 API перед финальной сборкой и тестами под 26.3.
- Cardboard-совместимость инвентарей и чата оставлена изолированной в существующих адаптерах; требуется smoke-test на конкретном форке Cardboard 26.3.
- Dependency verification и контроль содержимого shaded JAR обязательны перед релизом.

## 1.3.2 — 2026-08-26

- Убрано стандартное курсивное оформление Minecraft со всех названий и lore GUI-предметов.
- Явно настроенное форматирование и цвета по-прежнему поддерживаются.

## 1.3.1 — 2026-08-26

- Исправлено создание GUI на Cardboard 26.x: незавершённый Adventure-overload `createInventory` больше не используется на Cardboard.
- Paper продолжает использовать современный Adventure Component API; legacy String-overload изолирован одним точечным compatibility factory.
- Добавлены regression tests, запрещающие прямое создание inventory в GUI-классах.

## 1.3.0 — 2026-08-26

- Убраны JDBC-запросы из синхронных tab completer; добавлен асинхронно обновляемый снимок подсказок.
- Подключение и миграции БД при `/cloverreports reload` перенесены с Minecraft main thread.
- Освобождение review lease и закрытие БД при выключении перенесены в последовательный lifecycle executor.
- GUI titles, item names и lore переведены на Adventure `Component` API.
- Удалены широкие suppressions deprecated API; оставлены только два точечных Cardboard chat fallback.
- Усилены диагностика атомарной записи, строгая dependency verification и полный CI build.
- Удалён подтверждённо мёртвый код и добавлены regression/integration tests.
