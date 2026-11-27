# PlayGround

<!-- omgithub:readme:start -->
## 🚀 Build, play, and remix with OMGithub

**Created using [OMGithub.com](https://go.ondev.store/).**

[![OMGithub](https://img.shields.io/badge/OMGithub-Open%20project-orange?style=for-the-badge)](https://go.ondev.store/Vladimirxgusev/PlayGround)
[![GitHub](https://img.shields.io/badge/GitHub-Source-181717?logo=github&style=for-the-badge)](https://github.com/Vladimirxgusev/PlayGround)

- 🎮 [Open the project](https://go.ondev.store/Vladimirxgusev/PlayGround).
- ✨ [Remix this project](https://go.ondev.store/Vladimirxgusev/PlayGround?remix=1).
- 💻 [Explore the source](https://github.com/Vladimirxgusev/PlayGround).
- 🛠️ [Check build runs](https://github.com/Vladimirxgusev/PlayGround/actions).
- 🐛 [Report an issue](https://github.com/Vladimirxgusev/PlayGround/issues).
- 👤 [Explore the creator's projects](https://go.ondev.store/Vladimirxgusev).
- 🌍 [Create with OMGithub](https://go.ondev.store/).
<!-- omgithub:readme:end -->

## Секретутка — голосовой ассистент для Android

Приложение принимает голосовые команды на русском и:
- ставит будильники и таймеры в стандартных «Часах»;
- создаёт события в Google Календаре (через календарь телефона);
- записывает задачи в Google Задачи;
- отвечает на вопросы: погода (Open-Meteo), курсы ЦБ, время и дата, остальное — через YandexGPT.

Простые фразы разбираются правилами без интернета (`core/`), остальные — через YandexGPT,
если в настройках указаны API-ключ и ID каталога Yandex Cloud.

**Сборка:** APK собирает GitHub Actions (workflow «Android APK»), файл лежит в артефакте
`sekretutka-apk` у последнего запуска. Тесты разбора команд: `./gradlew :core:test`.

**Подпись:** все сборки подписываются одним ключом, иначе обновление не встанет поверх старой версии.
Ключ хранится в `signing/sekretutka.p12`, зашифрованный паролем из секрета репозитория `SIGNING_PASSWORD`
(Settings → Secrets and variables → Actions). Без этого секрета сборка не сможет расшифровать ключ.

## Galaxy Watch / Wear OS

Из одного запуска GitHub Actions установите `sekretutka-apk` на телефон и
`sekretutka-wear-apk` на часы. Оба APK подписываются существующим ключом
«Секретутки». Совпадение пакета и подписи нужно для Wear OS Data Layer.

На телефоне один раз создайте задачу в «Секретутке» и подтвердите доступ к Google
Задачам, если появится запрос. На часах откройте «Секретутку», продиктуйте или введите
«Добавь задачу купить молоко завтра» и нажмите «Добавить на телефон».
Часы дождутся подтверждения записи в Google Задачи от приложения на телефоне.
При временном отключении команда остаётся в очереди. «Повторить отправку» использует
тот же идентификатор задачи. Одновременно часы держат одну задачу в очереди.

На часах также можно сказать «Поставь будильник на 10:30» или «Таймер на 5 минут».
После подтверждения команда передаётся приложению «Часы» на Galaxy Watch.
