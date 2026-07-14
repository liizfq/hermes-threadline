# Hermes for Matrix

An Android mobile control client for [Hermes Agent](https://hermes-agent.nousresearch.com/docs), using a Matrix homeserver to carry AI communication. It implements message filtering and native Hermes command mapping — routing Hermes responses through threadline-rendered Matrix threads — and is designed for private/personal mobile access to Hermes. Built in Kotlin and Jetpack Compose, backed by the [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk) Android FFI bindings.

> **Note:** This is a development preview (v0.1.0). It is not a product of, or officially affiliated with, the Matrix.org or Element teams.

## Features

- **Login with your own homeserver** — enter any homeserver URL directly.
- **Sessions & threads** — bind a single Matrix room as the Hermes control channel; each thread within that room is treated as a separate Hermes session, sorted by the timestamp of the latest reply.
- **Rich messaging** — Markdown and HTML formatting with full rendering.
- **Media & attachments** — send and receive images, audio, video, and voice messages.
- **Message reactions** — react to messages with emoji.
- **Push notifications** — ntfy-based notifications are implemented. Official/system notification channels are not implemented yet.
- **E2EE not supported** — messages are **not** end-to-end encrypted; they are transmitted in plain text over the Matrix transport (HTTPS). Do not use this app for sensitive communications requiring end-to-end encryption.

## Requirements

- **Android:** 8.0 (API 26) or higher.
- **Native ABI:** `arm64-v8a` (other ABIs are not currently shipped).
- **Java 17** for building.

## Cloning

The Android SDK module lives in a separate repository and is consumed as a **git submodule**. You must clone with submodules:

```bash
# Clone including the SDK submodule in one step:
git clone --recurse-submodules https://github.com/liizfq/hermes-threadline.git
```

If you already cloned without `--recurse-submodules`, initialise and fetch the SDK submodule with:

```bash
git submodule update --init --recursive
```

The SDK submodule is tracked at `sdk-local/`. Until it is checked out the `sdk-local/` directory will be empty, and the project **will not build** — Gradle expects the `:sdk-local` module to be present.

The upstream SDK repository is [github.com/liizfq/hermes-threadline-sdk](https://github.com/liizfq/hermes-threadline-sdk.git). It ships the pre-generated Kotlin FFI bindings and the prebuilt `arm64-v8a` native shared library (`libmatrix_sdk_ffi.so`, tracked via Git LFS). You do **not** need a Rust toolchain to build the app.

## Build

This repository references the SDK via the `:sdk-local` submodule so you can build without recompiling the Rust code.

### From the command line

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Debug APK
./gradlew :app:assembleDebug

# Release APK (R8/resource shrinking enabled)
./gradlew :app:assembleRelease

# Release AAB (for Play Store upload)
./gradlew :app:bundleRelease
```

After building, the APK/AAB outputs are located at:

- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`
- `app/build/outputs/bundle/release/`

### Unsigned vs. signed release APK

By default, `assembleRelease` produces an **unsigned** APK (no `signingConfig` is applied). This keeps the public repository free of any commit history containing credentials.

To produce a **locally signed** release APK, supply credentials one of two ways — Gradle picks them up automatically (see `app/build.gradle.kts`):

**Option A — environment variables:**
```bash
export RELEASE_STORE_FILE=/path/to/your.keystore
export RELEASE_STORE_PASSWORD="your-store-password"
export RELEASE_KEY_ALIAS="your-key-alias"
export RELEASE_KEY_PASSWORD="your-key-password"
./gradlew :app:assembleRelease
```

**Option B — git-ignored `keystore.properties` file at the project root:**

Create `keystore.properties` in the repository root (this file is git-ignored — it will never be committed):

```properties
RELEASE_STORE_FILE=/path/to/your.keystore
RELEASE_STORE_PASSWORD=your-store-password
RELEASE_KEY_ALIAS=your-key-alias
RELEASE_KEY_PASSWORD=your-key-password
```

Then build normally:

```bash
./gradlew :app:assembleRelease
```

When all four values are present and the store file exists, Gradle applies a `userReleaseSigning` signing config and outputs a verifiable signed APK. When any value is missing, it falls back to producing an unsigned APK with a clear message in the build log — the build does not fail.

> **Debug builds** and **tests** are unaffected: they always use the bundled debug keystore, and never require release credentials.

### Using the sync-and-build script

A convenience script is provided at `scripts/sync-and-build.sh`. It syncs the project over SSH to a remote build host, runs Gradle, and optionally installs the debug APK.

Required environment variables:

| Variable            | Description                                                  |
| ------------------- | ------------------------------------------------------------ |
| `SYNC_SSH_KEY`      | Path to the SSH private key for the build host.              |
| `SYNC_SSH_HOST`     | SSH destination (e.g. `user@host`).                          |
| `SYNC_REMOTE_DIR`   | Remote directory to sync into.                               |
| `SYNC_ADB_DEVICE`   | *(Optional)* ADB device serial for `--install` / `--install-device`. |

> **Note:** this script works on the sync host; remember that the remote checkout must also have the SDK submodule initialised (`git submodule update --init --recursive`) before building.

```bash
export SYNC_SSH_KEY="$HOME/.ssh/host_key"
export SYNC_SSH_HOST="user@my-builder"
export SYNC_REMOTE_DIR="/home/user/hermes-android"

./scripts/sync-and-build.sh                 # sync + build
./scripts/sync-and-build.sh --install        # sync + build + install on default emulator
```

## Run / Install

1. Check out and initialise the SDK submodule (`git submodule update --init --recursive`).
2. Build the debug APK as described above.
3. Copy the APK to your device and open it, or use `adb install`.
4. Grant camera and microphone permissions when prompted for those features.

## Configuration

On first launch the app asks for your homeserver URL; after that, log in with your Matrix credentials. There is no hardcoded or default server — you must supply your own.

You can switch UI language, adjust chat font size, and manage push notification channels from the in-app Settings screen.

## Architecture

The app follows a layered MVVM architecture:

- **Presentation** — Jetpack Compose UI with `ViewModel`s, Hilt dependency injection, and Navigation Compose.
- **Domain** — domain models and use cases.
- **Data** — repository patterns over the matrix-rust-sdk FFI layer and on-device `SharedPreferences` for local session and credential caching.
- **FFI / SDK** — the matrix-rust-sdk FFI bindings exposed through the `:sdk-local` submodule.

Major dependencies:

- Jetpack Compose + Material 3
- Dagger Hilt
- AndroidX Navigation, Lifecycle
- [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk) (via the `:sdk-local` submodule)
- Media3 ExoPlayer for audio/video playback
- Coil for image loading
- OkHttp for ntfy push transport
- unifiedpush-android-connector for UnifiedPush support
- Element X wysiwyg for rich HTML composition/rendering

### SDK module

The `:sdk-local` module is a **separate repository** ([liizfq/hermes-threadline-sdk](https://github.com/liizfq/hermes-threadline-sdk.git)) consumed as a git submodule. It ships pre-generated Kotlin FFI bindings (under `sdk-local/src/main/kotlin/org/matrix/rustcomponents/sdk/` and `sdk-local/src/main/kotlin/uniffi/`) along with the prebuilt native shared library (`libmatrix_sdk_ffi.so`, tracked via Git LFS). This means the project can be built without a Rust toolchain. The upstream reference is [github.com/matrix-org/matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk).

The native library currently ships only for `arm64-v8a`.

Because the SDK is a submodule, the project **cannot build until the submodule is checked out** — a fresh clone must be run with `git clone --recurse-submodules` or followed by `git submodule update --init --recursive`.

> **`compileSdk` mismatch notice:** `sdk-local` is configured with `compileSdk = 36` while the `:app` module targets `compileSdk = 35`. This may produce a warning during build but does not block the build. It is tracked as a known issue and will be aligned in a future update.

If you need to update the SDK bindings or native library, make the changes in the SDK submodule repository and update the submodule pointer in this repository.

## Privacy & Security

- Connections travel over HTTPS; the network security configuration disallows cleartext traffic and trusts only system certificate authorities.
- No telemetry or analytics SDK is bundled or configured in the app.
- Push notifications are routed through the UnifiedPush framework — choose your own distributor or use the built-in ntfy channel. Endpoint URLs, access tokens, and UnifiedPush instance identifiers are stored locally on the device via `SharedPreferences`.

## Known limitations

- Only `arm64-v8a` is currently supported.
- **compileSdk mismatch:** `:sdk-local` is configured with `compileSdk = 36` while `:app` targets `compileSdk = 35`. This produces a warning during build but does not block the build. It is tracked for alignment in a future update.
- **Native ABI:** the app only ships the arm64-v8a native ABI (`libmatrix_sdk_ffi.so`). Devices on other ABIs (e.g. armeabi-v7a, x86_64) will crash at native library load because no alternative is bundled.
- **Thread timeline stability:** the app uses the SDK's focused-timeline API for threads. Because focused timelines are thread-scoped views with their own timeline loading and live-update streams, some edge cases remain around cold-start/history-loading and after process death or background/foreground transitions. The newer `refresh_thread` API (present in the SDK FFI bindings but **not yet integrated** into this app) is the intended path to mitigate these; until then, edge-case reloads or stale views can still occur.
- **Thread back-pagination:** due to the SDK's focused-timeline model, reaching the top of a very long thread may require manual (user-triggered) back-pagination; the automatic page-back triggers are conservative and are driven by `ActiveThread`'s `paginateOnce` / auto-paginate heuristic (`MIN_MESSAGES_BEFORE_INITIAL_PAGINATE`).
- **Session discovery:** when a session is started on another client, the app picks it up via a `DiscoveryListener` on the room timeline (debounced refresh). This covers real-time detection, but may not catch every historical-update edge case during initial sync.
- **Formatted messages and spaces:** some edge cases in formatted messages, threads, and group spaces may not yet be fully handled.
- **No license file:** no license file is present in this repository. Until one is added, the project is **not licensed for redistribution, modification, or commercial use** (see the License section below).

## Contributing

Contributions are welcome. Before opening a pull request:

1. Check out the SDK submodule (`git submodule update --init --recursive`).
2. Run `./gradlew :app:testDebugUnitTest` and ensure tests pass.
3. Test on a real or emulated Android 8.0+ device.
4. Keep changes focused — if you are adding a feature, consider splitting UI, domain, and data changes into separate commits.
5. If your change touches the SDK bindings or native library, the change belongs in the [SDK submodule repository](https://github.com/liizfq/hermes-threadline-sdk.git) — update it there and bump the submodule pointer here.

## License

No license file is present in this repository. Until one is added, the project is **not licensed for redistribution, modification, or commercial use**. If you are interested in contributing or reusing the code, please open an issue to discuss licensing.

## Acknowledgements

- [matrix-rust-sdk](https://github.com/matrix-org/matrix-rust-sdk) — upstream Rust SDK and Android FFI bindings.
- [UnifiedPush](https://unifiedpush.org/) — the Android push-notification framework.
- [Element X](https://github.com/element-hq/element-x-android) — for the wysiwyg rich-text component.
