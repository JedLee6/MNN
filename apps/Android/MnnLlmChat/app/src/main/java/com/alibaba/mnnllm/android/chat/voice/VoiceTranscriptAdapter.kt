// Created by ruoyi.sjd on 2025/06/18.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.chat.voice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.utils.UiUtils.getThemeColor

data class Transcript(
    val isUser: Boolean,
    val text: String,
    val isLoading: Boolean = false
)

class VoiceTranscriptAdapter(private val transcripts: MutableList<Transcript>) :
    RecyclerView.Adapter<VoiceTranscriptAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageTextView: TextView = view.findViewById(R.id.tv_message)
        val loadingIndicator: View = view.findViewById(R.id.loading_indicator)

        fun bind(transcript: Transcript) {
            messageTextView.text = transcript.text
            val color = if (transcript.isUser) {
                itemView.context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
            } else {
                itemView.context.getThemeColor(com.google.android.material.R.attr.colorOnSurface)
            }
            messageTextView.setTextColor(color)
            
            // Show loading only for AI messages that are marked as loading
            loadingIndicator.visibility = if (!transcript.isUser && transcript.isLoading) View.VISIBLE else View.GONE
        }

        fun updateText(transcript: Transcript) {
            messageTextView.text = transcript.text.trim()
            loadingIndicator.visibility = if (!transcript.isUser && transcript.isLoading) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice_transcript, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(transcripts[position])
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Only update the text content without changing visibility
            holder.updateText(transcripts[position])
        }
    }

    override fun getItemCount() = transcripts.size

    fun addTranscript(transcript: Transcript) {
        transcripts.add(transcript)
        notifyItemInserted(transcripts.size - 1)
    }

    fun updateLastTranscript(newText: String) {
        if (transcripts.isNotEmpty()) {
            val lastIndex = transcripts.size - 1
            val oldTranscript = transcripts[lastIndex]
            // Keep loading state as true during updates
            transcripts[lastIndex] = oldTranscript.copy(text = newText)
            notifyItemChanged(lastIndex, Unit)
        }
    }
    
    fun updateLastTranscriptLoading(isLoading: Boolean) {
        if (transcripts.isNotEmpty()) {
            val lastIndex = transcripts.size - 1
            val oldTranscript = transcripts[lastIndex]
            transcripts[lastIndex] = oldTranscript.copy(isLoading = isLoading)
            notifyItemChanged(lastIndex, Unit)
        }
    }
}
