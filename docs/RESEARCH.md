# Research and architecture notes

## On-device LLM choice

### Why not Kimi
Moonshot's Kimi models (K2, Kimi-VL) are large MoE models. Even the smallest
deployed variant has ~16B total parameters; activated parameters during
inference are ~3B but the full weights must be resident. That puts them
firmly in the server-tier category. Not viable on a phone.

### What does run on a phone (2026)

| Model | Size (int4) | RAM at runtime | Notes |
|---|---|---|---|
| **Gemma 3 1B-IT** | ~530 MB | ~1.5 GB | First-class Android support via Google AI Edge / MediaPipe `LlmInference`. JSON output capable. **This project's default.** |
| Gemma 3 4B-IT | ~2.5 GB | ~4 GB | Sharper, heavier. Overkill for our intents. |
| Qwen 2.5 1.5B Instruct | ~900 MB | ~2 GB | Excellent structured-output. Runs via llama.cpp/MLC. |
| Llama 3.2 1B Instruct | ~700 MB | ~2 GB | Meta's mobile-targeted small model. |
| Phi-3.5 Mini (3.8B) | ~2.2 GB | ~3.5 GB | Strong reasoner. |

We picked **Gemma 3 1B-IT** because:
1. Google AI Edge ships a stable Kotlin API (`MediaPipe LlmInference`).
2. ~530 MB int4 is the smallest "real" LLM that still does multi-turn chat well.
3. Officially supported on Android, single `.task` file, no JNI gymnastics.
4. Inference fast enough (20-40 tok/s on a Pixel 7-class chip) that the chat
   feels alive.

### Hybrid pipeline

Most of our user utterances are highly templated:

```
remind me at 7
set alarm for 6:30 tomorrow
remind me an hour before lunch
delete my lunch alarm
done — I submitted the assignment
```

A small set of regexes + a date library (`java.time` + a few helpers like
`PrettyTime` patterns) cover ~80% of inputs and run in microseconds. The LLM
only fires when:

