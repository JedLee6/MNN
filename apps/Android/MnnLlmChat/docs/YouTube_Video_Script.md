# YouTube Video Script — MNN Chat: ChatGPT-Like Real-Time Voice & Video Chat, Running Entirely On Your Phone

> **Estimated Duration**: 8–10 minutes  
> **Tone**: Enthusiastic, technical but accessible, developer-to-audience  
> **Format**: Talking head + screen recording demo + architecture diagrams

---

## PART 1 — Hook & Introduction (0:00 – 1:30)

**[TALKING HEAD — energetic opening]**

Hey everyone, I'm Jad, and welcome back to the channel.

So, we've all seen the massive hype around ChatGPT's Advanced Voice Mode — the way it sees the world through your camera, talks to you naturally, and actually stops talking when you interrupt it. It's amazing.

But here's the thing — it needs the cloud. It needs the internet. And it definitely needs a subscription.

**What if you could have all of that — running completely offline, on your phone?**

**[Show phone screen briefly]**

That's exactly what I built. This is **MNN Chat** — an open-source Android app that delivers a **1:1 real-time voice chat experience matching ChatGPT**, powered by the MNN inference engine, running **entirely locally on your phone** — no cloud, no internet required.

Let me tell you what this app can do:

- ✅ **Multiple multimodal models available** — including Qwen 2.5 from 0.6B to 32B, with **over 40 models** to choose from: GPT, DeepSeek, LLaMA, Gemma, and more.
- ✅ **Real-time voice conversation** with **acoustic echo cancellation** — interrupt the AI anytime, mid-speech.
- ✅ **Live video chat** — let the AI see you and the world around you through your camera.
- ✅ **Fully offline** — 100% control over your privacy.
- ✅ **Forever free and open-source** — no payments, no usage limits, no time restrictions, no ads, no catches.

A pure, smooth, and secure **on-device AI companion**.

Let me show you.

---

## PART 2 — Live Demo (1:30 – 5:00)

**[SCREEN RECORDING — phone screen]**

> **Note to viewer:** Throughout this entire demo, there is **no editing and no speed-up** — everything you see is happening in **real time**.

### 2.1 — Starting the App

So here's the app. When you open it, you see the model selection screen. MNN Chat supports a huge variety of models — Qwen, DeepSeek, LLaMA, Gemma, and more. I've got a model loaded here, and you can see the text chat interface.

But the magic happens when you tap **this microphone button** to enter **Voice Chat Mode**.

### 2.2 — Voice Chat Demo (Without Vision)

**[SCREEN RECORDING — Voice Chat mode, no camera]**

And just like that, we're in voice chat. The app is initializing — loading the ASR model for speech recognition and the TTS model for text-to-speech. Both are running locally on the device.

Now it's ready. Listen —

*[App speaks greeting: "Hi, I'm your AI assistant. How can I help you today?"]*

That greeting was generated completely on-device using the TTS engine. Now let me talk to it:

*[Speak: "Can you explain what a neural network is in simple terms?"]*

Watch the status — it goes from **Listening**, to **Processing**, to **Thinking**, and then it starts **Speaking** the response.

*[Let the AI respond]*

Notice how **fast** the response comes back. Without the vision model, the response speed is noticeably quicker — because the LLM doesn't need to process any image data. For pure voice conversations, this gives you a snappy, low-latency experience.

### 2.3 — Voice Interruption Demo

**[SCREEN RECORDING — interrupt the AI mid-speech]**

Now here's the cool part — I can **interrupt the AI mid-sentence** just by speaking.

*[Speak while AI is talking: "Actually, can you give me a shorter answer?"]*

See that? The AI **immediately stopped**, and it's now processing my new request. This is **full-duplex voice interaction** — the microphone stays active even while the AI is speaking. The moment it detects my voice, it cancels the current response and starts fresh.

Just like a real conversation.

The app uses Android's hardware **Acoustic Echo Canceler** so it doesn't hear its own voice through the mic. This is critical — without it, the AI's TTS output would be picked up by the microphone and cause a feedback loop.

### 2.4 — Live Video Vision Demo (Front & Back Camera)

**[SCREEN RECORDING — enable camera]**

Now, here's where it gets really exciting. See this camera button? Let me tap it.

*[Tap camera button — camera preview appears with back camera]*

The app is now showing a live camera preview. Let me point it at something and ask a question.

*[Point camera at an object — e.g., a book, a plant, a keyboard]*

*[Speak: "What do you see in front of me?"]*

The app automatically **captures a photo** the moment I start speaking, compresses it, and sends it along with my voice transcript to the vision-capable language model. The AI can now **"see"** what I'm looking at and describe it.

*[Let the AI respond]*

This is essentially the same experience as ChatGPT's live video mode — but it's all happening **on my phone, completely offline**.

Now let me switch to the **front camera** —

*[Tap camera switch button]*

*[Speak: "What do I look like right now?"]*

*[Let the AI respond]*

You can freely switch between front and back cameras during the conversation. The vision model handles both seamlessly.

---

## PART 3 — How It Works (5:00 – 7:30)

**[TALKING HEAD + architecture diagram overlay]**

Alright, let's talk about how this actually works under the hood. There are **four key components** in the pipeline.

### 3.1 — The Pipeline

**[Show data flow diagram]**

The flow is straightforward:

1. **Microphone** captures your voice as raw PCM audio at 16kHz.
2. **ASR (Automatic Speech Recognition)** — powered by Sherpa-MNN — converts that audio into text in real time, right on the device.
3. **LLM (Large Language Model)** — powered by MNN — takes the transcribed text (and optionally a camera image) and generates a response, token by token.
4. **TTS (Text-to-Speech)** — also powered by MNN — converts the response text into speech audio.
5. **Speaker** plays back the generated audio through Android's `AudioTrack`.

