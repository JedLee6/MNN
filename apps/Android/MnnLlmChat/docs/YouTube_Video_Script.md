# YouTube Video Script — MNN Chat: ChatGPT-Like Live Video Chat, Running Entirely On Your Phone

> **Estimated Duration**: 8–10 minutes  
> **Tone**: Enthusiastic, technical but accessible, developer-to-audience  
> **Format**: Talking head + screen recording demo + architecture diagrams

---

## PART 1 — Hook & Introduction (0:00 – 1:00)

**[TALKING HEAD — energetic opening]**

Hey everyone! What if I told you that you could have a **ChatGPT-like live video chat** — voice conversations, real-time camera vision, speech interruption — all running **completely on your phone**, with **zero cloud, zero API calls, zero internet required**?

That's exactly what I built. And today, I'm going to show you how it works.

**[Show phone screen briefly]**

This is **MNN Chat** — an open-source Android app powered by Alibaba's MNN inference framework. It runs large language models, speech recognition, and text-to-speech **entirely on-device**. And the feature I'm most excited about? **Live voice and video chat mode** — just like ChatGPT's Advanced Voice, but running locally on a phone.

Let me show you.

---

## PART 2 — App Overview & Demo (1:00 – 4:00)

### 2.1 — Starting the App

**[SCREEN RECORDING — phone screen]**

So here's the app. When you open it, you see the model selection screen. MNN Chat supports a variety of models — Qwen, DeepSeek, LLaMA, and more. I've got a model loaded here, and you can see the text chat interface — pretty standard.

But the magic happens when you tap **this microphone button** up here to enter **Voice Chat Mode**.

### 2.2 — Voice Chat Demo

**[SCREEN RECORDING — Voice Chat mode]**

And just like that, we're in voice chat. You can see the app is initializing — it's loading the ASR model for speech recognition and the TTS model for text-to-speech. Both are running locally.

Now it's ready. Listen —

*[App speaks greeting: "Hi, I'm your AI assistant. How can I help you today?"]*

That greeting was generated completely on-device using the TTS engine. Now let me talk to it:

*[Speak: "Can you explain what a neural network is in simple terms?"]*

Watch the status — it goes from **Listening**, to **Processing**, to **Thinking** — you can actually see when the model is in its thinking phase — and then it starts **Speaking** the response.

*[Let the AI respond for a few seconds]*

And here's the cool part —

### 2.3 — Voice Interruption Demo

**[SCREEN RECORDING — interrupt the AI mid-speech]**

I can **interrupt it mid-sentence** just by speaking!

*[Speak while AI is talking: "Actually, can you give me a shorter answer?"]*

See that? The AI immediately stopped, and it's now processing my new request. This is **full-duplex voice interaction** — the microphone stays active even while the AI is speaking. The moment it detects my voice, it cancels the current response and starts fresh. Just like a real conversation.

### 2.4 — Live Video Vision Demo

**[SCREEN RECORDING — enable camera]**

Now, here's where it gets really interesting. See this camera button? Let me tap it.

*[Tap camera button — camera preview appears]*

Now the app is showing a live camera preview. I'm going to point it at something and ask a question.

*[Point camera at an object — e.g., a book, a plant, a keyboard]*

*[Speak: "What do you see in front of me?"]*

The app automatically **captures a photo** the moment I start speaking, compresses it, and sends it along with my voice transcript to the vision-capable language model. The AI can now "see" what I'm looking at and describe it.

*[Let the AI respond]*

This is essentially the same experience as ChatGPT's live video mode — but it's all happening **on my phone, offline**.

I can also switch between front and back cameras with this button here.

*[Tap camera switch button]*

---

## PART 3 — How It Works (4:00 – 7:00)

**[TALKING HEAD + architecture diagram overlay]**

Alright, let's talk about how this actually works under the hood. There are **four key components** in the pipeline:

### 3.1 — The Pipeline

**[Show data flow diagram]**

The flow is simple:

1. **Microphone** captures your voice as raw PCM audio at 16kHz.
2. **ASR (Automatic Speech Recognition)** — powered by Sherpa-MNN — converts that audio into text in real time, right on the device.
3. **LLM (Large Language Model)** — powered by MNN — takes the transcribed text (and optionally a camera image) and generates a response, token by token.
4. **TTS (Text-to-Speech)** — also powered by MNN — converts the response text into speech audio.
5. **Speaker** plays back the generated audio through Android's `AudioTrack`.

