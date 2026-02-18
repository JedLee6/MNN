// Created by ruoyi.sjd on 2025/06/18.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.chat.voice

import android.app.Activity
import android.media.AudioManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import com.alibaba.mnnllm.android.asr.AsrService
import com.alibaba.mnnllm.android.audio.AudioChunksPlayer
import com.alibaba.mnnllm.android.chat.ChatPresenter
import com.alibaba.mnnllm.android.chat.GenerateResultProcessor
import com.alibaba.mnnllm.android.utils.VoiceModelPathUtils
import com.taobao.meta.avatar.tts.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoiceChatPresenterState {
    INITIALIZING,
    LISTENING,
    GENERATING_TEXT,
    PLAYING,
    PLAY_END
}

// Sealed class for sequential tasks
sealed class SerialTask {
    abstract val generationId: Long

    data class ProcessProgress(val progress: String, val isFirstChunk: Boolean, val responseBuilder: StringBuilder, val ttsSegmentBuffer: StringBuilder, override val generationId: Long) : SerialTask()
    data class ProcessFinalChunk(val ttsSegmentBuffer: StringBuilder, override val generationId: Long) : SerialTask()
    data class HandleAsrResult(val text: String, override val generationId: Long) : SerialTask()
    data class OnTtsComplete(override val generationId: Long) : SerialTask()
}

enum class VoiceChatState {
    CONNECTING,
    GREETING,
    LISTENING,
    PROCESSING,
    THINKING,
    SPEAKING,
    STOPPING,
    ERROR
}

