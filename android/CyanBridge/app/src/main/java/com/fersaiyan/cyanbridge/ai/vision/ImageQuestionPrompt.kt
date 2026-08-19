package com.fersaiyan.cyanbridge.ai.vision

import java.util.Locale

data class ImageQuestionSettings(
    val appLanguageTag: String,
    val defaultQuestion: String,
    val usesBuiltInDefault: Boolean,
)

enum class ImageQuestionRoute {
    PRO_RELAY,
    LOCAL_GEMMA,
    TASKER_GEMINI,
}

/** A single resolved prompt is deliberately shared by every image-question route. */
data class ResolvedImageQuestionPrompt(
    val text: String,
) {
    fun forRoute(route: ImageQuestionRoute): String = when (route) {
        ImageQuestionRoute.PRO_RELAY,
        ImageQuestionRoute.LOCAL_GEMMA,
        ImageQuestionRoute.TASKER_GEMINI
        -> text
    }
}

object ImageQuestionDefaults {
    fun listeningCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Estou ouvindo."
        "es" -> "Te escucho."
        "de" -> "Ich höre zu."
        "fr" -> "Je vous écoute."
        "it" -> "Ti ascolto."
        "zh" -> "我在听。"
        "ko" -> "듣고 있습니다."
        "ru" -> "Я слушаю."
        else -> "I am listening."
    }

    fun questionCueForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Pergunte."
        "es" -> "Pregunta."
        "de" -> "Frag."
        "fr" -> "Demandez."
        "it" -> "Chiedi."
        "zh" -> "请提问。"
        "ko" -> "질문하세요."
        "ru" -> "Спросите."
        else -> "Ask."
    }

    fun questionForLanguage(languageTag: String): String = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "pt" -> "Dê-me uma descrição concisa da imagem"
        "es" -> "Dame una descripción concisa de la imagen"
        "de" -> "Gib mir eine kurze Beschreibung des Bildes"
        "fr" -> "Donnez-moi une description concise de l'image"
        "it" -> "Dammi una descrizione concisa dell'immagine"
        "zh" -> "请简洁地描述这张图片"
        "ko" -> "이미지를 간단히 설명해 주세요"
        "ru" -> "Дайте мне краткое описание изображения"
        else -> "Give me a concise description of the image"
    }

    fun responseLanguageInstruction(languageTag: String): String =
        "Answer only in ${languageLabel(languageTag)} ($languageTag)."

    private fun languageLabel(languageTag: String): String =
        Locale.forLanguageTag(languageTag)
            .getDisplayLanguage(Locale.ENGLISH)
            .ifBlank { languageTag }
}

object ImageQuestionPromptResolver {
    fun resolve(
        settings: ImageQuestionSettings,
        userQuestion: String?,
        /**
         * Whether to append the response-language line to the question text.
         *
         * Callers that already carry the instruction in their own system prompt pass false.
         * Appending it here as well put "Answer only in English (en)." inside the wearer's own
         * words, and since that text is what gets stored as conversation history, every later
         * turn saw the instruction quoted back as something the wearer had said.
         *
         * Defaults to true so callers with no system prompt of their own keep working.
         */
        includeLanguageInstruction: Boolean = true,
    ): ResolvedImageQuestionPrompt {
        val languageTag = settings.appLanguageTag.ifBlank { "en" }
        val question = userQuestion?.trim().takeUnless { it.isNullOrBlank() }
            ?: settings.defaultQuestion.trim().ifBlank {
                ImageQuestionDefaults.questionForLanguage(languageTag)
            }

        return ResolvedImageQuestionPrompt(
            text = if (includeLanguageInstruction) {
                "$question\n\n${ImageQuestionDefaults.responseLanguageInstruction(languageTag)}"
            } else {
                question
            },
        )
    }
}
