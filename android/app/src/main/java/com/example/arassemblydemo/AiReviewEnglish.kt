package com.example.arassemblydemo

import org.json.JSONObject

/** Display language belongs to a review request, never to the stable tutorial/marker IDs. */
internal class AiReviewEnglish(context: JSONObject?) {
    val enabled = context?.optString("uiLanguage")?.substringBefore('-') == "en"
    fun say(chinese: String, english: String) = if (enabled) english else chinese
    fun cue(source: String, fallback: String) = if (enabled) FinalGuideEnglish.text(source) ?: fallback else source

    companion object {
        fun error(code: String) = when (code) {
            "NOT_CONFIGURED", "AUTH_FAILED" -> "Check your DeepSeek API key in AI settings."
            "BALANCE" -> "Your DeepSeek account has insufficient credit."
            "RATE_LIMIT", "PROVIDER_UNAVAILABLE" -> "DeepSeek is busy. Try again in a moment."
            "IMAGE_TOO_LARGE" -> "This photo is too large. Take another one."
            "TIMEOUT" -> "The review timed out. Try again or return to assembly."
            "UNREACHABLE", "REDIRECT_REJECTED" -> "The phone could not reach DeepSeek. Check your connection and try again."
            "INVALID_REQUEST" -> "The photos or step details are incomplete. Retake the photo."
            "WRONG_LANGUAGE" -> "The reply came back in another language. Retry this photo for English guidance."
            "CANCELLED" -> "Review canceled."
            "CONFIG_SAVE_FAILED" -> "AI settings could not be saved. Please try again."
            else -> "The photo review could not finish. Try again or return to assembly."
        }
    }
}
