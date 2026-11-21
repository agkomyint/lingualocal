# LinguaLocal

**A live interpreter for Thai, English and Burmese, built on Firebase AI Logic hybrid inference.**

Put the phone or laptop on the table and LinguaLocal listens to the whole conversation. It cuts speech into pieces at natural pauses, transcribes and translates every piece, tells the speakers apart, reads translations aloud, and saves everything as a project you can replay, search and ask questions about.

- **Try it (web, Chrome recommended):** https://lingualocal-chi.vercel.app
- **Platforms:** Web (Chrome), plus an Android app (Kotlin and Jetpack Compose)
- **License:** [Apache 2.0](LICENSE)

## Why

In Thailand, everyday life happens across three languages. A Burmese worker and a Thai manager sort out a shift. An expat signs a lease explained in fast Thai. A clinic visit happens with no interpreter. Translation apps handle one sentence at a time, and Burmese is usually supported last and worst.

LinguaLocal is built for whole conversations:

- **No button per sentence:** it just listens.
- **Three language pairs:** Thai ⇄ English, Thai ⇄ Burmese and English ⇄ Burmese, translated directly rather than through English.
- **Afterwards you can ask** *"What did she mean? Was that polite? What should I reply?"*
- **Private by default where possible:** in Chrome with Gemini Nano, explanations and Ask chats can run entirely on your device.

## Features

| | |
|---|---|
| **Live interpreting** | On-device voice detection (Silero VAD) cuts speech into pieces. Each piece goes to Gemini with its audio, which transcribes it, detects the language and translates it. A Gemini Live stream shows the words as they are spoken. |
| **Who said what** | A CAM++ speaker-embedding model running on the device labels voices as Person 1, Person 2 and so on, matched by cosine similarity. |
| **Spoken translations** | Translations are read aloud using the device's voices, or Gemini text-to-speech where the device has no voice, such as Burmese. |
| **Projects** | Every conversation is saved with timestamps, the original audio, Play all, find and jump, and typed messages. |
| **What did they mean?** | Explains cultural and politeness nuance and suggests a reply. It uses **hybrid inference**: Gemini Nano on the device when available, cloud Gemini otherwise. |
| **Ask** | A chat about the conversation. On **Cloud**, the Gemini agent calls tools to read and search the transcript, **re-listen to the original recordings** for tone and missed words, **correct pieces** (with undo) and search the web. On **Nano**, the chat runs on the device and works offline. |
| **Offline typing** | Chrome's on-device Translator API handles typed Thai ⇄ English messages. |

## How it uses Firebase AI Logic

All AI calls go through the Firebase AI Logic SDKs (`firebase/ai` on the web, `com.google.firebase:firebase-ai` on Android), protected by App Check.

| Feature | Firebase AI Logic | Where |
|---|---|---|
| What did they mean? | **Hybrid:** `InferenceMode.PREFER_ON_DEVICE` (Gemini Nano, falling back to cloud). The answer is labelled with the engine that wrote it. | `web/src/lib/hybrid.ts`, `app/.../ai/HybridAssistant.kt` |
| Ask, Nano mode | Gemini Nano in Chrome, on the device only, with the newest transcript pieces fitted to Nano's context. | `web/src/lib/ask.ts` |
| Ask, Cloud mode | `getGenerativeModel` with function calling and Google Search grounding | `web/src/lib/ask.ts`, `app/.../ai/AskAssistant.kt` |
| Transcribe and translate each piece | `getGenerativeModel` with audio input and a JSON `responseSchema` | `web/src/lib/translator.ts`, `app/.../ai/SegmentTranslator.kt` |
| Live captions while speaking | `getLiveGenerativeModel` (Gemini Live API) | `web/src/lib/live.ts`, `app/.../ai/LiveInterpreter.kt` |
| Burmese voice | Gemini TTS with `responseModalities: AUDIO` | `web/src/lib/speak.ts`, `app/.../ai/CloudVoice.kt` |
| Project names | Short title generation | `web/src/lib/namer.ts`, `app/.../ai/ProjectNamer.kt` |

**Other Firebase products:**

- **App Check:** reCAPTCHA Enterprise on the web, Play Integrity on Android, and debug tokens for development.
- **Authentication:** an anonymous account for each user.
- **Cloud Firestore:** projects, transcript pieces and Ask chats, stored per user (web).
- **Cloud Storage:** each piece's recording (web).

The rules are in `web/firestore.rules` and `web/storage.rules`.

## Architecture

