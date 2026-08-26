# Changelog

## 1.3.0 — 2026-08-26

- Убраны JDBC-запросы из синхронных tab completer; добавлен асинхронно обновляемый снимок подсказок.
- Подключение и миграции БД при `/cloverreports reload` перенесены с Minecraft main thread.
- Освобождение review lease и закрытие БД при выключении перенесены в последовательный lifecycle executor.
- GUI titles, item names и lore переведены на Adventure `Component` API.
- Удалены широкие suppressions deprecated API; оставлены только два точечных Cardboard chat fallback.
- Усилены диагностика атомарной записи, строгая dependency verification и полный CI build.
- Удалён подтверждённо мёртвый код и добавлены regression/integration tests.
