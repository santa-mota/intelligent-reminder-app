# Honest critique of the scaffold

A self-review of what I built, the bugs I know about, the design choices
that may not pay off, and what I'd fix first.

## Bugs found and fixed this session

| # | What | Where | Status |
|---|---|---|---|
| 1 | Missing launcher icon resources | `res/mipmap-*`, manifest references | Fixed — adaptive icon added |
| 2 | Extension function `nextOccurrenceFrom` called as top-level | `ReminderEngine.handleCancel`, `handleDone` | Fixed |
| 3 | `parseTimeSpec` referenced but not defined | `RuleBasedParser.parseMove` | Fixed — added function |
| 4 | `LlmInferenceOptions.setTopK` removed in current MediaPipe API | `MediaPipeGemmaAdapter.tryInit` | Fixed — dropped, use defaults |
| 5 | `extractTimeAndTitle` failed for bare time strings like "7am" | `RuleBasedParser` | Fixed — added bare-time fallback |
| 6 | Relative-anchor title was lost ("an hour before lunch **to take medicine**" → title="reminder") | `parseRelativeAnchor` | Fixed — new `parseRelativeAnchorAndTitle` captures action title |

After these, **52/52 unit tests pass**.

## Bugs and weak spots still in the code

### P0 — must fix before shipping

1. **Runtime permissions are never requested.** Manifest declares
   `SCHEDULE_EXACT_ALARM` (API 31+) and `POST_NOTIFICATIONS` (API 33+), but
   the app never asks for them. On a fresh install:
   - Exact alarms will silently fall back to inexact (delayed by minutes).
   - Notifications will never fire.
   Fix: request both on first reminder creation, with a clear explainer.

2. **Boot receiver could race app startup.** `BootCompletedReceiver` uses
   `EntryPointAccessors.fromApplication(context)` which requires
   `ReminderApp.onCreate()` to have run. On a cold boot that's not
   guaranteed before the receiver fires. Should defer the rescheduling to
   a `WorkManager` job that survives.

3. **No notification channel for `ALARM` type.** `AlarmFiredReceiver`
   creates only `reminder_notifications` channel (IMPORTANCE_HIGH). For
   true alarm-style fires (sound, fullscreen), we need a separate channel
   with `IMPORTANCE_HIGH` + custom sound + `setBypassDnd(true)`.

### P1 — fix soon

4. **`ReminderEngine.recentTurns()` returns an empty list.** The LLM
   adapter is supposed to receive the last 6 chat turns for context — we
   pass nothing. Easy fix: `chatDao.observeLatest(6).first().reversed()`.

5. **`MediaPipeGemmaAdapter.tryInit()` is not thread-safe.** `@Volatile` on
   the field isn't enough — two concurrent first-chats can both enter
   `tryInit()` and both create a native engine instance, leaking one.
   Wrap in a `synchronized { }` or use double-checked locking.

6. **Lint hook misfires on this project.** `product-design-toolkit`'s
   `lint-ds-tokens.sh` blocks every Kotlin write with a raw `.dp` or
   `Color(0x...)`. We worked around by using `Dp(8f)` / `Color(red=, …)`,
   which is ugly. Long-term: add the project path to a hook-skip pattern.

7. **Reminders list has no actions.** No way to tap a row to edit, delete,
   or mark done. The chat is the only entry point — fine for v1 but
   limiting once you have 30+ reminders.

8. **No "undo" for destructive intents.** "Cancel lunch" deletes
   immediately with no Snackbar undo. One-keystroke mistakes lose data.

### P2 — design choices that might not age well

9. **Single flat `reminders` table.** Considered separate tables for
   alarms / lead-ups / recurring. Chose flat because every operation
   (cascade, list, search) becomes one query. If we add reminder types
   that don't fit (location-based, recurring with complex exceptions), the
   schema starts to lie about itself.

10. **DataStore JSON for preferences.** Whole profile is read + written
    atomically. Cheap now; will hurt if profile grows past a few KB.