class VoiceChatPresenter(
    private val activity: Activity,
    private val view: VoiceChatView,
    private val chatPresenter: ChatPresenter,
    private val lifecycleScope: CoroutineScope
) : ChatPresenter.GenerateListener {
    companion object {
        const val TAG = "VoiceChatPresenter"
    }

    private var asrService: AsrService? = null
    private var ttsService: TtsService? = null
    private var audioPlayer: AudioChunksPlayer? = null
    private var audioManager: AudioManager = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager

    private var isRecording = false
    private var isSpeaking = false
    private var isProcessingLlm = false
    private var isStopped = false
    private var isStoppingGeneration = false
    private var isGenerationFinished = false
    
    private var isMuted = false
    
    // Interruption support
    private var currentGenerationId = 0L
    @Volatile private var isInterrupted = false

    // For handling LLM generation progress with thinking support
    private var generateResultProcessor: GenerateResultProcessor? = null
    private var responseBuilder = StringBuilder()
    private var ttsSegmentBuffer = StringBuilder()
    private var isFirstChunk = true
    private var isThinking = false
    
    private var currentStatus: VoiceChatPresenterState = VoiceChatPresenterState.INITIALIZING
        set(value) {
            if (field != value) {
                Log.d(TAG, "Status changed from ${field.name} to ${value.name}")
                field = value
            }
        }

    // Channel-based sequential processor
    private val taskChannel = Channel<SerialTask>(Channel.UNLIMITED)
    private val serialProcessor = lifecycleScope.launch {
        taskChannel.consumeEach { task ->
            if (!isStopped) {
                processTask(task)
            }
        }
    }

    private suspend fun processTask(task: SerialTask) {
        // Check if task belongs to current generation (except HandleAsrResult which starts a new one)
        if (task !is SerialTask.HandleAsrResult && task.generationId != currentGenerationId) {
            Log.d(TAG, "Ignoring task from old generation: ${task.generationId}, current: $currentGenerationId")
            return
        }

        when (task) {
            is SerialTask.ProcessProgress -> {
                if (isStoppingGeneration) return
                Log.d(TAG, "progress is ${task.progress}")
                
                if (task.isFirstChunk) {
                    // Initialize processor for new generation
                    generateResultProcessor = GenerateResultProcessor()
                    generateResultProcessor?.generateBegin()
                    // Transcript already added in HandleAsrResult
                }
                
                // Process the progress through GenerateResultProcessor
                generateResultProcessor?.process(task.progress)
                
                // Check if we're in thinking mode
                val thinkingContent = generateResultProcessor?.getThinkingContent() ?: ""
                val normalOutput = generateResultProcessor?.getNormalOutput() ?: ""
                val wasThinking = isThinking
                isThinking = thinkingContent.isNotEmpty() && normalOutput.isEmpty()
                
                // Update status based on thinking state
                if (isThinking && !wasThinking) {
                    // Just entered thinking mode
                    withContext(Dispatchers.Main) { view.updateStatus(VoiceChatState.THINKING) }
                    Log.d(TAG, "Entering thinking mode")
                } else if (!isThinking && wasThinking) {
                    // Just exited thinking mode
                    withContext(Dispatchers.Main) { view.updateStatus(VoiceChatState.PROCESSING) }
                    Log.d(TAG, "Exiting thinking mode")
                }
                
                // Only process normal output (not thinking content)
                if (normalOutput.isNotEmpty()) {
                    Log.d(TAG, "Normal output is not empty: '$normalOutput' progress: ${task.progress}")
                    task.responseBuilder.clear()
                    task.responseBuilder.append(normalOutput)
                    // NOTE: Do NOT update transcript here. Transcript updates are deferred
                    // to the audioWorker to stay in sync with TTS playback.
                    
                    // Process TTS for normal output only
                    val delimiters = "[.,!。，！？?\n、：；:]".toRegex()
                    val progressText = GenerateResultProcessor.noSlashThink(task.progress)!!
                    task.ttsSegmentBuffer.append(progressText)
                    if (delimiters.containsMatchIn(progressText) && !isThinking) {
                        val textToSpeak = task.ttsSegmentBuffer.toString()
                        task.ttsSegmentBuffer.clear()
                        Log.d(TAG, "Delimiter found. Queueing TTS: '$textToSpeak'")
                        if (!isStopped && !isStoppingGeneration) {
                            currentStatus = VoiceChatPresenterState.PLAYING
                            withContext(Dispatchers.Main) { view.updateStatus(VoiceChatState.SPEAKING) }
                            
                            // Send to TTS worker with current cumulative text for display sync
                            ttsWorkChannel.trySend(TtsWorkItem(textToSpeak, task.generationId, displayText = normalOutput))
                        }
                    }
                }
                
                Log.d(TAG, "progress is ${task.progress} end")
            }

            is SerialTask.ProcessFinalChunk -> {
                if (isStoppingGeneration) return
                Log.d(TAG, "progress is null")
                
                // Process final chunk through GenerateResultProcessor
                generateResultProcessor?.process(null)
                
                // Reset thinking state
                isThinking = false
                
                // Hide loading indicator when finished
                withContext(Dispatchers.Main) {
                    view.updateLastTranscriptLoading(false)
                }

                // Get the final cumulative normal output for display
                val finalNormalOutput = generateResultProcessor?.getNormalOutput() ?: ""

                if (task.ttsSegmentBuffer.isNotEmpty()) {
                    val textToSpeak = task.ttsSegmentBuffer.toString()
                    task.ttsSegmentBuffer.clear()
                    Log.d(TAG, "Queueing remaining buffer: '$textToSpeak'")
                    currentStatus = VoiceChatPresenterState.PLAYING
                    withContext(Dispatchers.Main) { view.updateStatus(VoiceChatState.SPEAKING) }
                    
                    // Send final text to TTS worker with complete display text
                    ttsWorkChannel.trySend(TtsWorkItem(textToSpeak, task.generationId, displayText = finalNormalOutput))
                }
                
                if (!isStoppingGeneration) {
                    // Send End of Generation signal with complete display text
                    ttsWorkChannel.trySend(TtsWorkItem("", task.generationId, isFinal = true, displayText = finalNormalOutput))
                }
                Log.d(TAG, "progress is null end")
            }



            is SerialTask.HandleAsrResult -> {
                if (isStoppingGeneration) return
                
                // If this is a new generation task, ensure we are ready
                isProcessingLlm = true
                isSpeaking = true
                isThinking = false
                // Reset interruption flag as we are starting processing
                isInterrupted = false
                
                // Auto-mute if enabled
                if (isAutoMicEnabled) {
                    muteMicrophone(true)
                }

                currentStatus = VoiceChatPresenterState.GENERATING_TEXT
                withContext(Dispatchers.Main) {
                    view.addTranscript(Transcript(isUser = true, text = task.text))
                    // Add AI placeholder immediately with loading state
                    view.addTranscript(Transcript(isUser = false, text = "", isLoading = true))
                    view.updateStatus(VoiceChatState.PROCESSING)
                }
                llmGenerate(task.text)
            }
            is SerialTask.OnTtsComplete -> {
                // Check if this completion event belongs to current generation
                if (isInterrupted) {
                    Log.d(TAG, "TTS completion ignored due to interruption")
                    return
                }

                // Always handle TTS completion to ensure proper state transition
                Log.d(TAG, "TTS playback completed, transitioning to LISTENING state")
                isProcessingLlm = false
                isSpeaking = false
                isThinking = false
                
                // Auto-unmute if enabled
                if (isAutoMicEnabled) {
                    muteMicrophone(false)
                }

                currentStatus = VoiceChatPresenterState.LISTENING
                withContext(Dispatchers.Main) {
                    view.updateStatus(VoiceChatState.LISTENING)
                }
                audioPlayer?.reset()
                kotlinx.coroutines.delay(500)
                // Only start recording if we're not in the middle of stopping
                // And if not already recording
                if (!isStoppingGeneration) {
                    startRecord()
                }
            }
        }
    }

    // Pipeline Channels
    private val ttsWorkChannel = Channel<TtsWorkItem>(Channel.UNLIMITED)
    private val audioWorkChannel = Channel<AudioWorkItem>(Channel.UNLIMITED)
    
    private fun initWorkers() {
        // TTS Worker
        lifecycleScope.launch(Dispatchers.IO) {
            for (item in ttsWorkChannel) {
                if (isStopped) break
                // Generation check: discard items from previous generations
                if (item.generationId != currentGenerationId) {
                    Log.d(TAG, "TTS Worker: Discarding old item gen=${item.generationId}, current=$currentGenerationId")
                    continue
                }
                
                if (item.text.isNotEmpty()) {
                    Log.d(TAG, "TTS Worker: Processing '${item.text}'")
                    val audioData = ttsService?.process(item.text, 0)
                    // Re-check generation after blocking call returns
                    if (item.generationId != currentGenerationId) {
                        Log.d(TAG, "TTS Worker: Generation changed during processing, discarding result")
                        continue
                    }
                    if (audioData != null && audioData.isNotEmpty()) {
                        audioWorkChannel.send(AudioWorkItem(audioData, item.generationId, displayText = item.displayText))
                    } else {
                        Log.w(TAG, "TTS Worker: Failed to generate audio for '${item.text}'")
                    }
                }
                
                if (item.isFinal) {
                    // Re-check generation before sending final marker
                    if (item.generationId != currentGenerationId) {
                        Log.d(TAG, "TTS Worker: Generation changed, skipping final marker")
                        continue
                    }
                    Log.d(TAG, "TTS Worker: Sending Final marker")
                    audioWorkChannel.send(AudioWorkItem(null, item.generationId, isFinal = true, displayText = item.displayText))
                }
            }
        }
        
        // Audio Worker
        lifecycleScope.launch(Dispatchers.IO) {
            var lastPlayedGenId = -1L // Track first segment per generation
            for (item in audioWorkChannel) {
                if (isStopped) break
                // Generation check
                if (item.generationId != currentGenerationId) {
                    Log.d(TAG, "Audio Worker: Discarding old item gen=${item.generationId}, current=$currentGenerationId")
                    continue
                }
                
                if (item.audioData != null) {
                    // Re-check generation right before playing
                    if (item.generationId != currentGenerationId) {
                        Log.d(TAG, "Audio Worker: Generation changed before playChunk, skipping")
                        continue
                    }
                    
                    // 250ms pause before non-first segments for natural speech rhythm
                    val isFirstSegment = (lastPlayedGenId != item.generationId)
                    if (!isFirstSegment) {
                        kotlinx.coroutines.delay(250)
                    }
                    lastPlayedGenId = item.generationId
                    
                    // Sync transcript with audio — update UI text when this segment starts playing
                    if (!item.displayText.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            view.updateLastTranscriptLoading(false)
                            view.updateLastTranscript(item.displayText)
                        }
                    }
                    
                    Log.d(TAG, "Audio Worker: Playing chunk (${item.audioData.size} bytes)")
                    audioPlayer?.playChunk(item.audioData)
                }
                
                if (item.isFinal) {
                    // Re-check generation right before endChunk
                    if (item.generationId != currentGenerationId) {
                        Log.d(TAG, "Audio Worker: Generation changed before endChunk, skipping")
                        continue
                    }
                    // Show complete text when all audio finishes
                    if (!item.displayText.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) { view.updateLastTranscript(item.displayText) }
                    }
                    Log.d(TAG, "Audio Worker: Calling endChunk")
                    audioPlayer?.endChunk()
                }
            }
        }
    }

    fun start() {
        Log.d(TAG, "Presenter starting...")
        isStopped = false
        isGenerationFinished = false
        currentStatus = VoiceChatPresenterState.INITIALIZING
        
        // Register this presenter as an additional listener to ChatPresenter
        chatPresenter.addGenerateListener(this)
        
        // Sync initial UI state
        view.updateAutoMicButtonState(isAutoMicEnabled)
        view.updateMuteButtonState(isMuted)
        
        initWorkers() // Start pipeline workers
        initTts()
        startAsr()
    }


    private fun initAudio() {
        // Clean up existing audio player first
        audioPlayer?.destroy()
        
        audioPlayer = AudioChunksPlayer()
        
        // Set up the completion listener with more detailed logging
        audioPlayer?.setOnCompletionListener {
            Log.d(TAG, "Audio playback completed - currentStatus: ${currentStatus.name}, isSpeaking: $isSpeaking, isProcessingLlm: $isProcessingLlm")
            // Capture current ID to check validity in processTask
            val genId = currentGenerationId
            currentStatus = VoiceChatPresenterState.PLAY_END
            lifecycleScope.launch {
                Log.d(TAG, "Sending OnTtsComplete task")
                taskChannel.send(SerialTask.OnTtsComplete(genId))
            }
        }
        
        audioPlayer?.sampleRate = 44100
        audioPlayer?.start()
        Log.d(TAG, "Audio player initialized with completion listener")
    }

    private fun initTts() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (isStopped) return@launch
                
                Log.d(TAG, "Initializing TTS Service...")
                ttsService = TtsService()
                initAudio()
                withContext(Dispatchers.IO) {
                    if (isStopped) return@withContext
                    
                    val modelDir = VoiceModelPathUtils.getTtsModelPath(activity)
                    Log.i(TAG, "Using TTS model path: $modelDir")
                    
                    // Apply TTS config overrides before init
                    applyTtsConfigOverrides(modelDir)
                    
                    val initResult = ttsService?.init(modelDir)
                    if (initResult != true) {
                        Log.e(TAG, "TTS Service initialization failed with path: $modelDir")
                        if (!isStopped) withContext(Dispatchers.Main) { view.showError("TTS init failed") }
                    } else {
                        Log.d(TAG, "TTS Service initialized successfully with path: $modelDir")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS initialization failed", e)
                if (!isStopped) withContext(Dispatchers.Main) { view.showError("TTS init failed: ${e.message}") }
            }
        }
    }

    /**
     * Apply TTS configuration overrides by modifying config.json on device
     * before native init reads it. This avoids rebuilding the .so file.
     */
    private fun applyTtsConfigOverrides(modelDir: String) {
        try {
            val configFile = File(modelDir, "config.json")
            if (!configFile.exists()) {
                Log.w(TAG, "TTS config.json not found at: ${configFile.absolutePath}")
                return
            }

            val json = JSONObject(configFile.readText())

            // Override TTS parameters here:
            json.put("iter_steps", 20)      // Higher = better quality (default: 10)
            json.put("speed", 1)           // 1.0 = normal speed
            json.put("speaker_id", "M1")     // Voice style
             json.put("precision", "fp16") // Uncomment to change precision

            configFile.writeText(json.toString(2))
            Log.i(TAG, "TTS config overrides applied: iter_steps=20, speed=1.0, speaker_id=F1")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply TTS config overrides", e)
        }
    }

    private fun startAsr() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (isStopped) return@launch
                
                Log.d(TAG, "Initializing ASR Service...")
                val modelDir = VoiceModelPathUtils.getAsrModelPath(activity)
                Log.i(TAG, "Using ASR model path: $modelDir")
                asrService = AsrService(activity, modelDir)

                withContext(Dispatchers.IO) {
                    if (isStopped) return@withContext
                    asrService?.initRecognizer()
                }

                if (isStopped) return@launch
                
                // NEW: Handle immediate speech detection for interruption
                asrService?.onSpeechDetected = {
                    lifecycleScope.launch {
                        if (!isStopped && (isSpeaking || isProcessingLlm)) {
                             Log.i(TAG, "Speech detected (interruption triggered)")
                             interruptCurrentSession()
                        }
                    }
                }

                asrService?.onRecognizeText = { text ->
                    lifecycleScope.launch {
                        if (!isStopped && text.isNotEmpty()) {
                            // If we happen to be speaking/processing, we should have already interrupted via onSpeechDetected.
                            // But safeguard here just in case.
                            if (isSpeaking || isProcessingLlm) {
                                interruptCurrentSession()
                            }
                            
                            Log.i(TAG, "ASR Result: $text")
                            // Pass the *new* generation ID
                            taskChannel.send(SerialTask.HandleAsrResult(text, currentGenerationId))
                        } else {
                             Log.d(TAG, "ASR ignored empty text")
                        }
                    }
                }
                
                // Reset generation state when ASR is ready
                isGenerationFinished = false
                
                startRecord()
                currentStatus = VoiceChatPresenterState.LISTENING
                if (!isStopped) withContext(Dispatchers.Main) { 
                    view.updateStatus(VoiceChatState.LISTENING)
                    // Show and speak greeting message when all systems are ready
                    view.showGreetingMessage()
                    speakGreetingMessage()
                }
                Log.i(TAG, "ASR started successfully. Now listening.")
            } catch (e: Exception) {
                Log.e(TAG, "ASR initialization or start failed", e)
                if (!isStopped) withContext(Dispatchers.Main) { view.showError("ASR init failed: ${e.message}") }
            }
        }
    }
    
    private fun interruptCurrentSession() {
        if (!isInterrupted) {
            Log.i(TAG, "Interrupting current session")
            isInterrupted = true
            currentGenerationId++ // Invalidate old tasks in Pipeline
            
            // Stop current generation and playback
            chatPresenter.stopGenerate()
            audioPlayer?.reset() // Use reset() to stop current playback and prepare for new audio
            
            // Channel items with old generationId will be discarded by workers

            // Reset buffers
            responseBuilder.clear()
            ttsSegmentBuffer.clear()
            isFirstChunk = true

            // Update UI status to Listening
            lifecycleScope.launch(Dispatchers.Main) {
                view.updateStatus(VoiceChatState.LISTENING)
                // Ensure loading indicator is hidden if we interrupt
                view.updateLastTranscriptLoading(false)
            }
        }
    }

    private fun llmGenerate(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting LLM generation... isStopped: $isStopped")
            if (isStopped) return@launch

            // Reset generation state
            responseBuilder.clear()
            ttsSegmentBuffer.clear()
            isFirstChunk = true
            isGenerationFinished = false

            // Send message through ChatPresenter for proper session management
            chatPresenter.sendMessage(text)
        }
    }

    private fun stopRecord() {
        if (isRecording) {
            asrService?.stopRecord()
            isRecording = false
            Log.d(TAG, "Recording stopped")
        }
    }

    private fun startRecord() {
        // Allow recording even if speaking or processing to support interruption
        if (!isRecording) {
            asrService?.startRecord()
            isRecording = true
            Log.d(TAG, "Recording started")
        }
    }

    fun getCurrentStatus(): VoiceChatPresenterState {
        return currentStatus
    }

    private fun speakGreetingMessage() {
        lifecycleScope.launch {
            try {
                if (isStopped) return@launch
                
                // Get the greeting message from resources
                val greetingMessage = activity.getString(com.alibaba.mnnllm.android.R.string.voice_chat_ready_greeting)
                
                // Temporarily stop recording while speaking greeting if desired, 
                // but interruption support suggests keeping it on? 
                // For greeting, let's keep it safe.
                stopRecord()
                
                // Set status to greeting
                currentStatus = VoiceChatPresenterState.PLAYING
                withContext(Dispatchers.Main) {
                    view.updateStatus(VoiceChatState.GREETING)
                }
                
                // Generate TTS audio for greeting
                withContext(Dispatchers.IO) {
                    if (isStopped) return@withContext
                    
                    Log.d(TAG, "Speaking greeting message: $greetingMessage")
                    val audioData = ttsService?.process(greetingMessage, 0)
                    
                    if (audioData != null && audioData.isNotEmpty() && !isStopped) {
                        withContext(Dispatchers.Main) {
                            // Store the original listener
                            val originalListener = {
                                Log.d(TAG, "Audio playback completed - currentStatus: ${currentStatus.name}, isSpeaking: $isSpeaking, isProcessingLlm: $isProcessingLlm")
                                val genId = currentGenerationId
                                currentStatus = VoiceChatPresenterState.PLAY_END
                                lifecycleScope.launch {
                                    Log.d(TAG, "Sending OnTtsComplete task")
                                    taskChannel.send(SerialTask.OnTtsComplete(genId))
                                }
                                Unit
                            }
                            
                            // Set up temporary completion listener for greeting
                            audioPlayer?.setOnCompletionListener {
                                Log.d(TAG, "Greeting message playback completed")
                                lifecycleScope.launch {
                                    // Resume normal state after greeting
                                    currentStatus = VoiceChatPresenterState.LISTENING
                                    withContext(Dispatchers.Main) {
                                        view.updateStatus(VoiceChatState.LISTENING)
                                    }
                                    // Small delay then resume recording
                                    kotlinx.coroutines.delay(300)
                                    startRecord()
                                    
                                    // Restore the original completion listener for normal TTS
                                    // Do this on the main thread to avoid threading issues
                                    withContext(Dispatchers.Main) {
                                        audioPlayer?.reset()
                                        audioPlayer?.setOnCompletionListener(originalListener)
                                        Log.d(TAG, "Original completion listener restored")
                                    }
                                }
                            }
                            
                            // Play the greeting audio
                            audioPlayer?.playChunk(audioData)
                            audioPlayer?.endChunk()
                        }
                    } else {
                        Log.w(TAG, "Failed to generate TTS audio for greeting message")
                        // If TTS fails, just resume recording
                        withContext(Dispatchers.Main) {
                            currentStatus = VoiceChatPresenterState.LISTENING
                            view.updateStatus(VoiceChatState.LISTENING)
                        }
                        startRecord()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error speaking greeting message", e)
                // On error, just resume normal state
                currentStatus = VoiceChatPresenterState.LISTENING
                withContext(Dispatchers.Main) {
                    view.updateStatus(VoiceChatState.LISTENING)
                }
                startRecord()
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Presenter stopping...")
        isStopped = true
        
        // Reset generation state
        isGenerationFinished = false
        isInterrupted = true // Ensure any pending callbacks abort
        
        // Stop any ongoing generation and trigger ChatActivity's stop logic
        if (isProcessingLlm || isSpeaking) {
            chatPresenter.stopGenerate()
            if (activity is com.alibaba.mnnllm.android.chat.ChatActivity) {
                activity.onStopGenerationRequested()
            }
        }
        
        // Unregister from ChatPresenter
        chatPresenter.removeGenerateListener(this)
        
        if (isRecording) {
            try {
                asrService?.stopRecord()
                asrService = null
                isRecording = false
                Log.d(TAG, "ASR record stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping ASR record", e)
            }
        }
        try {
            audioPlayer?.destroy()
            ttsService?.destroy()
            ttsService = null
            audioPlayer = null
            Log.d(TAG, "TTS and AudioPlayer destroyed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying TTS service", e)
        }
        
        // Cleanup serial processor
        try {
            taskChannel.close()
            ttsWorkChannel.close()
            audioWorkChannel.close()
            Log.d(TAG, "Serial processor and work channels closed.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serial processor", e)
        }
    }

    fun toggleSpeaker(isSpeakerOn: Boolean) {
        audioManager.isSpeakerphoneOn = isSpeakerOn
        Log.d(TAG, "Speaker toggled: $isSpeakerOn")
    }

    fun stopGeneration() {
        Log.d(TAG, "Stopping generation...")
        if (isProcessingLlm || isSpeaking) {
            isStoppingGeneration = true
            isGenerationFinished = false
            currentGenerationId++ // Invalidate pipeline items first
            isInterrupted = true
            
            // Stop generation in ChatPresenter
            chatPresenter.stopGenerate()
            
            // Trigger ChatActivity's stop logic
            if (activity is com.alibaba.mnnllm.android.chat.ChatActivity) {
                activity.onStopGenerationRequested()
            }
            
            // Stop current playback immediately (reset = stop + recreate)
            audioPlayer?.reset()

            // Reset buffers
            responseBuilder.clear()
            ttsSegmentBuffer.clear()
            isFirstChunk = true

            isProcessingLlm = false
            isSpeaking = false
            isThinking = false
            currentStatus = VoiceChatPresenterState.LISTENING
            
            lifecycleScope.launch {
                withContext(Dispatchers.Main) {
                    view.updateStatus(VoiceChatState.STOPPING)
                    view.updateLastTranscriptLoading(false)
                }

                // Auto-unmute if enabled
                if (isAutoMicEnabled) {
                    muteMicrophone(false)
                }

                // Short delay to let pipeline drain stale items
                kotlinx.coroutines.delay(300)

                withContext(Dispatchers.Main) {
                    view.updateStatus(VoiceChatState.LISTENING)
                }

                isStoppingGeneration = false
                isInterrupted = false
                startRecord()
            }
        }
    }
    
    // ... existing mute implementation ...
    private var isAutoMicEnabled = false

    fun toggleMute() {
        muteMicrophone(!isMuted)
    }

    fun toggleAutoMic() {
        isAutoMicEnabled = !isAutoMicEnabled
        lifecycleScope.launch(Dispatchers.Main) {
            view.updateAutoMicButtonState(isAutoMicEnabled)
        }
        Log.d(TAG, "Auto-mic toggled: $isAutoMicEnabled")
    }

    fun muteMicrophone(muted: Boolean) {
        if (isMuted != muted) {
            isMuted = muted
            asrService?.setMuted(muted)
            Log.d(TAG, "Microphone mute state changed to: $muted")
            lifecycleScope.launch(Dispatchers.Main) {
                view.updateMuteButtonState(muted)
            }
        }
    }
    
    /**
     * Recreate ASR and TTS services with new models
     * This method should be called when the default voice models have changed
     */
    fun recreateVoiceServices() {
        Log.d(TAG, "Recreating voice services due to model changes...")
        
        lifecycleScope.launch {
            try {
                // Stop current services
                stopRecord()
                
                // Reset generation state
                isGenerationFinished = false
                
                // Cleanup existing services
                asrService?.stopRecord()
                asrService = null
                
                ttsService?.destroy()
                ttsService = null
                
                // Show connecting state
                currentStatus = VoiceChatPresenterState.INITIALIZING
                withContext(Dispatchers.Main) {
                    view.updateStatus(VoiceChatState.CONNECTING)
                }
                
                // Reinitialize services with new models
                initTts()
                startAsr()
                
                // Restore mute state
                asrService?.setMuted(isMuted)
                
                Log.d(TAG, "Voice services recreated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error recreating voice services", e)
                if (!isStopped) {
                    withContext(Dispatchers.Main) {
                        view.showError("Failed to recreate voice services: ${e.message}")
                    }
                }
            }
        }
    }
    
    // ChatPresenter.GenerateListener implementation
    override fun onGenerateStart() {
        // No additional action needed for voice chat UI
    }
    
    override fun onLlmGenerateProgress(progress: String?, generateResultProcessor: GenerateResultProcessor) {
        if (isStopped || isStoppingGeneration || progress == null) return
        if (isInterrupted) return // Ignore if interrupted
        
        lifecycleScope.launch {
            if (isStopped || isStoppingGeneration) return@launch
            
            if (isFirstChunk) {
                taskChannel.send(SerialTask.ProcessProgress(progress, true, responseBuilder, ttsSegmentBuffer, currentGenerationId))
                isFirstChunk = false
            } else {
                taskChannel.send(SerialTask.ProcessProgress(progress, false, responseBuilder, ttsSegmentBuffer, currentGenerationId))
            }
        }
    }
    
    override fun onDiffusionGenerateProgress(progress: String?, diffusionDestPath: String?) {
        // Not used in voice chat
    }
    
    override fun onGenerateFinished(benchMarkResult: HashMap<String, Any>) {
        if (isStopped || isStoppingGeneration) return
        if (isInterrupted) return
        
        if (isGenerationFinished) {
            Log.d(TAG, "onGenerateFinished already processed, ignoring duplicate call")
            return
        }
        
        isGenerationFinished = true
        Log.d(TAG, "onGenerateFinished called, sending ProcessFinalChunk task")
        
        lifecycleScope.launch {
            if (!isStoppingGeneration) {
                taskChannel.send(SerialTask.ProcessFinalChunk(ttsSegmentBuffer, currentGenerationId))
            }
        }
    }
    
    // Work Items for Pipeline
    private data class TtsWorkItem(
        val text: String, 
        val generationId: Long, 
        val isFinal: Boolean = false,
        val displayText: String = ""
    )

    private data class AudioWorkItem(
        val audioData: ShortArray?, 
        val generationId: Long, 
        val isFinal: Boolean = false,
        val displayText: String? = null
    )



}

interface VoiceChatView {
    fun updateStatus(state: VoiceChatState)
    fun addTranscript(transcript: Transcript)
    fun updateLastTranscript(text: String)
    fun updateLastTranscriptLoading(isLoading: Boolean)
    fun showError(message: String)
    fun stopGeneration()
    fun showGreetingMessage()
    fun updateMuteButtonState(isMuted: Boolean)
    fun updateAutoMicButtonState(isEnabled: Boolean)
}
