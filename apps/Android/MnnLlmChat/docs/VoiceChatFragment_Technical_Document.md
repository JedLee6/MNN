# VoiceChatFragment Technical Document

## 1. Overview

The Voice Chat feature in MNN LLM Chat provides a real-time, voice-driven conversational interface. Users can speak to the AI, which processes the speech via an on-device ASR (Automatic Speech Recognition) engine, sends the transcribed text to a local LLM (Large Language Model) for inference, and speaks the response back using an on-device TTS (Text-to-Speech) engine. It also supports **real-time vision** via CameraX and **voice interruption** (full-duplex).

All AI processing — ASR, LLM inference, and TTS — runs entirely on-device using MNN (Mobile Neural Network) native libraries.

---

## 2. Architecture

The module follows an **MVP (Model-View-Presenter)** pattern:

| Layer | Class | Responsibility |
| :--- | :--- | :--- |
| **View** | `VoiceChatFragment` | UI rendering, permissions, camera, user interactions |
| **Presenter** | `VoiceChatPresenter` | Orchestration of ASR, LLM, TTS; state management |
| **Model/Service** | `AsrService`, `TtsService`, `AudioChunksPlayer`, `ChatPresenter` | Core AI and audio playback engines |

### 2.1 Component Structure Diagram

```mermaid
graph TB
    subgraph "View Layer"
        VCF["VoiceChatFragment<br/>(UI & Permissions)"]
        VTA["VoiceTranscriptAdapter<br/>(RecyclerView)"]
        VMC["VoiceModelsChecker<br/>(Model Readiness)"]
        VMS["VoiceModelMarketBottomSheet<br/>(Model Selection)"]
    end

    subgraph "Presenter Layer"
        VCP["VoiceChatPresenter<br/>(Orchestrator)"]
        GRP["GenerateResultProcessor<br/>(Thinking/Normal Output)"]
        TC["taskChannel<br/>(SerialTask Channel)"]
    end

    subgraph "Service Layer"
        ASR["AsrService<br/>(Sherpa-MNN)"]
        LLM["ChatPresenter<br/>(MNN LLM)"]
        TTS["TtsService<br/>(MNN TTS)"]
        ACP["AudioChunksPlayer<br/>(AudioTrack)"]
    end

    subgraph "Native Layer (C++/JNI)"
        NASRS["sherpa_mnn<br/>(ASR Native)"]
        NLLM["mnn_llm<br/>(LLM Native)"]
        NTTS["mnn_tts<br/>(TTS Native)"]
    end

    VCF --> VCP
    VCF --> VTA
    VCF --> VMC
    VCF --> VMS
    VCP --> TC
    VCP --> GRP
    VCP --> ASR
    VCP --> LLM
    VCP --> TTS
    VCP --> ACP
    ASR --> NASRS
    LLM --> NLLM
    TTS --> NTTS

    style VCF fill:#4A90D9,color:#fff
    style VCP fill:#D9534F,color:#fff
    style ASR fill:#5CB85C,color:#fff
    style TTS fill:#5CB85C,color:#fff
    style LLM fill:#5CB85C,color:#fff
    style ACP fill:#5CB85C,color:#fff
```

---

## 3. Feature Details

### 3.1 Voice Chat (Core Loop)

The core voice chat loop follows a sequential pipeline:

**ASR → LLM → TTS → Audio Playback → ASR (repeat)**

#### State Machine

The presenter tracks its internal state via `VoiceChatPresenterState`:

```mermaid
stateDiagram-v2
    [*] --> INITIALIZING : start()
    INITIALIZING --> LISTENING : ASR & TTS ready
    LISTENING --> GENERATING_TEXT : ASR text received
    GENERATING_TEXT --> PLAYING : First TTS audio ready
    PLAYING --> PLAY_END : AudioTrack marker reached
    PLAY_END --> LISTENING : OnTtsComplete processed
    PLAYING --> LISTENING : stopGeneration()
    GENERATING_TEXT --> LISTENING : stopGeneration()
```

The UI displays a simplified state via `VoiceChatState`:

```mermaid
stateDiagram-v2
    [*] --> CONNECTING : Fragment created
    CONNECTING --> LISTENING : Services initialized
    LISTENING --> GREETING : Greeting plays
    GREETING --> LISTENING : Greeting ends
    LISTENING --> PROCESSING : User speaks
    PROCESSING --> THINKING : Thinking tokens
    THINKING --> PROCESSING : Normal output begins
    PROCESSING --> SPEAKING : TTS audio plays
    SPEAKING --> STOPPING : User interrupts
    STOPPING --> LISTENING : Reset complete
    SPEAKING --> LISTENING : Playback ends
```

#### Serial Task Processing

The presenter uses a **Kotlin Coroutine Channel** (`taskChannel`) to serialize all critical operations:

```mermaid
sequenceDiagram
    participant ASR as AsrService
    participant TC as taskChannel
    participant SP as Serial Processor
    participant LLM as ChatPresenter
    participant TTS as TtsService
    participant AP as AudioChunksPlayer
    participant UI as VoiceChatFragment

    Note over ASR, UI: 1. User Speech Recognition
    ASR ->> TC: HandleAsrResult(text)
    TC ->> SP: Dequeue task
    SP ->> UI: addTranscript(user)
    SP ->> LLM: sendMessage(text)

    Note over ASR, UI: 2. LLM Generation Progress
    LLM ->> TC: ProcessProgress(token)
    TC ->> SP: Dequeue task
    SP ->> SP: GenerateResultProcessor.process()
    SP ->> UI: updateLastTranscript()
    SP ->> SP: Buffer until delimiter
    SP ->> TTS: processTtsText(segment)
    TTS -->> SP: ShortArray (audio)
    SP ->> AP: playChunk(audio)

    Note over ASR, UI: 3. LLM Generation Complete
    LLM ->> TC: ProcessFinalChunk
    TC ->> SP: Dequeue task
    SP ->> TTS: processTtsText(remaining)
    TTS -->> SP: ShortArray (audio)
    SP ->> AP: playChunk + endChunk()

    Note over ASR, UI: 4. Audio Playback Complete
    AP ->> TC: OnTtsComplete
    TC ->> SP: Dequeue task
    SP ->> UI: updateStatus(LISTENING)
    SP ->> ASR: startRecord()
```

### 3.2 Voice Interruption (Full-Duplex)

Voice interruption allows the user to speak while the AI is still talking or generating, immediately canceling the current response.

**Key Design Decisions:**
- ASR recording stays **active** during LLM generation and TTS playback (the microphone is never stopped during a turn).
- `AsrService.onSpeechDetected` is triggered as soon as partial speech text is detected.
- When speech is detected during AI output, `stopGeneration()` is called immediately.

```mermaid
sequenceDiagram
    participant User as User (Mic)
    participant ASR as AsrService
    participant VCP as VoiceChatPresenter
    participant LLM as ChatPresenter
    participant AP as AudioChunksPlayer

    Note over User, AP: AI is speaking (TTS playback active)

    User ->> ASR: Speaks during AI output
    ASR ->> ASR: Detects partial text
    ASR ->> VCP: onSpeechDetected()
    VCP ->> VCP: stopGeneration()
    VCP ->> LLM: stopGenerate()
    VCP ->> AP: stop()
    VCP ->> AP: reset()
    VCP ->> VCP: isStoppingGeneration = true
    Note over VCP: Small delay (300ms + 200ms)
    VCP ->> VCP: isStoppingGeneration = false
    VCP ->> ASR: startRecord()
    Note over User, AP: System returns to LISTENING state
```

#### Echo Cancellation Modes

Two modes are supported to prevent the AI from hearing its own TTS output:

| Mode | Mechanism | Behavior |
| :--- | :--- | :--- |
| **Hardware AEC** (default) | `VOICE_COMMUNICATION` audio source + `AcousticEchoCanceler` | System-level echo cancellation; mic stays active |
| **Auto-Mute** (software) | `setMuted(true/false)` on `AsrService` | Mic is muted when AI speaks, unmuted when AI stops |

### 3.3 Real-Time Vision (Camera Integration)

Vision mode enables the AI to "see" the user's surroundings by capturing a photo from the camera and sending it alongside the voice transcription to a vision-capable LLM.

**Availability:** Only enabled when the current model supports vision (`ModelTypeUtils.isVisualModel(modelId)`).

```mermaid
sequenceDiagram
    participant User as User
    participant VCF as VoiceChatFragment
    participant CameraX as CameraX
    participant ASR as AsrService
    participant VCP as VoiceChatPresenter
    participant LLM as ChatPresenter

    User ->> VCF: Tap Camera Button
    VCF ->> CameraX: startCamera()
    Note over VCF: Camera preview visible

    User ->> ASR: Speaks a question
    ASR ->> VCP: onSpeechDetected()
    VCP ->> VCF: capturePhoto()
    VCF ->> CameraX: takePicture()
    CameraX -->> VCF: onImageSaved(uri)
    VCF ->> VCF: compressImage + store URI

    ASR ->> VCP: onRecognizeText("What is this?")
    VCP ->> VCP: HandleAsrResult
    VCP ->> VCF: getCapturedImageUri()
    VCF -->> VCP: Uri (photo)
    VCP ->> LLM: sendMessage(ChatDataItem with text + imageUri)
    VCP ->> VCF: clearCapturedImageUri()

    LLM ->> VCP: onLlmGenerateProgress(tokens...)
    Note over VCP, LLM: Normal TTS pipeline continues
```

**Camera Features:**
- Front/back camera switching via `switchCamera()`
- Images compressed in background via `cameraExecutor`
- CameraX lifecycle-aware, bound to `viewLifecycleOwner`

---

## 4. Key Classes Reference

### 4.1 VoiceChatFragment