1. The rule parser returns `Ambiguous` (no high-confidence intent).
2. We need a *conversational* response ("Based on last time, want every
   3 hours starting tomorrow?").
3. We need to *infer periodicity* from history.

This keeps battery and latency in check. It also means the app still functions
(in a degraded way) if the model file can't be loaded.

## Domain model

```
Reminder
  id: UUID
  title: String
  description: String?
  type: ALARM | NOTIFICATION
  triggerAt: ZonedDateTime
  recurrence: Recurrence?
  parentId: UUID?               # for relative-to-event reminders
  relativeOffset: Duration?      # negative = "before parent"
  groupId: UUID?                 # e.g. "lunch-cluster"
  status: ACTIVE | COMPLETED | SKIPPED | DELETED
  exceptions: List<LocalDate>    # skipped dates for recurring
  metadata: Map<String,String>   # tags, categories
  createdAt / updatedAt

Recurrence
  pattern: DAILY | WEEKLY | MONTHLY | CUSTOM
  interval: Int                  # every N
  daysOfWeek: Set<DayOfWeek>?    # for WEEKLY
  endDate: LocalDate?

PreferenceProfile (singleton in DataStore)
  categoryCadences: Map<Category, CadenceProfile>
  defaultLeadTimes: Map<Category, Duration>
  rejectedSuggestions: List<RejectedSuggestion>
```

## Alarm vs Notification

| | Alarm | Notification |
|---|---|---|
| Mechanism | `AlarmManager.setAlarmClock` | `setExactAndAllowWhileIdle` + heads-up notification |
| User experience | Lockscreen takeover, can wake device, ringtone | Silent or sound, dismissable, doesn't bypass DND |
| When | "wake me", explicit critical time, deadline moment | Lead-up nudges, ambient reminders |
| Default | Conservative — only when user says "alarm" or it's the literal deadline moment | Everything else |

## Dependency graph

```
              ┌───────────────────────────────┐
              │      lunch (12:00 daily)      │
              └──────┬────────────────┬───────┘
                     │                │
        parentId=lunch│      parentId=lunch
        offset=-1h    │      offset=-15m
                     ▼                ▼
        ┌────────────────┐    ┌──────────────────┐
        │ medicine 11:00 │    │ wash hands 11:45 │
        └────────────────┘    └──────────────────┘
```

When user says "move lunch to 1:30", the engine:
1. Locates `lunch` reminder
2. Queries all reminders with `parentId == lunch.id`
3. Recomputes each child's `triggerAt = parent.triggerAt + relativeOffset`
4. Updates the system alarm registrations for parent + each child
5. Returns a chat message: "Moved lunch to 1:30 PM. Medicine moved to 12:30 PM,
   wash hands moved to 1:15 PM."

If user explicitly sets a child's time independently, we *detach* it from the
parent (`parentId = null`) and remember the detach decision so we don't keep
re-attaching.

## Preference learning

Stored in DataStore as JSON. Key idea: classify a reminder into a *category*
("assignment", "meal", "medicine", "meeting", "exercise", "appointment", "wake",
"travel", "errand", or "uncategorized"). When the user picks a cadence for that
category, increment a confidence counter; after 3 confirmations, *autonomously*
apply that cadence for the next reminder in the same category, but always show
the user what we did and offer a "change" chip.

We also remember *rejected* suggestions — if the user three times rejected
"every 3 hours" for assignments, we stop suggesting it.

## Periodicity inference

Two signals:

1. **Explicit**: "every day", "weekly", "every Monday" — trivially parsed.
2. **Implicit**: user creates a one-time reminder for a context we've seen 3+
   times within a tight cadence (e.g., medicine at 9 AM three days in a row).
   On the fourth one, ask: "Want me to make this daily?"

When user says "done" or cancels:

- If `recurrence != null`: skip this occurrence (add to `exceptions`), tell
  user next fire time.
- If one-time: delete + ask about dependents.

## Edge cases

| # | Case | Handling |
|---|---|---|
| 1 | "Remind me about X in 30 min" | Relative-to-now, no parent. |
| 2 | "Remind me an hour before lunch" with no lunch reminder | Engine asks for lunch time first, creates both with dependency. |
| 3 | "Move lunch to 1:30" | Move parent + cascade all children. |
| 4 | DST / timezone change | Store `LocalDateTime` + `ZoneId`; recompute next fire via `BootReceiver` + `ACTION_TIMEZONE_CHANGED`. |
| 5 | Device reboot | `BootReceiver` re-registers all active alarms. |
| 6 | "Cancel all medicine reminders for today" | Scope-aware deletion: by tag + by date. |
| 7 | "I submitted the assignment" (deadline in 2 days) | "Done" intent → cancel main + all lead-up siblings. |
| 8 | Recurring with holiday skip | Add exception date. |
| 9 | Model file missing | App degrades to rule-based parser + templated responses. |
| 10 | Two-parent conflict (user manually set child time after parent moved) | Detach child from parent; remember decision. |
| 11 | Snooze | Notification action "snooze 10m / done / remind in 1h". |
| 12 | Permissions | Request `SCHEDULE_EXACT_ALARM` (API 31+), `POST_NOTIFICATIONS` (API 33+), `USE_FULL_SCREEN_INTENT` on first reminder. |
| 13 | Quiet hours | If reminder would fire in user's defined quiet window, shift to next allowed slot. |
| 14 | App backgrounded for days | `WorkManager` periodic check + `BootReceiver` keeps things consistent. |

## Out of scope for v1

- Location-based reminders (needs GPS, more permissions)
- Voice input (could be added — Android `SpeechRecognizer`)
- Sync across devices (would need a backend)
- Sharing reminders with others

## Tech stack

- Kotlin 2.0
- Jetpack Compose + Material 3
- Room 2.7
- DataStore Preferences
- Hilt (DI)
- Kotlinx Coroutines + Flow
- MediaPipe `LlmInference` for Gemma 3 1B
- JUnit5, Truth, Turbine, MockK, Robolectric, Compose UI test

Min SDK 26 (Android 8.0). Target SDK 35.
