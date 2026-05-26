# iOS port analysis

How much of this would you have to rewrite to ship the same app on iOS, and
what gets weird on the iOS side.

## TL;DR

A clean iOS port is roughly **4–5 weeks of work for one engineer**:

- **~2 weeks**: code sharing decision + KMP refactor (or duplicate-in-Swift)
- **~1 week**: SwiftUI port of the two tabs + chat
- **~1 week**: on-device LLM via MLX Swift or MediaPipe iOS
- **~3–5 days**: local-notification scheduling (the 64-pending-notification
  limit changes the design)
- **~3–5 days**: testing, polish, App Store entitlements

The chat + reminder engine logic is portable. The alarm/notification model
and the model-execution stack are the two areas that need genuine rework.

## Module-by-module

### `domain/` — pure Kotlin
**Portable.** No Android dependencies. Two ways:
1. **Kotlin Multiplatform (KMP)**. Move to `commonMain`; compiles to a
   Swift-callable ObjC/Swift framework. Lets the two apps stay in lockstep
   on business rules.
2. **Swift duplicate**. Translate Reminder, Recurrence, DependencyGraph to
   Swift structs. ~600 lines of Swift; ~2 days. Less infra, more drift.

For one engineer shipping fast: **duplicate**. For a team or long-term:
**KMP**, despite the build-tool tax.

### `nlu/RuleBasedParser` — pure Kotlin
**Portable**, same options. Regex flavors differ slightly between Java's
`Pattern` and Swift's `NSRegularExpression`, but the patterns themselves
work after minor escaping tweaks.

### `nlu/LlmAdapter` interface — pure Kotlin
**Portable** as KMP. Swift implementation lives platform-side.

### `ml/MediaPipeGemmaAdapter` — Android-only
**Rewrite.** Options on iOS:

| Stack | Pros | Cons |
|---|---|---|
| **MLX Swift** (Apple's on-device ML framework, Apple Silicon optimized) | Native Swift API. Gemma 3 1B, Llama, Mistral, Qwen all run in MLX format. Fastest on iPhone 15 Pro+ chips. Active development. | iOS 17+. Models need to be in MLX format (HuggingFace ships them, or convert with `mlx_lm.convert`). |
| **MediaPipe Tasks for iOS** (Google's framework) | Direct API parity with our Android code. Same `LlmInference`-style class. Same `.task` model files. | Less integrated with Apple silicon than MLX. |
| **llama.cpp with Swift wrapper** | Mature, runs anywhere. GGUF model files. | More work to bridge; less battery-optimal. |
| **Apple Foundation Models** (iOS 18+) | Zero install, no model file to ship. | Apple-controlled prompt format. Limited tool-calling support. Newer (changing fast). |
| **Core ML** (after converting Gemma to Core ML) | First-class Apple integration. Neural Engine. | Conversion tooling is fragile for LLMs; quantization choices matter. |

**Recommendation: MLX Swift.** Cleanest Swift integration, best perf on
modern iPhones, easy to swap Gemma → other models later.

### `data/db` — Room (SQLite via Android)
**Rewrite.** Options:

- **GRDB.swift** — Swift-first SQLite library. Most ergonomic match for
  Room's DAO style. Recommended.
- **SwiftData** (iOS 17+) — Apple's new persistence framework. Conceptually
  closer to Core Data; nicer for SwiftUI binding.
- **Core Data** — heavier, more iOS-native.

Same schema works either way (Reminder, ChatMessage, Preference). About 1
day of work for an experienced Swift dev.

### `data/alarm` — `AlarmManager` + `BroadcastReceiver`
**Largest semantic change.** iOS doesn't have an `AlarmManager.setAlarmClock`
equivalent.

- **Local notifications** via `UNUserNotificationCenter` is the only path.
- iOS limit: **64 pending local notifications** per app. For recurring
  daily reminders, can't schedule all future ones — schedule the next 30
  days and refresh in `applicationDidBecomeActive`.
- **Critical alarms** (bypass DND, full volume) require the Critical Alerts
  entitlement, which Apple grants only to apps with a clear medical /
  safety justification. We don't qualify.
- "Alarm" type → high-priority notification with custom sound + critical
  bit (best-effort). Visually less impressive than Android's lockscreen
  takeover.
- "Notification" type → standard notification.

### `engine/ReminderEngine` — pure Kotlin
**Portable.** Same logic. Only the `AlarmScheduler` dependency is platform-
specific (Swift impl wraps `UNUserNotificationCenter`).

### `engine/PreferenceLearner` — pure Kotlin
**Portable.** Storage swap: DataStore → UserDefaults (for small profiles)
or a file in the Documents directory (for larger ones).

### `ui/` — Jetpack Compose
**Rewrite** as SwiftUI. The mental model is similar; the porting takes
about as long as writing it fresh once you know the SwiftUI patterns.

Two tabs → `TabView` with two `NavigationStack`s. Chat → `ScrollView` of
message bubbles, with `.scrollIndicators` and `.scrollDismissesKeyboard`.
Reminders list → `List` with `Section`s.

Compose → SwiftUI conversion is ~1 week for our screen set.

## Hybrid: Compose Multiplatform?

Compose for iOS (still beta-ish but viable) could share the UI layer too —
costs a slightly heavier framework size and some friction in animations /
gestures, but you'd ship from one codebase.

For chat + a list view this is a credible option. The on-device LLM and
notification work are still platform-specific regardless.

## What you can't share, no matter what

| Thing | Why |
|---|---|
| Notification scheduling | Different system APIs, different constraints (Android: alarms; iOS: 64-pending limit) |
| Permission flows | API-shaped differently |
| Background work | WorkManager vs BackgroundTasks framework |
| LLM runtime | MediaPipe vs MLX vs llama.cpp — different deploy formats |
| Lockscreen / DND behaviour | Each OS exposes a different surface |

## Recommended path for this project

1. **Phase 1**: Ship Android alone. Validate the product.
2. **Phase 2**: Extract `domain/`, `nlu/RuleBasedParser`, and `engine/` into
   a KMP `:shared` module. No iOS app yet — this is plumbing for later.
3. **Phase 3**: Build the iOS app. SwiftUI for UI, MLX Swift for LLM,
   `UNUserNotificationCenter` for scheduling, the shared KMP module for
   logic.

This sequencing means the Android app keeps shipping while the shared
layer matures; the iOS app benefits from a known-good engine when it
arrives.
