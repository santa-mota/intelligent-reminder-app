# Setup

## Prerequisites

- Android Studio Ladybug+ (or AGP 8.7+ on the command line)
- JDK 17
- Android SDK with platform 35 installed
- At least one connected device or emulator running API 26+

## First build

```bash
./gradlew :app:assembleDebug
```

The first build downloads Gradle 8.10 and pulls dependencies. Should take ~5
min on a clean machine.

## Running tests

```bash
./gradlew test                     # JVM unit tests (fast)
./gradlew connectedDebugAndroidTest # device/emulator tests
```

## The on-device model

The app uses Gemma 3 1B-IT in `.task` format (~530 MB).

It is **not** bundled in the APK. On first launch, the app downloads it from
Kaggle (Google's official distribution) into the app's private files dir at:

```
/data/data/com.santamota.reminder/files/models/gemma-3-1b-it-int4.task
```

For development, you can sideload a copy you've already downloaded:

```bash
adb push gemma-3-1b-it-int4.task /sdcard/Download/
# then in the app: Settings → Use sideloaded model
```

Until the model loads, the rule-based parser handles everything; the chat just
gives templated responses for complex queries.

## Permissions requested

| Permission | When | Why |
|---|---|---|
| `SCHEDULE_EXACT_ALARM` | First reminder | Needed for `AlarmManager.setAlarmClock` on API 31+ |
| `POST_NOTIFICATIONS` | First reminder | API 33+ runtime notification permission |
| `USE_FULL_SCREEN_INTENT` | Manifest | For wake-up style alarms |
| `RECEIVE_BOOT_COMPLETED` | Manifest | Re-register alarms after reboot |

No internet permission, no location, no contacts. Stays offline.
