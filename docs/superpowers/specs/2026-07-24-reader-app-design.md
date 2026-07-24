# VieNeuTTS-Android — Sub-project 2: EPUB Audio Reader App — Design

Status: approved by user 2026-07-24 (all sections confirmed during brainstorming)
Depends on: sub-project 1 (`:engine`, see
`2026-07-23-tts-engine-core-design.md`) — already built and validated,
consumed unchanged. No changes to `:engine`'s public API are needed for
this sub-project.

## 1. Goal

An Android app that turns an EPUB into an audiobook using the on-device
`:engine`: pick a book, see its chapters, listen — generating audio
on-demand (streamed, with a buffer-ahead guarantee) or replaying audio
already generated, with independently adjustable playback speed/pitch, a
per-app default voice plus an optional per-book voice override, and full
persistence (reopening a book or restarting the app resumes exactly where
the user left off).

Target device for validation: Galaxy Z Fold, 12 GB RAM (real hardware —
sub-project 1's only on-device testing was a 3 GB-RAM emulator, where the
fp32 backbone OOM'd on long single-shot inputs; seʼ §7).

## 2. Architecture

A new Gradle module, `:reader-app` (Kotlin + Jetpack Compose + Material3,
matching `:app-sample`'s conventions), depending on `:engine` unchanged.
`:app-sample` keeps its existing role as `:engine`'s technical test
harness — it is not touched by this sub-project.

Three layers inside `:reader-app`:

- **Data** — Room (SQLite) for structured state (books, chapters,
  sentences, playback position, voice settings) + one WAV file per sentence
  on disk for generated audio. Room holds *metadata only*; audio bytes
  never go through the database.
- **Generation** — `TtsGenerationService`, an Android foreground service
  that owns a single, process-lifetime `TtsEngine` instance (loading it —
  copying ~500 MB of bundled assets and initializing 4 ONNX sessions — is
  expensive and must happen once, not per book or per sentence). The
  service runs a sequential worker over two priority queues: the
  currently-listened chapter (high) and the next chapter (low,
  background pre-generation). Cancellable per book/chapter; a foreground
  notification shows progress, matching what a download/podcast app does
  and satisfying Android's background-execution limits for
  multi-second-per-sentence work.
- **Playback** — an `AudioTrack`-based player tracking the current
  sentence index, applying speed/pitch via `AudioTrack.setPlaybackParams`
  (Android's built-in time-stretcher — no custom DSP needed, and no
  re-generation needed when the user changes rate/pitch, since those are
  playback-time parameters, not generation-time ones), and enforcing the
  buffer-ahead gate (§5) against `TtsGenerationService`'s progress.

## 3. Data model (Room)

```
Book(id, title, author, epubFilePath, coverPath?, voiceOverride: String?,
     lastChapterIndex, lastSentenceIndex, addedAt)

Chapter(id, bookId, orderIndex, title, sentenceCount)

Sentence(id, chapterId, orderIndex, text,
         audioStatus: NOT_GENERATED | GENERATING | GENERATED | FAILED,
         audioFilePath: String?, durationMs: Int?)

AppSettings(defaultVoice: String, defaultSpeechRate: Float, defaultPitch: Float,
            autoAdvanceChapter: Boolean, sleepTimerMinutes: Int?)
```

Audio files: `books/<bookId>/audio/ch<N>_s<M>.wav` — one self-contained
48kHz mono WAV per sentence (matches `:engine`'s output format directly,
no transcoding).

**Voice resolution**: `book.voiceOverride ?: appSettings.defaultVoice`.
**Changing a book's voice** (global default change doesn't affect books
with their own override; changing the override on a specific book) deletes
all of that book's generated audio and resets every `Sentence.audioStatus`
to `NOT_GENERATED` — a book is never allowed to mix two voices mid-way
through. This reuses the same delete pathway as the explicit "remove
generated audio" control.

**Resume**: `Book.lastChapterIndex`/`lastSentenceIndex` are written on every
sentence transition. Reopening a book (or restarting the app) resumes at
that sentence's start — sentence-granularity resume, not sub-sentence
millisecond position, which is enough for an audiobook-style UX and much
simpler to persist correctly than mid-sentence resume.

## 4. Generation & buffer algorithm

**Generation** (inside `TtsGenerationService`): a sequential worker
processes a chapter's sentences in order, calling
`TtsEngine.synthesize(sentence.text, voice)` once per sentence (no changes
to `:engine` — sentence splitting is `:reader-app`'s job, a lightweight
regex-based Vietnamese sentence splitter run once at import time, not a new
`:engine` API), writes the WAV, updates `Sentence` in Room. Two priority
queues: current chapter (high) drains before next-chapter background
pre-generation (low) gets any worker time.

**Buffer gate**, recomputed on every state change:

```
remaining        = totalSentences - currentPlaybackIndex
required         = ceil(remaining / 4)
generatedAhead    = lastGeneratedIndex - currentPlaybackIndex

playback may run/continue when: generatedAhead >= required
```

When playback catches up to the point where `generatedAhead < required`,
it auto-pauses and shows a "generating..." state; it only resumes once
`generatedAhead` reaches the (recomputed, now-smaller, since `remaining`
shrank as playback advanced) `required` again — so the required lookahead
naturally shrinks as the chapter nears its end. If the whole chapter is
already `GENERATED` (replay), the gate is skipped entirely and playback
reads straight from files.

**Controls**: stop-generation (cancels the in-flight job, keeps whatever
was already generated) and delete (removes a book's generated audio and/or
the book itself).

## 5. Screens & navigation

```
Library (list of imported books)
  → [+] Add book → SAF file picker (ACTION_OPEN_DOCUMENT) → copy into app
    storage → parse EPUB → insert into Library
  → pick a book → Book Detail
       - chapter list (title, status: unheard/in-progress/heard)
       - whole-book progress bar (sentences heard / total sentences in book)
       - "This book's voice" → Book Voice screen (override, or "use app default")
       → pick a chapter → Listen screen
            - play/pause, next/prev sentence, buffer-wait indicator while generating
            - "Playback settings" → Speech Settings screen
App Settings (from Library)
  - default voice for new books
  - default speech rate / pitch (used unless a book has its own)
```

**Speech Settings** (per §"pitch" clarification — this is *pitch*, a voice
control, not a pinch-to-zoom gesture): speech rate, pitch, auto-advance to
next chapter on chapter end, sleep timer. All playback-time, applied via
`AudioTrack.setPlaybackParams` — never trigger regeneration.

## 6. Key dependencies

- **EPUB parsing**: `epublib` (`nl.siegmann.epublib:epublib-core`) for
  spine/metadata/chapter extraction + Jsoup for HTML→plain-text per
  chapter. `epublib` is old but does exactly the narrow job needed
  (parsing, not rendering) and avoids hand-rolling zip/OPF/NCX parsing;
  Readium's Kotlin toolkit was the alternative but pulls in a much larger
  surface (navigator/rendering) this audio-first app doesn't use.
- **Room** for the metadata database.
- **AudioTrack** (`android.media`) for playback — no ExoPlayer/Media3
  needed; requirements are simple (one WAV at a time, speed/pitch via
  `PlaybackParams`) and `:app-sample` already established the raw
  `AudioTrack` pattern.

## 7. Error handling

- Per-sentence generation failure → mark `Sentence.audioStatus = FAILED`,
  skip it in the buffer count (doesn't count toward `generatedAhead`),
  surface a small inline retry affordance in the Listen screen; does not
  halt the rest of the queue.
- EPUB parse failure on import → toast/snackbar with the error, book is not
  added to the Library.
- The known fp32 memory-ceiling finding from sub-project 1 (OOM on a
  ~430-char single-shot input on a 3GB emulator) is expected to matter far
  less here: (a) the target device has 12GB RAM, and (b) this app never
  makes a single-shot call larger than one sentence, which is exactly the
  kind of chunking that finding suggested. Still worth an explicit check
  during implementation on the Z Fold (or a higher-RAM AVD) once the
  generation service is wired up.

## 8. Testing

- Unit tests (JVM, no device) for: the sentence splitter, the buffer-gate
  math (`required`/`generatedAhead` arithmetic across edge cases —
  chapter start, chapter end, all-generated replay), Room DAOs/migrations.
- Instrumented tests (emulator/device) for: SAF import → parse → Library
  flow on a real small EPUB fixture, and an end-to-end generate-then-play
  smoke test for one short chapter.
- Manual validation on the Z Fold: import a real book, listen through a
  chapter boundary (checks auto-advance + next-chapter background
  pre-generation), change voice mid-book (checks the delete-and-regenerate
  path), force-restart the app mid-chapter (checks resume).

## 9. Explicitly out of scope

- Cloud sync / multi-device.
- Bookmarks/annotations, text highlighting during playback, visual reading
  mode (this is an audio-first app — no epub *text* renderer, only chapter
  titles and progress are shown).
- Voice cloning / custom voices (per sub-project 1, `:engine` only supports
  the 14 built-in presets).
- Non-EPUB formats (PDF, MOBI, etc).

## 10. Decisions log

Captured from the brainstorming session (2026-07-24):

1. New `:reader-app` module, not an extension of `:app-sample` (which
   stays a technical harness for `:engine`).
2. "Pinch" in the original request means **pitch** (voice pitch), not a
   pinch-to-zoom gesture.
3. Speech Settings scope: rate + pitch (playback-time, via
   `AudioTrack.setPlaybackParams`) + auto-advance-chapter + sleep timer.
   Generation-time sampling params (temperature/top_k/top_p/repetition_penalty)
   are explicitly not exposed in this app's UI.
4. Buffer unit is **sentences**, not seconds or characters (~150
   sentences/chapter is the user's stated ballpark) — `required =
   ceil(remaining_sentences / 4)`.
5. Background pre-generation of the next chapter, plus explicit
   stop-generation and delete controls.
6. EPUB selection via Android's Storage Access Framework, not a hand-rolled
   file browser.
7. Persistence: Room for metadata, one WAV file per sentence on disk.
8. Generation runs in an Android foreground service (not a plain
   ViewModel-scoped coroutine), specifically to support background
   next-chapter pre-generation while the user is elsewhere.