```
Mic ─► AudioWorklet ─► Rust/WASM audio core (framing, fbank) ─► Silero VAD (ONNX)
                                                                 │ speech piece
             ┌───────────────────────────────────────────────────┤
             ▼                                                    ▼
   CAM++ speaker embedding (ONNX)          Firebase AI Logic ─► Gemini (transcribe + translate)
             │                                                    │
             └──────────► project piece ◄─────────────────────────┘
                              │
       Firestore / Storage ◄──┴──► speak (device voice or Gemini TTS)
                              │
             Ask / What did they mean? ─► Firebase AI Logic hybrid: Gemini Nano ⇄ cloud Gemini
```

**Web:**
- Next.js 15, React 19, TypeScript, Tailwind CSS 4, Radix UI, zustand.
- Audio core in Rust compiled to WebAssembly (`web/audio-core`), with ONNX Runtime Web for the models.
- Hosted on Vercel.

**Android:**
- Kotlin and Jetpack Compose, with a foreground listening service.
- ONNX Runtime for VAD, speaker and language-ID models, and an AAC encoder for compact recordings.

## Repository layout

```
web/                 Web app (Next.js)
  src/lib/           AI, audio, state and storage logic
  src/components/    UI
  audio-core/        Rust → WebAssembly audio core
  public/models/     Silero VAD and CAM++ ONNX models
app/                 Android app (Kotlin, Compose)
LICENSE              Apache 2.0
```

## Run the web app

You need [bun](https://bun.sh) (or Node 20+) and a Firebase project.

1. **Set up Firebase**, in the console:
   - **AI Logic:** enable it with the Gemini Developer API.
   - **Authentication:** enable the Anonymous provider.
   - **Firestore and Storage:** create both.
   - **App Check:** register the web app with **reCAPTCHA Enterprise** and enforce it for AI Logic.
2. **Configure:** create `web/.env.local` with your web app's config. These are public client values, not secrets.
   ```
   NEXT_PUBLIC_FIREBASE_API_KEY=
   NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=
   NEXT_PUBLIC_FIREBASE_PROJECT_ID=
   NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET=
   NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID=
   NEXT_PUBLIC_FIREBASE_APP_ID=
   NEXT_PUBLIC_RECAPTCHA_SITE_KEY=
   ```
   For local development, register an App Check debug token in the console. Never commit it.
3. **Install and run:**
   ```bash
   cd web
   bun install
   bun dev            # http://localhost:3000
   ```
4. **Deploy the rules (optional):** `firebase deploy --only firestore:rules,storage`.
5. **Rebuild the audio core (optional):** a prebuilt `.wasm` is already committed. To rebuild it you need Rust and wasm-pack, then run `bun run wasm`.

## Run the Android app

1. Open the project root in Android Studio.
2. Add your own `app/google-services.json` from the Firebase console. It is not committed.
3. Debug builds use the App Check debug provider. Register the debug token printed in Logcat in the Firebase console.
4. Run on a device (Android 8+). Gemini Nano is used through Firebase AI Logic when the device supports it; otherwise the app uses cloud Gemini.

## Testing tips

- **Desktop Chrome** with built-in AI shows a **Cloud / Nano** switch in Ask, and "What did they mean?" answers are labelled `on-device · Gemini Nano`. Chrome needs about 22 GB of free disk space before it downloads Gemini Nano. You can check its status at `chrome://on-device-internals`.
- In any other browser, the app runs fully on cloud Gemini.
- To use the app: allow the microphone, press **Start listening**, choose a language pair and talk. Open a project and press **Ask** to chat about it.

## Built during #BuildHybridAI 2026

All the code in this repository was written during the submission period (first commit 20 Nov 2026), with Claude Code as the coding assistant. No earlier code was reused. Third-party pieces: the open-source Silero VAD and CAM++ (3D-Speaker) ONNX models, and the libraries listed in `web/package.json` and `app/build.gradle.kts`.

**What I built:**

- **Android app:** an always-on Thai/English interpreter with on-device VAD, speaker identification and language ID, per-piece Gemini transcription and translation, live captions, spoken translations and projects. It has hybrid Gemini Nano / cloud "What did they mean?" and the tool-using Ask agent that can re-listen to recordings and fix mistakes.
- **Burmese support:** Thai ⇄ Burmese and English ⇄ Burmese pairs with direct translation and a Gemini TTS Burmese voice.
- **Web version:** a full port to Next.js, with the audio pipeline in Rust/WebAssembly and ONNX Runtime Web, and Firestore and Storage sync, all running in the browser.
- **Hybrid AI on the web:** Firebase AI Logic `PREFER_ON_DEVICE` for explanations, a Cloud / Nano switch in Ask, and Chrome's on-device Translator for offline typing.
- **Fixes:** for example, stripping the WAV header and C2PA metadata from Gemini TTS output, which removed a buzz at the end of every spoken translation.