| Responsibility | Details |
| :--- | :--- |
| UI Binding | `FragmentVoiceChatBinding` with status text, transcript list, control buttons |
| Permissions | `RECORD_AUDIO` for ASR, `CAMERA` for vision mode |
| Camera | CameraX with `Preview` + `ImageCapture` use cases |
| Transcript | `VoiceTranscriptAdapter` with smooth scroll |
| Model Settings | Opens `VoiceModelMarketBottomSheet` for TTS/ASR model switching |

### 4.2 VoiceChatPresenter

| Responsibility | Details |
| :--- | :--- |
| Serial Processing | `Channel<SerialTask>(UNLIMITED)` with `consumeEach` |
| ASR Management | `AsrService` init, start/stop recording, speech detection |
| LLM Integration | `ChatPresenter.GenerateListener` for progress callbacks |
| TTS Processing | Segments text at delimiters `[.,!。，！？?\n、：；:]`, calls `TtsService.process()` |
| Thinking Support | Uses `GenerateResultProcessor` to separate thinking vs. normal output |
| Greeting | Speaks a localized greeting on startup |
| Service Recreation | `recreateVoiceServices()` when user switches voice models |

### 4.3 AsrService

| Responsibility | Details |
| :--- | :--- |
| Engine | Sherpa-MNN `OnlineRecognizer` (streaming ASR) |
| Audio Source | `VOICE_COMMUNICATION` with AEC + Noise Suppressor |
| Sample Rate | 16000 Hz, mono, PCM 16-bit |
| Callbacks | `onRecognizeText` (endpoint), `onSpeechDetected` (partial) |
| Mute Support | `isMuted` flag fills buffer with zeros |

### 4.4 TtsService

| Responsibility | Details |
| :--- | :--- |
| Engine | MNN TTS native library (`mnn_tts`) |
| Init | Async via `Deferred`, supports `waitForInitComplete()` |
| Process | `process(text, id)` returns `ShortArray` (PCM audio) |
| Language | Configurable via `setLanguage()` |

### 4.5 AudioChunksPlayer

| Responsibility | Details |
| :--- | :--- |
| Engine | Android `AudioTrack` (streaming mode) |
| Chunk Playback | `playChunk(ShortArray)` writes PCM data |
| Completion | `endChunk()` sets a marker; invokes `onCompletionListener` when reached |
| Reset | Preserves listener reference across `reset()` calls |

---

## 5. Data Flow Diagram

```mermaid
flowchart LR
    subgraph Input
        MIC["🎤 Microphone"]
        CAM["📷 Camera"]
    end

    subgraph "On-Device AI Pipeline"
        ASR["ASR<br/>(Sherpa-MNN)"]
        LLM["LLM<br/>(MNN)"]
        TTS["TTS<br/>(MNN)"]
    end

    subgraph Output
        SPK["🔊 Speaker"]
        SCR["📱 Screen<br/>(Transcript)"]
    end

    MIC -->|"PCM 16kHz"| ASR
    CAM -->|"JPEG"| LLM
    ASR -->|"Text"| LLM
    LLM -->|"Tokens"| TTS
    LLM -->|"Text"| SCR
    TTS -->|"PCM Audio"| SPK
```

---

## 6. Initialization Sequence

```mermaid
sequenceDiagram
    participant F as VoiceChatFragment
    participant P as VoiceChatPresenter
    participant TTS as TtsService
    participant ASR as AsrService
    participant AP as AudioChunksPlayer

    F ->> F: checkAndRequestPermission()
    F ->> P: new VoiceChatPresenter()
    F ->> P: start()
    P ->> P: addGenerateListener(this)
    P ->> TTS: init(modelDir)
    P ->> AP: initAudio(sampleRate)
    P ->> ASR: initRecognizer()
    ASR -->> P: ready
    P ->> ASR: startRecord()
    P ->> F: updateStatus(LISTENING)
    P ->> F: showGreetingMessage()
    P ->> TTS: processTtsText(greeting)
    TTS -->> P: ShortArray
    P ->> AP: playChunk(greeting audio)
    P ->> AP: endChunk()
    AP -->> P: onCompletion
    P ->> F: updateStatus(LISTENING)
    P ->> ASR: startRecord()
    Note over F, AP: System ready for voice input
```

---

## 7. Shutdown & Cleanup

```mermaid
sequenceDiagram
    participant F as VoiceChatFragment
    participant P as VoiceChatPresenter
    participant ASR as AsrService
    participant TTS as TtsService
    participant AP as AudioChunksPlayer

    F ->> P: stop()
    P ->> P: isStopped = true
    P ->> LLM: stopGenerate()
    P ->> LLM: removeGenerateListener()
    P ->> ASR: stopRecord()
    P ->> AP: destroy()
    P ->> TTS: destroy()
    P ->> P: taskChannel.close()
    F ->> F: cameraExecutor.shutdown()
    F ->> F: popBackStack()
```
