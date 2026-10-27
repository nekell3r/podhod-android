# Подход для Android

Репозиторий подготовлен для отдельного Android-клиента общего приложения «Подход».

**Статус: Android-приложение ещё не реализовано.** Здесь нет APK, Gradle-проекта или готового интерфейса. Существующий iOS-клиент продолжает развиваться отдельно; перенос backend на другую платформу не требуется.

- [Общий backend, REST API и MCP](https://github.com/nekell3r/podhod-backend).
- [Рабочий клиент iOS](https://github.com/nekell3r/podhod-ios).
- [Контракт API](https://github.com/nekell3r/podhod-backend/blob/main/docs/API.md).
- [План интеграции Android](docs/INTEGRATION.md).

`catalog/exercise-catalog.json` — закреплённый экспорт основного каталога бэкенда. `catalog.lock.json` содержит версию, SHA-256 и Git-ревизию источника. В нём нет записей тренировок, аккаунтов, токенов или серверных credentials. Условия данных и видеоссылок — [CATALOG-LICENSE.md](CATALOG-LICENSE.md).

Адрес API, OAuth redirect и настройки подписи задаются локально при реализации клиента. Серверные адреса владельца и секреты в репозиторий не включаются.
