package com.iris.assistant.agent

import com.iris.assistant.memory.ShortTermMemory

/**
 * The whole app is built around the API-based model (OpenAiIntentModel), but
 * two things needed fixing here:
 *
 *  1) When the API is connected but returns plain text instead of the
 *     expected tool-call JSON (common with smaller models that don't follow
 *     instructions perfectly), the offline rule-based engine is now ALSO
 *     asked whether it recognizes a real command in the same message. If it
 *     does, that tool actually runs instead of the request silently doing
 *     nothing. This runs on every non-tool reply, not just on API failure.
 *  2) When the API is fully unreachable (no key, no internet, provider
 *     error, no quota), the offline engine is the sole source of truth, same
 *     as before.
 */
class HybridIntentModel(
    private val primary: OpenAiIntentModel,
    private val fallback: RuleBasedAIModel
) : IntentModel {

    override suspend fun decide(userText: String, availableTools: Collection<Tool>, memory: ShortTermMemory): Decision {
        return try {
            val apiDecision = primary.decide(userText, availableTools, memory)
            if (apiDecision.toolName != null) {
                // The API confidently picked a tool itself — trust it.
                apiDecision
            } else {
                // API just replied with text (or its JSON didn't parse) —
                // double-check with the offline engine in case it recognizes
                // an actual command the API's reply missed.
                val ruleDecision = fallback.decide(userText, availableTools, memory)
                if (ruleDecision.toolName != null) ruleDecision else apiDecision
            }
        } catch (e: Exception) {
            // API totally unreachable — offline engine is the only option.
            val warning = "⚠️ (بدون اتصال به هوش مصنوعی آنلاین: ${e.message}) "
            val fb = fallback.decide(userText, availableTools, memory)
            if (fb.toolName == null && fb.spokenReply != null) fb.copy(spokenReply = warning + fb.spokenReply) else fb
        }
    }
}
