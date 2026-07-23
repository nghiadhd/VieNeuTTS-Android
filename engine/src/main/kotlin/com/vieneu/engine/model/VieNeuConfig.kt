package com.vieneu.engine.model

import com.vieneu.engine.tokenizer.MiniJson
import com.vieneu.engine.tokenizer.asObj
import com.vieneu.engine.tokenizer.asNum

/** Mirrors the fields of `onnx_update/config.json` that `OnnxV3LiteEngine` reads. */
data class VieNeuConfig(
    val nVq: Int,
    val hidden: Int,
    val numHiddenLayers: Int, // L — backbone decode_step KV-cache layer count
    val localNumHiddenLayers: Int, // L_loc — acoustic (per-frame) decoder layer count
    val audioPad: Int,
    val textPromptStart: Int,
    val textPromptEnd: Int,
    val speechGenerationStart: Int,
    val speechGenerationEnd: Int,
    val audioRefSlot: Int,
    val defaultStyleTokenId: Int,
    val styleLabels: Map<String, Int>,
    val useSpeakerEmbedding: Boolean,
) {
    fun resolveStyleId(style: String): Int = styleLabels[style] ?: defaultStyleTokenId

    companion object {
        fun fromJson(json: String): VieNeuConfig {
            val root = MiniJson.parse(json).asObj()
            val styleLabels = root["style_labels"]?.asObj()?.mapValues { it.value.asNum() } ?: emptyMap()
            return VieNeuConfig(
                nVq = root.getValue("n_vq").asNum(),
                hidden = root.getValue("hidden_size").asNum(),
                numHiddenLayers = root.getValue("num_hidden_layers").asNum(),
                localNumHiddenLayers = root.getValue("local_num_hidden_layers").asNum(),
                audioPad = root.getValue("audio_pad_token_id").asNum(),
                textPromptStart = root.getValue("text_prompt_start_token_id").asNum(),
                textPromptEnd = root.getValue("text_prompt_end_token_id").asNum(),
                speechGenerationStart = root.getValue("speech_generation_start_token_id").asNum(),
                speechGenerationEnd = root.getValue("speech_generation_end_token_id").asNum(),
                audioRefSlot = root.getValue("audio_ref_slot_token_id").asNum(),
                defaultStyleTokenId = root.getValue("default_style_token_id").asNum(),
                styleLabels = styleLabels,
                useSpeakerEmbedding = root["use_speaker_embedding"] as? Boolean ?: false,
            )
        }
    }
}
