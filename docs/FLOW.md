# Data and interaction flow

How a user message travels from chat input to a phone notification, and
where state lives along the way.

## End-to-end sequence

```
User types: "remind me to take medicine an hour before lunch"
       │
       ▼
ChatScreen (Compose)
  - keystrokes update local state (composer text)
  - on Send → ChatViewModel.send(text)
       │
       ▼
ChatViewModel
  - inserts USER ChatMessageEntity (Room: chat_messages)
  - calls ReminderEngine.handle(text)
       │
       ▼
ReminderEngine
  ┌───────────────────────────────────────┐
  │ 1. RuleBasedParser.parse(text)        │
  │      → Intent.Create(                 │
  │          title="take medicine",       │
  │          relativeTo=RelativeAnchor(   │
  │            match=ReminderMatch("lunch"),
  │            offset=-1h),               │
  │          category=MEDICINE)           │
  │    If Ambiguous, ask LLM (Gemma 3 1B) │
  └───────────────────────────────────────┘
       │
       ▼
  ┌───────────────────────────────────────┐
  │ 2. Resolve relativeTo:                │
  │    - reminderDao.search("lunch") →    │
  │      find existing lunch reminder     │
  │    - parent.triggerAt + (-1h) →       │
  │      this reminder's triggerAt        │
  │    - if no lunch found:               │
  │      reply "when is lunch?"           │
  │      and stop (no DB writes)          │
  └───────────────────────────────────────┘
       │
       ▼
  ┌───────────────────────────────────────┐
  │ 3. Build Reminder domain object       │
  │    - parentId = lunch.id              │
  │    - relativeOffset = -1h             │
  │    - groupId = lunch.groupId (or new) │
  │    - recurrence = lunch.recurrence    │
  │      (inherits from parent)           │
  └───────────────────────────────────────┘
       │
       ▼
  ┌───────────────────────────────────────┐
  │ 4. Persist:                           │
  │    - reminderDao.replaceCluster(...)  │
  │      (transactional)                  │
  │    - learner.observeCreate(reminder)  │
  │      → updates PreferenceProfile in   │
  │      DataStore                        │
  └───────────────────────────────────────┘
       │
       ▼
  ┌───────────────────────────────────────┐
  │ 5. Schedule:                          │
  │    AndroidAlarmScheduler.schedule(    │
  │      reminder)                        │
  │    → AlarmManager.setExactAndAllow    │
  │      WhileIdle(triggerMs, pendingI)   │
  └───────────────────────────────────────┘
       │
       ▼
  ┌───────────────────────────────────────┐
  │ 6. Compose reply:                     │
  │    "Got it — medicine at 12:00 PM,    │
  │     one hour before lunch."           │
  │    (LLM or templated, depending on    │
  │     whether Gemma is loaded)          │
  └───────────────────────────────────────┘
       │
       ▼
ReminderEngine returns EngineReply
       │
       ▼
ChatViewModel
  - inserts AGENT ChatMessageEntity
  - updates ui state with pendingSuggestion (if any)
       │
       ▼
Compose re-renders chat list with new message
```

### Then, an hour before lunch:

```
AlarmManager fires PendingIntent
       │
       ▼
AlarmFiredReceiver.onReceive(intent)
  - extracts reminderId from intent extras
  - reminderDao.byId(reminderId)
  - NotificationManager.notify(title, body, action chips)
  - If recurring: schedule next occurrence
       │
       ▼
User sees heads-up notification
  - tap → opens MainActivity with reminderId extra
  - swipe to dismiss
  - action "snooze" / "done" / "remind in 1h"
```

### Reboot / timezone change:

```
ACTION_BOOT_COMPLETED (or TIMEZONE_CHANGED)
       │
       ▼
BootCompletedReceiver
  - reminderDao.activeOnce() → all ACTIVE rows
  - AlarmScheduler.rescheduleAll(reminders)
  - all PendingIntents re-registered with the OS
```

## Where state lives

| Store | Holds | Notes |
|---|---|---|
| `reminders` Room table | All reminders ever created (ACTIVE, COMPLETED, SKIPPED, DELETED). Flat shape; recurrence, relative-anchor, group all in same row. | Indexed on `triggerEpochMs`, `status`, `parentId`, `groupId`. |
| `chat_messages` Room table | Full conversation log. | Trimmed to last 100 by background task (TODO). |
| DataStore `reminder_prefs` | `PreferenceProfile` JSON: category cadences, recurring candidates, rejected suggestions, quiet hours. | Survives uninstall? No — backup excluded by `data_extraction_rules.xml`. |
| `AlarmManager` (system) | Currently-armed PendingIntents. Each reminder → one PendingIntent keyed by `reminder.id.hashCode()`. | Cleared on reboot — rebuilt by `BootCompletedReceiver`. |
| `NotificationManager` (system) | Posted notifications. | User dismissable; can also be programmatically cancelled when reminder is marked done. |
| In-memory `_profile` StateFlow | Current `PreferenceProfile` for fast reads from `PreferenceLearner.suggestFor`. | Sourced from DataStore at app start. |
| In-memory `messages` StateFlow | Last 200 chat messages for the UI. | Sourced from `chat_messages` table via Room Flow. |

## Why this shape

- **Single Room table for all reminders.** Tried two tables (alarms + relative-reminders) first; merging them at query time was painful. Flat shape with nullable `parentId`/`relativeOffset` is uglier but every operation (cascade, list, search) becomes one query.
- **Dependency graph rebuilt fresh each cascade.** Only hundreds of reminders max; cheaper to load them all than to maintain a separate graph table.
- **Preferences in JSON, not relational.** Profile is read in bulk, written in bulk, never queried by field. JSON in DataStore is the simplest match.
- **Chat history in Room, not just memory.** Survives backgrounding; lets the LLM use recent turns as context across app launches.

## What's currently missing (TODOs)

1. `recentTurns()` in `ReminderEngine` returns an empty list — should query `ChatDao` for the last ~6 turns to pass to the LLM as context. Easy fix.
2. No background cleanup of old `chat_messages` rows. Add a `WorkManager` periodic worker.
3. Permission flow: manifest declares `SCHEDULE_EXACT_ALARM` + `POST_NOTIFICATIONS`, but the app never requests them at runtime. Should ask on first reminder creation.
4. Snooze / Done notification actions: not wired (notification posts but has no action buttons yet).
5. Dependency on `INTERNET` permission only for model download — should be removed if model is sideloaded.
6. Recurrence: WEEKLY interval > 1 with explicit days-of-week has an edge case in `advanceWeekly` — not yet tested.
