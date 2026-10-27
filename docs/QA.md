# Проверка паритета с iOS

Источники: iOS commit `30d1060c54864d57d5d2313dcfcadbe53a953774`, контракт backend и закреплённый каталог.

| Сценарий | Ожидаемое поведение | Проверка |
|---|---|---|
| Пустой старт, программа и библиотека | Создание без сети, поиск | OfflineFlowTest |
| Шаблон и недели | Созданная неделя и active независимы от шаблона | WorkoutTest |
| Конкурирующий редактор | Старый черновик не перезаписывает день | WorkoutTest |
| Перенос | Только в пределах недели; занятый день отклоняется | WorkoutTest |
| Подходы | 1–9999 повторов, 0–2000 кг, запятая, собственный вес | WorkoutTest, OfflineFlowTest |
| Отдых | Два вида; после последнего упражнения не запускается | WorkoutTest |
| Пропуск | Сохраняет выполненное и черновики; возврат выбирает упражнение | WorkoutTest |
| Досрочное завершение | Нужен выполненный подход и явный выбор | WorkoutTest |
| История | Снимок, объём, время, отображение результата | OfflineFlowTest, WorkoutTest |
| Время в фоне | Отдых ограничен deadline, интервалы не перекрываются | WorkoutTest |
| Удалённая команда | Прежний контекст закрывается до применения | WorkoutTest |
| Протеин | Независимые даты и отметки, отмена | WorkoutTest, OfflineFlowTest |
| Напоминания | Два местных времени, без дублей, выключение | ProteinRemindersTest |
| Каталог | 58 записей, aliases, фильтры, ё/е, авторство | CatalogAndSyncTest, verify_catalog.py |
| Нагрузка | Прямые + косвенные × 0,5; неизвестные отдельно | CatalogAndSyncTest |
| Атомарное сохранение | Ошибка записи сохраняет прошлый файл и состояние | TrainingRepositoryTest |
| Повреждённый/новый файл | Не заменяется пустыми данными | TrainingRepositoryTest |
| Перезапуск | UUID, черновики, интервалы, история и протеин восстанавливаются | TrainingRepositoryTest, OfflineFlowTest |
| CAS | Две независимые правки приводят к конфликту | CatalogAndSyncTest, backend tests |
| Выполненные подходы | Нельзя выбрать теряющую их активную версию | CatalogAndSyncTest |
| OAuth config | iOS по умолчанию, Android по query, неизвестное отклоняется | backend config_test.go |
| OAuth client | Public, PKCE S256, свой redirect, без secret | backend deploy/tests |
| Переключение пользователей | Старые UI и active не видны другому аккаунту, возврат восстанавливает свой архив | OfflineFlowTest, AccountWorkspacesTest |
| Миграция аккаунтов | Исходный владелец сохраняется, повреждённый selector не перезаписывается, есть backup | AccountWorkspacesTest, iOS AccountWorkspacesTests |
| Принятие гостевых данных | Следующему пользователю выделяется новый гостевой каталог | AccountWorkspacesTest, iOS AccountWorkspacesTests |
| Время другого аккаунта | Не засчитывается прежнему упражнению | WorkoutTest, iOS AccountWorkspacesTests |
| Мультиюзерный backend | Независимые ресурсы, история, revision и request ID | backend multiuser_integration_test.go |
| Telegram broker | Подпись, JWKS, PKCE S256, стабильный subject, UserInfo отключён | backend deploy/tests, live redirect checks |
| Ограничение Telegram state | `requiresShortStateParameter=true`; короткий state при прямом входе и длинном redirect Account Console | backend deploy/tests, браузерная проверка обоих клиентов и Account Console |
| Подмена/отмена Telegram state | Подмена отклоняется без выдачи code; отмена сохраняет исходный client state и открывает login Keycloak | браузерная проверка callback с `error=access_denied` |
| Account Console после входа | `sub`, audience `account`, собственные account roles; profile и linked-accounts возвращают HTTP 200 | backend deploy/tests, протокольная проверка полного code/PKCE flow и Account API |
| Первоначальный owner | Realm import сохраняет стандартные права управления своим аккаунтом для привязки Telegram | backend deploy/tests, проверка эффективных ролей старой установки |

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/verify_catalog.py
python3 scripts/release_local.py
```

Robolectric сохраняет изображения экранов в `app/build/reports/screenshots/`. Проверки JVM Android не устанавливают Simulator или Android Emulator.

На физическом устройстве нужно проверить браузерный OAuth/refresh/logout с действующим Keycloak, восстановление после принудительного закрытия, уведомления после перезагрузки и смены часового пояса, энергосбережение, Media3-видео, внешние ссылки, масштаб шрифта и TalkBack. Для двух телефонов — независимые правки, разрешение конфликтов и сетевой обрыв после принятого сервером запроса. Локальные автоматические проверки не подтверждают эти аппаратные и сетевые сценарии.