All four of these — ASR, LLM, TTS, and audio playback — run **entirely on-device** using MNN's optimized inference engine for ARM processors.

### 3.2 — How Interruption Works

**[Show interruption sequence diagram]**

The voice interruption feature is really interesting from an engineering perspective.

The key insight is: **we never stop recording**. Even while the AI is speaking, the microphone and ASR engine stay active. The ASR has a callback called `onSpeechDetected` that fires the instant it detects any speech in the audio stream.

When that fires during AI output, the app immediately:
- Stops the LLM generation
- Stops audio playback
- Clears the audio buffer
- Returns to listening mode

All within milliseconds. That's what gives it that natural, conversational feel.

For echo cancellation, we use a dual approach: Android's hardware **Acoustic Echo Canceler** as the primary mechanism, with a software **Auto-Mute mode** as fallback that silences the mic when the AI speaks.

### 3.3 — How Vision Works

**[Show vision sequence diagram]**

For the vision feature, we use Android's **CameraX** library. When the user starts speaking, the app captures a frame from the camera in the background, compresses it to JPEG, and attaches it to the message sent to the LLM.

The LLM has to be a **vision-capable model** — like Qwen-VL — so it can process both text and images. The app automatically detects whether the current model supports vision and only shows the camera button if it does.

### 3.4 — Sequential Processing

One thing I want to highlight is the **serial task processing** architecture. All the operations — handling ASR results, processing LLM tokens, generating TTS audio, and managing playback completion — are serialized through a single Kotlin coroutine channel.

This means there are no race conditions, no threading issues. Everything happens in the right order, every time. It's a really clean pattern for managing complex, real-time AI pipelines.

---

## PART 4 — Technology Stack (7:30 – 8:15)

**[TALKING HEAD with text overlay of tech stack]**

Let me quickly go over the tech stack:

- **MNN** — Alibaba's Mobile Neural Network framework. Handles all the model inference — LLM, TTS. Incredibly optimized for mobile ARM chips.
- **Sherpa-MNN** — A streaming ASR engine that provides real-time speech recognition with endpoint detection.
- **CameraX** — Android Jetpack's camera library for the vision feature.
- **Kotlin Coroutines** — For managing all the async operations cleanly.
- The app is written in **Kotlin** and uses standard Android architecture patterns.

And the best part? **It's all open source.** You can check out the repository — link in the description — and run it yourself.

---

## PART 5 — Future Plans (8:15 – 9:15)

**[TALKING HEAD — forward-looking, excited]**

So what's next? Let me share the roadmap.

Right now, the TTS and ASR engines **only support English**. That's the biggest limitation. So my number one priority is **multi-language support**.

Here's the plan: I'm going to combine the **Sherpa-ONNX** project with the MNN Chat project to dramatically improve ASR accuracy and add support for many more languages.

Specifically:

1. **Multi-Language ASR with Whisper** — I plan to integrate OpenAI's **Whisper Large V3** model, which supports **over 90 languages**. This will give us high-accuracy, multi-language speech recognition — all running on-device.

2. **Multi-Language TTS with Piper** — For text-to-speech, I'm looking at **Piper TTS**, which supports **over 40 languages**. Imagine having a natural-sounding AI voice in Mandarin, Japanese, Spanish, French, German — all offline on your phone.

3. **Streaming TTS** — Instead of generating the entire sentence and then playing it, I want to stream audio chunks as they're generated, reducing the first-audio latency even further.

4. **Multi-turn Vision** — Currently, each turn captures a single photo. I'd like to support continuous video understanding, where the AI maintains visual awareness across the entire conversation.

5. **iOS Support** — The MNN framework already supports iOS, so bringing this voice chat experience to iPhone is definitely on the roadmap.

The goal is to make MNN Chat a truly **universal, multi-language, on-device AI assistant** — free and open-source for everyone.

---

## PART 6 — Closing (9:15 – 9:45)

**[TALKING HEAD — warm closing]**

That's it for today! I hope this gives you a good idea of what's possible with **on-device AI** right now. We're at a point where you can have a full ChatGPT-like voice and video conversation with an AI — completely offline, completely private, running on hardware you already own.

If you found this interesting, please give it a **like**, **subscribe**, and drop a **comment** below. I'd love to hear what features you'd want to see — especially which **languages** you'd like supported first.

The full source code is available on **GitHub** — link in the description. Feel free to **star** the repo, open issues, or contribute.

Thanks for watching, and I'll see you in the next one!

---

## Appendix — B-Roll & Visual Suggestions

| Timestamp | Visual |
| :--- | :--- |
| 0:00 – 0:15 | Quick montage: phone running app, voice waves, camera POV, "100% OFFLINE" text flash |
| 0:15 – 1:30 | Talking head with feature bullet points appearing as animated text overlays |
| 1:30 – 2:30 | Full-screen phone recording: voice chat without camera (highlight speed) |
| 2:30 – 3:30 | Full-screen phone recording: voice interruption demo (highlight "AI stops instantly") |
| 3:30 – 5:00 | Full-screen phone recording: video chat with camera switch (back → front) |
| 5:00 – 7:30 | Architecture diagrams (from technical doc) overlaid on talking head |
| 7:30 – 8:15 | Text overlay bullet points of tech stack |
| 8:15 – 9:15 | Animated roadmap: language flags 🌍, Whisper logo, Piper logo, iOS icon |
| 9:15 – 9:45 | GitHub repo page, subscribe animation, language poll overlay |
