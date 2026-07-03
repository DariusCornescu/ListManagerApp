# List Manager — Android (native)

Native Android client for the List Manager app, built with Kotlin + Jetpack Compose.
It talks to the FastAPI backend in [`backend-fastapi/`](../backend-fastapi/).

## Required local setup: `ApiConfig.kt`

The network layer reads its base URL and environment from an
`ApiConfig` object at:

```
app/src/main/java/com/darius/listmanager/network/ApiConfig.kt
```

**This file is intentionally git-ignored** (see [`.gitignore`](.gitignore)) so
that each developer can point the app at their own backend without committing
machine-specific URLs. It does **not** exist in a fresh checkout, and the project
**will not compile** until you create it — `RetrofitClient`, `NetworkHelper`,
`WebSocketService`, and `SyncDebugScreen` all reference `ApiConfig`.

### Create it

Copy the checked-in template and edit the URLs:

```bash
cd android-native/app/src/main/java/com/darius/listmanager/network
cp ApiConfig.kt.example ApiConfig.kt
```

Then open `ApiConfig.kt` and set the URLs for your environment. See
[`ApiConfig.kt.example`](app/src/main/java/com/darius/listmanager/network/ApiConfig.kt.example)
for the full annotated template.

### Expected shape

`ApiConfig` is an `object` in package `com.darius.listmanager.network` exposing
at least these members (this is the minimal contract the rest of the app relies on):

| Member | Type | Purpose |
| --- | --- | --- |
| `BASE_URL` | `String` | Retrofit base URL + WebSocket host source. **Must end with a trailing `/`.** |
| `USE_PRODUCTION` | `Boolean` | Selects prod vs. dev; also picks `wss` vs. `ws` for the WebSocket. |
| `getCurrentEnvironment()` | `fun (): String` | Human-readable label shown on the Sync Debug screen. |

Minimal example:

```kotlin
package com.darius.listmanager.network

object ApiConfig {
    const val USE_PRODUCTION: Boolean = false

    val BASE_URL: String
        get() = if (USE_PRODUCTION) "https://your-production-host.example.com/"
                else "http://10.0.2.2:8000/"

    fun getCurrentEnvironment(): String =
        if (USE_PRODUCTION) "Production" else "Development"
}
```

### Choosing `BASE_URL`

The FastAPI backend listens on **port 8000** by default. Note the trailing slash
is required (Retrofit, and the app appends paths like `${BASE_URL}health`).

| Running against | `BASE_URL` |
| --- | --- |
| Android **emulator** → backend on your machine | `http://10.0.2.2:8000/` |
| **Physical device** → backend on your machine | `http://<your-computer-LAN-IP>:8000/` (same Wi‑Fi) |
| Deployed backend | `https://your-production-host.example.com/` |

> `10.0.2.2` is the emulator's alias for the host machine. Do **not** use
> `localhost`/`127.0.0.1` from the emulator — that points at the emulator itself.

## Building

Once `ApiConfig.kt` exists:

```bash
cd android-native
./gradlew assembleDebug        # or open the folder in Android Studio
```
