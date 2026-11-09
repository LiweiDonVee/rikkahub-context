package me.rerere.ai.context

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json

data class ReasoningDelivery(val omissions: List<ContextExclusion>, val notices: List<String>)

/** Inspect the built native fields, rather than equating stored reasoning with transmitted reasoning. */
fun inspectReasoningDelivery(messages: List<UIMessage>, protocol: String, body: String): ReasoningDelivery {
    val visible = mutableListOf<String>()
    fun text(value: JsonElement?) {
        when (value) {
            is JsonPrimitive -> if (value.isString) visible += value.content
            is JsonArray -> value.forEach { text(it) }
            is JsonObject -> value.forEach { (key, child) -> if (key in setOf("text", "thinking", "summary", "content")) text(child) }
            else -> Unit
        }
    }
    fun walk(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                text(value["reasoning_content"])
                text(value["reasoning_details"])
                (value["reasoning"] as? JsonPrimitive)?.let { text(it) }
                val type = (value["type"] as? JsonPrimitive)?.content
                if (type in setOf("reasoning", "thinking", "thought")) {
                    text(value["text"]); text(value["thinking"]); text(value["summary"]); text(value["content"])
                }
                if ((value["thought"] as? JsonPrimitive)?.content == "true") text(value["text"])
                value.values.forEach(::walk)
            }
            is JsonArray -> value.forEach(::walk)
            else -> Unit
        }
    }
    walk(json.parseToJsonElement(body))
    val omissions = messages.flatMap { message -> message.parts.mapIndexedNotNull { index, part ->
        if (part is UIMessagePart.Reasoning && part.reasoning.isNotBlank() && visible.none { part.reasoning in it })
            ContextExclusion(message.id.toString(), index, "Provider 未在原生思考文本字段中携带此段可见内容；如需作为文字发送，可在编辑器中转为正文。")
        else null
    } }
    val notices = omissions.map { it.reason }.distinct().toMutableList()
    if (protocol in setOf("anthropic_messages", "openai_responses") && messages.any { message -> message.parts.any {
        it is UIMessagePart.Reasoning && it.reasoning.isNotBlank() && !hasOpaqueState(it)
    } }) notices += "包含无签名的手工思考。预览展示其协议表示，能否接受由接口决定；也可显式转为正文。"
    return ReasoningDelivery(omissions, notices)
}
