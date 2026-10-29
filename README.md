# VoiceTodo

Android app: speak a task, get it saved with its deadline, get
reminded before it is due. Speech recognition and parsing run entirely
on-device (sherpa-onnx + the rule parser). A user-configured DeepSeek /
OpenAI-compatible endpoint to refine parsing when online is planned, not built.

## Modules

- `parser/` — offline Chinese task and time extraction, plain Kotlin/JVM so it
  builds and tests without the Android SDK. Entry point:
  `TaskParser().parse(text, now)`.
- `app/` — the Android app (Kotlin, Compose, minSdk 26). Push-to-talk →
  SenseVoice int8 via sherpa-onnx, fully offline → parser → confirmation
  card → SQLite → one exact alarm per task (lead time, then due time;
  date-only tasks the evening before and the morning of). Notifications
  carry 完成 / 1小时后再提醒. Alarms are restored after reboot, clock
  changes and exact-alarm permission changes.

## Building

The speech runtime (sherpa-onnx 1.13.8 AAR) and model (~240 MB) are not in
git:

```bash
./scripts/fetch-model.sh
```

Then, with the Android SDK at `~/Library/Android/sdk` (see `local.properties`):

```bash
./gradlew :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk` (~280 MB, almost all
model). Debug builds also take input from adb, which is how the emulator was
tested without a voice:

```bash
adb shell am start -n app.voicetodo/.ui.MainActivity --es debug_text "明天下午三点开会"
adb shell am start -n app.voicetodo/.ui.MainActivity --es debug_wav /data/data/app.voicetodo/files/t1.wav
```

## Parser

```
normalize → split clauses → scan time atoms → join into mentions
          → read relation (之前 / 之后 / 提醒我) → resolve against now
          → strip time words into a title → group clauses into tasks
```

Guesses that are habits rather than facts (what "下周" means, when 下班 is,
whether a bare "3点" is afternoon) live in `ParserConfig`. Anything uncertain
comes back as a `Warning` plus alternatives for the confirmation card.

Holiday dates (lunar festivals, 清明) come from the Hong Kong Observatory
conversion tables for 2024–2045; ICU's ChineseCalendar was tried and gets
2027's 春节 wrong.

```bash
./gradlew :parser:test
```

The corpus is `parser/src/test/.../TaskParserTest.kt`. When a real utterance
parses wrong, add it there first.

### Known gaps

- Titles come from normalized text, so "整理三点建议" keeps its time-looking
  "3点" as written digits.
- Repeating tasks ("每天", "每周一") are flagged `RECURRING`, not modelled.
- No lunar dates beyond named festivals ("腊月二十三").
