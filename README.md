# AI Calendar / ប្រតិទិន AI

An Android calendar that creates events from **text, a photo, or your voice** — in **Khmer and
English** — using a small generative model that runs **entirely on the phone**. No account, no
server, no analytics. It works on a plane.

---

## What it does

| Input | How it works |
|---|---|
| **Text** | Type `ជួបលោកគ្រូ ថ្ងៃស្អែក ម៉ោង ៣ រសៀល នៅសាលា` or `dentist next friday 9am` and get a dated event. |
| **Photo** | Photograph an invitation, poster or timetable. Text is read on-device, then turned into one or more events. |
| **Voice** | Dictate in Khmer or English; the transcript goes through the same pipeline. |

Every extraction produces **drafts you review before saving** — the title, date, time and place are
all editable in place. Nothing is written to your calendar behind your back.

## How the language understanding works

Two engines, deliberately combined rather than raced:

**1. A deterministic Khmer/English date grammar** (`nlp/RuleBasedEventParser.kt`).
Hand-written, ~600 lines, no model required. It understands Khmer numerals (`០១២៣៤៥៦៧៨៩`),
relative days (`ថ្ងៃស្អែក`, `ខានស្អែក`, `សប្តាហ៍ក្រោយ`), weekdays (`ថ្ងៃសុក្រ`), day parts
(`ព្រឹក` / `រសៀល` / `ល្ងាច` / `យប់`), half-hours (`ម៉ោង ៣ កន្លះ`), explicit dates
(`ថ្ងៃទី ១៥ ខែ សីហា`), durations (`រយៈពេល ២ ម៉ោង`) and locations (`នៅ …`), plus the English
equivalents. It is instant, costs nothing, and **cannot hallucinate a date**.

**2. An on-device LLM** (Gemma via MediaPipe LLM Inference), for phrasing the grammar cannot reach.

They are then **reconciled** (`ai/EventExtractor.kt`). Small models are good at *what* an event is —
the title, the place, splitting a poster into three separate appointments — and unreliable at
*when*, because relative-date arithmetic is precisely what they get wrong. So when the grammar found
a concrete date or time in the input, **that value wins**; the model supplies the semantics around
it. When the grammar found nothing, the model's answer stands.

The practical consequence: **the app is fully usable with no model installed.** Installing one makes
it better at long, messy or multi-event input.

## Setting up the on-device model

The model is *not* bundled — quantised Gemma bundles are hundreds of megabytes and carry their own
licence. Install one from **Settings → On-device AI**:

- **Import model** — pick a `.task` file already on the phone, or
- **Download** — paste a URL (an access token field is provided for gated Hugging Face repos).

Two suggestions are built into the Settings screen:

| Model | Size | Notes |
|---|---|---|
| Gemma 3 1B (int4) | ~555 MB | Smallest. Good Khmer and English on 4 GB devices. |
| Gemma 3n E2B (int4) | ~3.1 GB | **Reads photos directly** and is stronger on Khmer. Needs 6 GB+ RAM. |

When a vision-capable bundle is installed, photos are passed to the model as images *in addition to*
the OCR text, which recovers layout that OCR flattens.

## Khmer text recognition

ML Kit's bundled recogniser covers Latin script but **has no Khmer model**, so Khmer pages go through
Tesseract with `khm.traineddata` — a ~4 MB pack downloaded on demand from
**Settings → Photo text recognition**, or from the prompt that appears when a photo turns out to be
Khmer. With the pack installed both engines run and the richer result wins, because Cambodian
invitations are usually mixed script.

## Voice input

Uses Android's `SpeechRecognizer` with `km-KH` or `en-US` (selectable in Settings). Whether Khmer
dictation works **offline** depends on the user having downloaded Google's offline voice pack in
system settings — the app says so explicitly when the recogniser reports a network error.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36).

```bash
./gradlew :app:assembleDebug          # per-ABI debug APKs
./gradlew :app:testDebugUnitTest      # JVM tests for the parser and JSON reader
./gradlew :app:assembleRelease        # minified, needs a signing config
```

APKs land in `app/build/outputs/apk/debug/`. The build is **split by ABI**: MediaPipe, Tesseract and
ML Kit together ship ~214 MB of native code across four architectures, so a universal APK would be
250 MB. Install `app-arm64-v8a-debug.apk` on any modern phone; `armeabi-v7a` covers older 32-bit
devices and `x86_64` covers emulators.

## Architecture

```
com.aicalendar
├── ai/          LlmEngine + MediaPipe implementation, ModelManager, Prompts, EventExtractor
├── nlp/         KhmerText, DateTimeLexicon, RuleBasedEventParser   ← pure Kotlin, unit-tested
├── ocr/         OcrEngine (ML Kit + Tesseract), TessDataInstaller
├── voice/       SpeechToText (SpeechRecognizer as a Flow)
├── data/        Room entity/DAO/database, EventRepository, SettingsRepository (DataStore)
├── notify/      ReminderScheduler (AlarmManager), ReminderReceiver, BootReceiver
├── ui/          Compose: calendar, capture, editor, settings + theme
└── util/        MiniJson — a forgiving JSON reader for model output
```

Dependency wiring is a hand-rolled `AppContainer`; the app has exactly one process-wide object worth
sharing (the loaded model, which owns hundreds of megabytes of native memory), so a DI framework
would only add build time.

**Khmer rendering:** Khmer stacks diacritics above and subscript consonants below the baseline, so
every text style gets explicit, roomier line height with font padding kept on (`ui/theme/Theme.kt`).
Numbers are rendered as Khmer numerals when the UI locale is `km` (`ui/Formatters.kt`).

## Privacy

Text, audio transcripts and photos are processed on the device and never leave it. The only network
access the app makes is when *you* ask it to download a model bundle or the Khmer OCR pack. There is
no account, no backend and no telemetry.

## Known limitations

- `LlmInference` in MediaPipe 0.10.35 is marked deprecated upstream (Google is moving to LiteRT-LM).
  It still works and has no in-package replacement yet; migrating is a follow-up.
- Recurring events ("every Monday") are not modelled — each extraction produces single events.
- No sync with the system calendar provider or Google Calendar; storage is local-only.
- Khmer OCR accuracy on handwriting is poor; it is tuned for printed text.