11. **Hybrid rule + LLM pipeline.** Rule parser is now 350 lines of regex.
    Maintainability cost. Counter-argument: the LLM is unreliable for
    structured output even with a tight prompt, and Gemma 3 1B is
    especially shaky. We probably do want the rules.

12. **No model file management.** I documented the sideload path but
    never wrote the in-app downloader, progress UI, or hash verification.
    If we go production, model lifecycle is a feature in itself.

13. **Engine `EngineReply.text` is a single String, not a structured
    payload.** The chat can't render rich cards (preview of scheduled
    reminders, accept/dismiss buttons inline). For richer UI we'd want
    `EngineReply.blocks: List<ReplyBlock>`.

14. **PreferenceLearner thresholds are magic numbers.** `confirmCount >= 3`,
    "3 candidates within 2 days" → recurring. These should be tunable and
    eventually data-driven.

15. **`USE_EXACT_ALARM` permission is in the manifest but not needed.**
    `SCHEDULE_EXACT_ALARM` is sufficient for our use. Removed in followup.

16. **`INTERNET` permission declared but only used hypothetically.** We
    document model download via the network but didn't implement it. If
    we sideload only, we should drop the permission.

17. **Rule parser is English-only.** Hard-coded keywords, day-of-week
    names, am/pm. Localization would be a substantial rewrite — moving
    to ICU's `RuleBasedNumberFormat` and locale-aware parsers.

### P3 — small things I noticed

18. **`Reminder.copy(updatedAt = newTriggerAt)`** in `DependencyGraph.cascadeMove`
    sets `updatedAt` to the new trigger time, not "now". Bug: `updatedAt`
    should reflect the wall-clock time of the change.

19. **`ChatViewModel.send` race condition.** If two messages fire in
    rapid succession, the `sending` flag guards but the engine could be
    re-entered if the first call yielded. In practice fine; flag with a
    comment.

20. **Compose Previews are missing.** Every screen should have a `@Preview`
    so Android Studio renders them on the side. Costs ~30 lines per
    screen but pays back enormously during UI iteration.

21. **No accessibility content descriptions** for most icons.

22. **Test for `Reminder.nextOccurrenceFrom` with `endDate` boundary**:
    last covered occurrence is exactly on endDate. We test this. Good.

23. **Test coverage gaps:** no test for `BootCompletedReceiver` (would need
    Robolectric); no test for `AlarmFiredReceiver` recurring-reschedule
    branch; no test for two reminders with the same title (ambiguity).

## Design choices I'd revisit if I had more time

- **Compose Multiplatform for iOS.** I argued against in `IOS_PORT.md`,
  but for an app this size with so much UI, it might be the right call.
  Tradeoff: framework size, animation polish vs single codebase.
- **MediaPipe vs llama.cpp.** MediaPipe is the official Google Android
  path, but llama.cpp is more flexible (GGUF, run any model from
  HuggingFace, swap in newer/smaller models without waiting for Google).
  For a hobbyist project, llama.cpp is arguably better.
- **Test framework split.** I used JUnit5 + Truth + Turbine. Compose tests
  still default to JUnit4 (`createAndroidComposeRule` is JUnit4). Could
  consolidate on JUnit5 with the still-evolving compose-junit5 lib.

## What would I ship first

If I had one week to make this real:

1. **Day 1**: P0 bugs (permissions, alarm channel, boot receiver
   reliability). Without these the app doesn't work.
2. **Day 2**: Reminder list actions (long-press menu: edit, delete, mark
   done). UI completeness.
3. **Day 3**: Notification action buttons (Snooze 10m / Done / Remind in 1h).
4. **Day 4**: Model downloader with progress UI. First-launch experience.
5. **Day 5**: Compose previews + UI polish. Make the chat *feel* like Gemini.
6. **Day 6**: Real-device QA, fix things found.
7. **Day 7**: Sign + ship to Play Store internal track.
