# ZUREQ Shopper for Android

Native Android client (Kotlin, Jetpack Compose) for searching independent online shops through the
[ZUREQ](https://zureq.io) API: search by country or the whole EU, live price and stock, and checkout on the
shop's own site. Sister app of [zureq-ios](https://github.com/dzienisz/zureq-ios).

## Build

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Requires JDK 17 and an Android SDK with platform 35 (`local.properties` → `sdk.dir`).

The app needs a ZUREQ API key (`zmcp_…`, from chat.zureq.io/developers). Debug builds can be seeded from the
command line: `adb shell am start -n com.dzienisz.zureqshopper/.MainActivity --es zureqApiKey zmcp_…`.

This is an independent app and is not affiliated with ZUREQ.
