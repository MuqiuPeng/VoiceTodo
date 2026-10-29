# VoiceTodo

Android app: speak or type a task in Chinese or English, get it saved with
its deadline, get reminded before it is due. The UI is in English and
Chinese (follows the system, or pick one in Settings → Language). Speech recognition and parsing run entirely
on-device (sherpa-onnx + the rule parser). A user-configured DeepSeek /
OpenAI-compatible endpoint to refine parsing when online is planned, not built.

## Modules

- `parser/` — offline task and time extraction, plain Kotlin/JVM so it builds
  and tests without the Android SDK. `TaskParser` for Chinese,
  `EnglishParser` for English; `looksEnglish(text)` picks one, so Chinese with
  English words in it ("明天跟OpenAI开会") stays with the Chinese rules.
- `app/` — the Android app (Kotlin, Compose, minSdk 26). One input bar at
  the bottom: tap to type, hold to talk (slide up to cancel). A drawer (menu
  button, or swipe from the left) holds 专有名词 and 设置 (speech pack,
  reminder permissions, recordings, updates, help). Task rows: swipe left to
  complete, swipe right to delete (with an undo); a spoken task keeps its
  recording (16 kHz WAV, shared by all tasks from one utterance). Topics
  (name, colour, keywords) colour tasks automatically — computed when shown,
  so editing a topic recolours everything; tapping a task overrides it. A
  home-screen widget adds tasks without opening the app: it opens a small
  panel over the home screen (`QuickAddActivity`, its own task) to type in or
  hold to talk, or recording hands-free from the icon (one cell wide, the
  widget is only that icon, dark or white to suit the wallpaper; a widget cannot be
  long-pressed, so that stops on ~1.6 s of silence), with a compact
  confirmation and ✓ to save. Speech →
  SenseVoice int8 via sherpa-onnx, fully offline → parser → confirmation
  card → SQLite → one exact alarm per task (lead time, then due time;
  date-only tasks the evening before and the morning of). Notifications
  carry 完成 / 1小时后再提醒. Alarms are restored after reboot, clock
  changes and exact-alarm permission changes.

### Proper nouns (专有名词)

SenseVoice takes no hotwords, so the user's company names, people and places
are fixed after recognition instead (`parser/.../Vocabulary.kt`):

- **Pinyin matching** — "疼讯" becomes "腾讯" because they sound the same.
  Accent mergers (zh/z, n/l, f/h, -ing/-in…) are allowed for at most a third
  of a term's characters; looser than that starts rewriting ordinary words.
- **Aliases** — for words whose sound does not follow from their spelling:
  Shopee ← 虾皮, 秀匹.
- **Protection** — terms are hidden from the time parser, so "周一科技" is a
  company, not Monday.
- **Learning** — fixing a name by hand on the confirmation card offers to add
  it, with what was heard as its alias. Corrections are listed on the card
  with an undo.

### English

SenseVoice runs with language detection (`auto`) and writes English in
capitals with numbers as words ("CALL BOB TOMORROW AT THREE PM"), so the
English side lower-cases all-caps text first and reads spelled-out times.
`EnglishParser` covers the common dictation phrasings (tomorrow, next Friday,
at 3pm, by Friday, within 3 days, in 2 hours, end of month, remind me 30
minutes before); `EnglishDisfluency` removes um/uh and corrections like
"Friday, no wait, Saturday". Padding silence before recognition was tried and
made SenseVoice worse, so audio is fed as recorded.

### Hesitations (语气词和改口)

Before vocabulary correction, `Disfluency.kt` removes filler sounds (嗯, 呃),
filler words where a pause marks them as filler (那个, 就是说 — but not
"那个方案"), stutters (我我我, 明天明天) and self-corrections ("明天，不对，
后天" keeps 后天; "下午三点不是四点" keeps 下午). Recognizers often drop the
pauses, so a bare 不对/不是 between two times of the same kind also counts as a
correction. The confirmation card lists what was removed, with an undo.

The pinyin table is generated from Unicode's Unihan data by
`scripts/gen-pinyin.py` (Unicode License v3).

## Install

Download `VoiceTodo-<version>.apk` from the
[latest release](https://github.com/MuqiuPeng/VoiceTodo/releases/latest) and
open it on the phone (allow "install unknown apps" for the browser or file
manager when asked). After that the app updates itself: it checks GitHub once
a day, or on "检查更新", and offers to download and install a newer release.
Voice input needs the speech pack, downloaded once from 设置 → 语音包
(~230 MB). Checking for updates and downloading the pack are the only network
traffic the app makes.

## Building

The speech runtime (sherpa-onnx 1.13.8 AAR) is not in git:

```bash
./scripts/fetch-deps.sh
```

The speech model is not part of the APK. Three speech packs are offered in
Settings, each published as assets of its own `speech-*` pre-release (a
pre-release, so the updater's `releases/latest` never sees it), with sizes and
SHA-256 pinned in `SpeechPacks.kt`:

| Pack | Size | Notes |
|---|---|---|
| FunASR Nano (Fun-ASR-Nano-2512, int8) | 252 MB | Recommended. Best on Chinese with English mixed in; English in normal case |
| SenseVoice (2025-09-09, int8) | 226 MB | Good Chinese; English words often misheard, English in capitals |
| Zipformer zh-en (2023-11-22, int8) | 72 MB | Smallest and fastest; weakest on mixed speech |

Chosen by running candidates over the same synthesized clips on the device's
own sherpa-onnx runtime (`DebugBenchReceiver`, debug builds only). On mixed
sentences ("下周一要交essay", "帮我book一个meeting room", "周三有个interview")
FunASR Nano got all of them right where SenseVoice and Zipformer missed the
English words. Paraformer zh (2025-10) and the WeNet zh-en-yue model were
also tried and spelled English out letter by letter.

Then, with the Android SDK at `~/Library/Android/sdk` (see `local.properties`):

```bash
./gradlew :app:assembleDebug
```

Releases are signed with a key kept outside the repo (`VOICETODO_KEYSTORE`
and `VOICETODO_KEYSTORE_PASSWORD` in `~/.gradle/gradle.properties`). Android
only installs an update signed with the same key as the installed app, so
**losing that keystore means users must uninstall to upgrade**. To publish,
bump `val version` in `app/build.gradle.kts`, commit, then:

```bash
scripts/release.sh "这一版改了什么"
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk` (~45 MB). Debug builds also take input from adb, which is how the emulator was
tested without a voice:

```bash
adb shell am start -n app.voicetodo/.ui.MainActivity --es debug_text "明天下午三点开会"
adb shell am start -n app.voicetodo/.ui.MainActivity --es debug_term "'Shopee|虾皮|秀匹'"
adb shell am start -n app.voicetodo/.ui.MainActivity --es debug_wav /data/data/app.voicetodo/files/t1.wav
# updater against a local server shaped like GitHub's releases/latest JSON
adb shell am start -n app.voicetodo/.ui.MainActivity --es debug_update_url http://10.0.2.2:8771/latest.json
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
