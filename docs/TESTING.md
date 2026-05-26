# Testing guide

Three ways to see / test this app, in increasing fidelity.

## 1. Unit tests (no device, no emulator)

These run on the JVM and cover the domain + parser + engine + preference
learner — the parts you'll change most often.

```bash
cd /Users/shamahap/Desktop/Training/personal/intelligent-reminder-app
gradle :app:testDebugUnitTest
```

Reports:
- `app/build/reports/tests/testDebugUnitTest/index.html` (HTML)
- `app/build/test-results/testDebugUnitTest/*.xml` (CI-friendly XML)

To filter:
```bash
gradle :app:testDebugUnitTest --tests "com.santamota.reminder.nlu.*"
```

## 2. Compose preview (UI on laptop, no device)

Add `@Preview` annotations to any composable and Android Studio renders it
in a side panel. Fastest way to iterate on UI styling.

In Android Studio:

1. Open the project (File → Open → `intelligent-reminder-app/`).
2. Wait for Gradle sync (first time: ~5 min).
3. Open `app/src/main/.../ui/chat/ChatScreen.kt`.
4. Click "Split" in the top right of the editor → previews render in the
   right pane.

To make `ChatScreen` previewable without Hilt:

```kotlin
@Preview
@Composable
private fun ChatScreenPreview() {
    IntelligentReminderTheme {
        ChatScreenContent(  // refactor: extract a pure-state version
            messages = sampleMessages(),
            ui = ChatUiState(),
            onSend = {},
        )
    }
}
```

(The current `ChatScreen` takes a hilt viewModel; a small refactor splits
it into a `ChatScreenContent(state, callbacks)` + `ChatScreen(vm)` so the
content composable previews cleanly.)

## 3. Run on the Android emulator

Your machine already has these AVDs (run `~/Library/Android/sdk/emulator/emulator -list-avds`):

- `LI_Emulator_30`
- `Medium_Phone` / `Medium_Phone_API_36.0`
- `SDUI_Emulator`

To run the app on, say, `Medium_Phone`:

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
$ANDROID_HOME/emulator/emulator -avd Medium_Phone -no-snapshot-save -no-audio &
# wait ~30s for boot
adb wait-for-device
adb shell input keyevent 82   # unlock

# install + launch
gradle :app:installDebug
adb shell am start -n com.santamota.reminder/.ui.MainActivity
```

The first build takes 3-5 min (downloading deps). Subsequent rebuilds are
incremental, ~10-20s.

### Useful adb commands while testing

```bash
# Logs from our app
adb logcat -v color --pid=$(adb shell pidof com.santamota.reminder)

# Inspect the Room DB
adb shell run-as com.santamota.reminder cat /data/data/com.santamota.reminder/databases/reminders.db | sqlite3 :memory: ".tables"

# Trigger a reminder right now (for testing alarms)
adb shell am broadcast -a com.santamota.reminder.ALARM_FIRE --es reminderId "<id>"

# Reset state
adb uninstall com.santamota.reminder

# Time-travel
adb shell date 052517002026   # MMddHHmmYYYY
```

### Run UI tests on emulator

```bash
gradle :app:connectedDebugAndroidTest
```

This installs the app + test APK and runs `ChatScreenSmokeTest` against
a connected device/emulator.

## 4. Install on a physical Android phone

### One-time setup
1. On the phone: Settings → About phone → tap "Build number" 7 times to
   enable Developer Options.
2. Settings → System → Developer options → enable "USB debugging".
3. Plug the phone in. On the phone, accept the "Allow USB debugging" prompt.
4. `adb devices` should show your phone.

### Install the APK

```bash
gradle :app:assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or, to share the APK with someone else (e.g., yourself on a different
phone), upload `app-debug.apk` and they sideload it (Settings → Security →
Install unknown apps).

For a release-signed APK you'd need a keystore — see
[Android docs](https://developer.android.com/studio/publish/app-signing).

## 5. Setting up the on-device LLM

The Gemma 3 1B model isn't shipped in the APK (would balloon it to 600MB+).
Two options for development:

**(a) First-run download (production path)**
The app, on first launch, downloads from Kaggle (Google's official
distribution) into the app's private files dir. Network permission required.
Slow first launch (~2 min on good wifi).

**(b) Sideload (development path)**
Pre-download `gemma-3-1b-it-int4.task` from Kaggle, then:

```bash
adb push ~/Downloads/gemma-3-1b-it-int4.task /sdcard/Download/
adb shell run-as com.santamota.reminder mkdir -p files/models
adb shell run-as com.santamota.reminder cp /sdcard/Download/gemma-3-1b-it-int4.task files/models/
```

The `MediaPipeGemmaAdapter.tryInit()` will pick it up on next chat send.

Until you load the model, the app still works — the rule-based parser
handles ~80% of utterances, and the agent uses templated responses for the
rest. You'll see this in logs:

```
W/Gemma: Failed to load Gemma model: <file not found>
```

…and the app keeps going.

## What to manually test once it's installed

A scripted test pass that exercises the core promises of the app:

1. **Set a one-time reminder.** "Remind me at 3 PM to take a break."
   - Reminder appears in the Reminders tab.
   - At 3 PM (or change device clock to verify), notification fires.

2. **Set a recurring reminder.** "Remind me every day at 1 PM about lunch."
   - Appears under "Daily" in the Reminders tab.
   - After it fires once, next occurrence is scheduled automatically.

3. **Relative anchor cascade.** With lunch from (2) active:
   "Remind me to take medicine an hour before lunch."
   - Medicine reminder appears, time = lunch − 1h.
   - Then: "move lunch to 2 PM". Medicine should auto-shift to 1 PM.

4. **Mark done.** "I submitted the assignment" (after creating an
   assignment reminder).
   - Reminder vanishes from the list (status = COMPLETED).
   - Notification is cancelled if it was queued.

5. **Recurring skip.** "Lunch is done" (with daily lunch reminder active).
   - Today's lunch is skipped.
   - The reminder remains in the list but the next occurrence is tomorrow.

6. **Reboot.** Restart the device. Reminders should fire on time after
   reboot (the `BootCompletedReceiver` re-registers them).

7. **Timezone change.** Change device timezone. Reminders should fire at
   the same local time on the new TZ (because we store ZoneId per reminder).

## Common failure modes

| Symptom | Likely cause |
|---|---|
| App opens but bottom tabs don't appear | Hilt failed to inject ViewModel — check logcat for `IllegalStateException` from Dagger |
| Reminder created but never fires | Missing `SCHEDULE_EXACT_ALARM` permission on API 31+. Go to Settings → Apps → Intelligent Reminder → Alarms & reminders → enable. |
| Notification fires but no heads-up | Missing `POST_NOTIFICATIONS` permission on API 33+. Should be requested at runtime (TODO — currently relies on user enabling it manually). |
| LLM never responds smartly | Model file missing or failed to load. App falls back to rule-based parser. See "Setting up the on-device LLM" above. |
| First build takes forever | Normal — Gradle is downloading the AGP 8.7 + Compose BOM + Hilt. Subsequent builds are seconds. |