All four of these — ASR, LLM, TTS, and audio playback — run **entirely on-device** using MNN's optimized inference engine for ARM processors.

### 3.2 — How Interruption Works

**[Show interruption sequence diagram]**

Now, the voice interruption feature is really interesting from an engineering perspective.

The key insight is: **we never stop recording**. Even while the AI is speaking, the microphone and ASR engine stay active. The ASR has a callback called `onSpeechDetected` that fires the instant it detects any speech in the audio stream.

When that fires during AI output, the app immediately:
- Stops the LLM generation
- Stops audio playback
- Clears the audio buffer
- Returns to listening mode

All within milliseconds. That's what gives it that natural, conversational feel.

We also handle echo cancellation — because the AI's own voice could be picked up by the mic! We use Android's hardware **Acoustic Echo Canceler** to filter that out. And as a fallback, there's a software **Auto-Mute mode** that silences the mic when the AI speaks.

### 3.3 — How Vision Works

**[Show vision sequence diagram]**

For the vision feature, we use Android's **CameraX** library. When the user starts speaking, the app captures a frame from the camera in the background, compresses it to JPEG, and attaches it to the message sent to the LLM.

The LLM has to be a **vision-capable model** — like Qwen-VL — so it can process both text and images. The app automatically detects whether the current model supports vision and only shows the camera button if it does.

### 3.4 — Sequential Processing

One thing I want to highlight is the **serial task processing** architecture. All the operations — handling ASR results, processing LLM tokens, generating TTS audio, and managing playback completion — are serialized through a single Kotlin coroutine channel.

This means there are no race conditions, no threading issues. Everything happens in the right order, every time. It's a really clean pattern for managing complex, real-time AI pipelines.

---

## PART 4 — Technology Stack (7:00 – 8:00)

**[TALKING HEAD with text overlay of tech stack]**

Let me quickly go over the tech stack:

- **MNN** — Alibaba's Mobile Neural Network framework. Handles all the model inference — LLM, TTS. It's incredibly optimized for mobile ARM chips.
- **Sherpa-MNN** — A streaming ASR engine that provides real-time speech recognition with endpoint detection.
- **CameraX** — Android Jetpack's camera library for the vision feature.
- **Kotlin Coroutines** — For managing all the async operations cleanly.
- The app is written in **Kotlin** and uses standard Android architecture patterns.

And the best part? **It's all open source.** You can check out the repository — link in the description — and run it yourself.

---

## PART 5 — Future Plans (8:00 – 9:00)

**[TALKING HEAD — forward-looking, excited]**

So what's next? I have several things planned:

1. **Optimized TTS Queue** — Right now, TTS generation happens sequentially. I'm working on a queue system where audio segments can be pre-generated while the current one is still playing, making responses feel even more seamless.

2. **Streaming TTS** — Instead of generating the entire sentence and then playing, I want to stream audio chunks as they're generated, reducing the first-audio latency to near-zero.

3. **Multi-turn Vision** — Currently, each turn captures a single photo. I'd like to support continuous video understanding, where the AI maintains awareness of the visual context across a conversation.

4. **More Models** — Support for more on-device models, including smaller, faster ones optimized specifically for voice interactions.

5. **iOS Support** — The MNN framework already supports iOS, so bringing this voice chat experience to iPhone is definitely on the roadmap.

---

## PART 6 — Closing (9:00 – 9:30)

**[TALKING HEAD — warm closing]**

That's it for today! I hope this gives you a good idea of what's possible with **on-device AI** right now. We're at a point where you can have a full ChatGPT-like voice and video conversation with an AI — completely offline, completely private, running on hardware you already own.

If you found this interesting, please give it a **like**, **subscribe**, and drop a **comment** below. I'd love to hear what features you'd want to see added.

The full source code is available on GitHub — link in the description. Feel free to star the repo, open issues, or contribute.

Thanks for watching, and I'll see you in the next one!

---

## Appendix — B-Roll & Visual Suggestions

| Timestamp | Visual |
| :--- | :--- |
| 0:00 – 0:10 | Quick montage: phone running app, voice waves, camera POV |
| 1:00 – 4:00 | Full-screen phone recording (portrait or landscape crop) |
| 4:00 – 7:00 | Architecture diagrams (from technical doc) overlaid on talking head |
| 7:00 – 8:00 | Text overlay bullet points of tech stack |
| 8:00 – 9:00 | Animated list of future features |
| 9:00 – 9:30 | GitHub repo page, subscribe animation |
