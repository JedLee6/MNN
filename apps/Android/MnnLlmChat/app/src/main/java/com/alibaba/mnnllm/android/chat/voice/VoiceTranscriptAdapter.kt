// Created by ruoyi.sjd on 2025/06/18.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.chat.voice

import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
    val spokenLength: Int = 0  // boundary: [0,spokenLength)=spoken(gray), [spokenLength,end)=speaking(white)
)

class VoiceTranscriptAdapter(private val transcripts: MutableList<Transcript>) :
    RecyclerView.Adapter<VoiceTranscriptAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageTextView: TextView = view.findViewById(R.id.tv_message)

        fun bind(transcript: Transcript) {
            if (transcript.isUser) {
                messageTextView.text = transcript.text
                messageTextView.setTextColor(
                    itemView.context.getThemeColor(com.google.android.material.R.attr.colorPrimary)
                )
            } else {
                applyHighlight(transcript)
            }
            messageTextView.setTextColor(color)
        }

        fun updateText(transcript: Transcript) {
            if (transcript.isUser) {
                messageTextView.text = transcript.text.trim()
            } else {
                applyHighlight(transcript)
            }
        }

        private fun applyHighlight(transcript: Transcript) {
            val text = transcript.text.trim()
            if (text.isEmpty()) {
                messageTextView.text = text
                return
            }
            val spokenLen = transcript.spokenLength.coerceIn(0, text.length)
            val spannable = SpannableString(text)
            val spokenColor = Color.parseColor("#88FFFFFF")  // gray (semi-transparent white)
            val speakingColor = itemView.context.getThemeColor(
                com.google.android.material.R.attr.colorOnSurface
            )
            if (spokenLen > 0) {
                spannable.setSpan(
                    ForegroundColorSpan(spokenColor), 0, spokenLen,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (spokenLen < text.length) {
                spannable.setSpan(
                    ForegroundColorSpan(speakingColor), spokenLen, text.length,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            messageTextView.text = spannable
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
            transcripts[lastIndex] = oldTranscript.copy(text = newText)
            // Use payload to indicate this is just a text update
            notifyItemChanged(lastIndex, Unit)
        }
    }

    fun updateLastTranscriptHighlight(newText: String, spokenLength: Int) {
        if (transcripts.isNotEmpty()) {
            val lastIndex = transcripts.size - 1
            val oldTranscript = transcripts[lastIndex]
            transcripts[lastIndex] = oldTranscript.copy(text = newText, spokenLength = spokenLength)
            notifyItemChanged(lastIndex, Unit)
        }
    }
}