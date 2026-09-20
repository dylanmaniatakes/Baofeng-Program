# Building Baofeng Programmer

## Development

Open `Android/` in Android Studio, or set `JAVA_HOME` to JDK 17/21 and `ANDROID_HOME` to your Android SDK. Install platform 35, then run:

```sh
cd Android
./gradlew :app:assembleDebug :radio-core:test :app:lintDebug
```

No signing credentials or radio backups are required for development. The optional local-image test is skipped unless `-PradioBackup=/absolute/path/to/original.bfp` is supplied.

## Signed Releases

Create a private signing key with Java's `keytool`, then put `signing.properties` in a `.signing/` directory at the repository root. This entire directory is ignored by Git.

```properties
storeFile=.signing/release.p12
storePassword=YOUR_STORE_PASSWORD
keyAlias=release
keyPassword=YOUR_KEY_PASSWORD
```

`storeFile` is relative to the repository root. Do not commit keys, passwords, or personal radio data. Without this configuration, Gradle creates an unsigned release artifact; debug builds never use the release key.

```sh
cd Android
./gradlew :app:assembleRelease :radio-core:test :app:lintRelease
```

The signed APK is `Android/app/build/outputs/apk/release/app-release.apk`. Check it with Android SDK `apksigner verify --verbose --print-certs` before distributing it. Back up the release key and its password securely: future updates must use the same signing certificate. A separately signed APK cannot update an existing installation with another certificate.

## Modules

- `app`: Compose interface, local workspace, and document import/export.
- `radio-core`: image format, field codecs, backup format, driver, and transport interface.
- `transport-ble`: discovery, GATT lifecycle, serialized writes, and incoming-byte queue.

## UI Smoke Test

`tools/ui_smoke.py` exercises memory editing, setting navigation, themes, and small/large layouts on an emulator with an existing test workspace. It refuses physical devices. Screenshots are local test output, not repository assets. Do not use personal programming images as public fixtures.
