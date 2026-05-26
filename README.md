# Intelligent Reminder App

An Android app that turns natural-language conversation into smart alarms and
reminders, fully offline. Built around a small on-device LLM (Gemma 3 1B-IT via
MediaPipe `LlmInference`) for the language-understanding bits the rule-based
parser can't handle on its own.

Two tabs to start:

1. **Chat** — Gemini-style dark chat. "Remind me to submit my assignment in 3
   days" → the agent figures out cadence, sets an alarm, sets pre-deadline
   notifications, learns your habits.
2. **Reminders** — A chronological list of everything the agent has scheduled.
   Daily activities first, then weekly, then monthly, then one-offs.

A third "Improvements" tab is in the design but not built yet.

## Why offline

Nothing about parsing "alarm at 7am" needs a cloud LLM. Keeping it on-device
makes it private, free to run, and works on planes / off-grid.

## Status

Scaffolding stage. See `docs/RESEARCH.md` for the design decisions and
`docs/SETUP.md` for how to build.

## Modules

- `app` — Compose UI, ViewModels, Activity, services
- `:domain` — pure-Kotlin data classes + business rules + dependency graph
- `:data` — Room DB, DataStore, AlarmScheduler (AlarmManager wrapper)
- `:engine` — ReminderEngine, PreferenceLearner, ConflictResolver
- `:nlu` — RuleBasedParser + LlmAdapter interface
- `:ml` — MediaPipe Gemma adapter

All non-Android modules are unit-testable with no emulator.
