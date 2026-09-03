# Attribution

The Busan read-only portal protocol and parsing structure are adapted from [mahlernim/ha-busan-city-gas](https://github.com/mahlernim/ha-busan-city-gas), inspected at commit `320513798301d491e3984fae8bb1a1cede22e8c0`. Copyright 2026 mahlernim, MIT License. The full MIT terms are included in [LICENSE](LICENSE).

The estimator implementation and Android user interface are part of this project. The seasonal idea is informed by the same integration and the app's weekly physical-confirmation design.

Research references, not redistributed source code, include [af950833/korea_gasapp](https://github.com/af950833/korea_gasapp), the [Gasapp module in hwajin-me/home-assistant-korea-components](https://github.com/hwajin-me/home-assistant-korea-components), and [dugurs/ha-city-gas-bill](https://github.com/dugurs/ha-city-gas-bill). Their distinct capabilities and licensing observations are recorded in [provider research](docs/PROVIDERS.md).

Runtime libraries include AndroidX/Jetpack Compose, AndroidX WorkManager, Kotlin and coroutines, and OkHttp under Apache License 2.0, and jsoup under the MIT License. Material Symbols/Icons are provided through AndroidX under Apache License 2.0. Gradle Wrapper is distributed under Apache License 2.0. Dependency source and license links follow.

- [AndroidX](https://android.googlesource.com/platform/frameworks/support/)
- [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)
- [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)
- [OkHttp](https://github.com/square/okhttp/blob/master/LICENSE.txt)
- [Okio](https://github.com/square/okio/blob/master/LICENSE.txt)
- [jsoup](https://github.com/jhy/jsoup/blob/master/LICENSE)
- [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE)

The original launcher artwork was generated for this project with the built-in image-generation tool. It is not a gas-provider logo. The prompt, asset paths and usage notes are in [brand assets](docs/BRAND.md).
