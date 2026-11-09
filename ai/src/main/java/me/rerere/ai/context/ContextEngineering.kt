package me.rerere.ai.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import kotlin.uuid.Uuid

/** Local editing metadata. Never serialized as a provider message. Old histories need no migration. */
@Serializable
data class ContextMessageState(
    val originalParts: List<UIMessagePart>? = null,
    val excluded: Boolean = false,
    val excludedParts: Set<Int> = emptySet(),
    val sources: List<ContextSource> = emptyList(),
    val toolRuns: List<ToolRunRecord> = emptyList(),
    val invalidOpaqueParts: Set<Int> = emptySet(),
    val manualToolExecution: Boolean = false,
    val requestSelection: ContextSelection? = null,
)

@Serializable
data class ContextSource(
    val conversationId: String,
    val messageId: String,
    val partIndex: Int? = null,
    val description: String = "",
    val skillName: String? = null,
)

@Serializable
data class ToolRunRecord(
    val id: String = Uuid.random().toString(),
    val tool: UIMessagePart.Tool,
    val status: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class ContextSelection(
    val includeReasoning: Boolean = true,
    val includeSkills: Boolean = true,
    val includeToolCalls: Boolean = true,
    val includeToolResults: Boolean = true,
    val excludedMessages: Set<String> = emptySet(),
    val messageOrder: List<String> = emptyList(),
)

@Serializable
data class ContextExclusion(val messageId: String, val partIndex: Int? = null, val reason: String)

data class CompiledContext(val messages: List<UIMessage>, val exclusions: List<ContextExclusion>)

/** Select a request context without changing the stored history or executing anything. */
fun compileContext(history: List<UIMessage>, selection: ContextSelection): CompiledContext {
    val exclusions = mutableListOf<ContextExclusion>()
    val byId = history.associateBy { it.id.toString() }
    val orderedIds = selection.messageOrder.toSet()
    require(selection.messageOrder.distinct().size == selection.messageOrder.size) { "Duplicate message in order" }
    require(selection.messageOrder.all { it in byId }) { "Message order refers to missing history" }
    val ordered = if (selection.messageOrder.isEmpty()) history else {
        selection.messageOrder.map { byId.getValue(it) } + history.filter { it.id.toString() !in orderedIds }
    }
    val selected = ordered.mapNotNull { message ->
        val id = message.id.toString()
        if (message.contextState.excluded || id in selection.excludedMessages) {
            exclusions += ContextExclusion(id, reason = "Excluded by user")
            return@mapNotNull null
        }
        val parts = message.parts.mapIndexedNotNull { index, part ->
            val reason = when {
                index in message.contextState.excludedParts -> "Part excluded by user"
                part is UIMessagePart.Reasoning && !selection.includeReasoning -> "History thinking excluded"
                part is UIMessagePart.Tool && part.toolName == "use_skill" && !selection.includeSkills -> "Skill excluded"
                part is UIMessagePart.Tool && (!selection.includeToolCalls || !selection.includeToolResults) ->
                    "Tool call and result excluded together to preserve protocol pairing"
                part is UIMessagePart.Tool && !part.isExecuted -> "Tool result missing; historical tool is never replayed"
                else -> null
            }
            if (reason != null) {
                exclusions += ContextExclusion(id, index, reason)
                null
            } else {
                require(index !in message.contextState.invalidOpaqueParts) {
                    "Edited signed/encrypted provider part $index cannot be replayed. Exclude it or convert it to text."
                }
                if (part is UIMessagePart.Tool) {
                    require(runCatching { json.parseToJsonElement(part.input) is JsonObject }.getOrDefault(false)) {
                        "Historical tool ${part.toolCallId} has invalid arguments. Edit or exclude it before sending."
                    }
                }
                part
            }
        }
        if (parts.isEmpty()) {
            exclusions += ContextExclusion(id, reason = "消息没有选中的内容，未进入请求")
            null
        } else message.copy(parts = parts)
    }
    return CompiledContext(selected, exclusions)
}

fun hasOpaqueState(part: UIMessagePart): Boolean = part.metadata?.keys?.any {
    it in setOf("signature", "encrypted_content", "openrouter_reasoning_details", "thoughtSignature", "interactions_signature")
} == true || part is UIMessagePart.ServerTool

/** Draft identity follows the source part through insertions, deletions and moves. */
data class ContextPartDraft(val part: UIMessagePart, val originalIndex: Int? = null, val key: String = Uuid.random().toString())

fun UIMessage.contextDraft() = parts.mapIndexed { index, part -> ContextPartDraft(part, index) }

const val USER_AUTHORED_REASONING = "context_user_authored"

fun UIMessagePart.Reasoning.withUserReasoning(text: String) = copy(
    reasoning = text,
    metadata = JsonObject(metadata.orEmpty() + (USER_AUTHORED_REASONING to JsonPrimitive(true))),
)

fun UIMessage.saveContextDraft(draft: List<ContextPartDraft>) = saveContextEdit(draft.map { it.part }, draft.map { it.originalIndex })

fun UIMessage.saveContextEdit(parts: List<UIMessagePart>, originalIndices: List<Int?> = parts.indices.map { it.takeIf { index -> index in this.parts.indices } }): UIMessage {
    require(originalIndices.size == parts.size)
    require(originalIndices.filterNotNull().distinct().size == originalIndices.filterNotNull().size) { "A source part cannot be used twice" }
    require(originalIndices.filterNotNull().all { it in this.parts.indices }) { "Source part no longer exists" }
    return copy(
    parts = parts,
    contextState = contextState.copy(
        originalParts = contextState.originalParts ?: this.parts,
        excludedParts = parts.indices.filterTo(mutableSetOf()) { originalIndices[it] in contextState.excludedParts },
        invalidOpaqueParts = parts.indices.filterTo(mutableSetOf()) { index ->
            val part = parts[index]
            if (part is UIMessagePart.Text && !hasOpaqueState(part)) false else {
                val sourceIndex = originalIndices[index]
                val previous = sourceIndex?.let { this.parts[it] }
                if (part == previous) sourceIndex in contextState.invalidOpaqueParts else {
                    if (part is UIMessagePart.Tool && previous is UIMessagePart.Tool &&
                        part.toolName == previous.toolName && part.input == previous.input && part.metadata == previous.metadata
                    ) sourceIndex in contextState.invalidOpaqueParts else previous?.let(::hasOpaqueState) == true || hasOpaqueState(part)
                }
            }
        },
    ),
)
}

fun UIMessage.restoreContextOriginal(): UIMessage = contextState.originalParts?.let {
    copy(parts = it, contextState = contextState.copy(originalParts = null, excludedParts = emptySet(), invalidOpaqueParts = emptySet()))
} ?: this

/** Regenerate before a part, or continue after its entire consecutive parallel call group. */
fun contextAtBreakpoint(history: List<UIMessage>, messageId: Uuid, partIndex: Int?, continueAfter: Boolean, selection: ContextSelection = ContextSelection()): List<UIMessage> {
    val index = history.indexOfFirst { it.id == messageId }
    require(index >= 0) { "Breakpoint message no longer exists" }
    val message = history[index]
    if (partIndex == null) return history.take(if (continueAfter) index + 1 else index)
    require(partIndex in message.parts.indices) { "Breakpoint part no longer exists" }
    var end = if (continueAfter) partIndex + 1 else partIndex
    if (continueAfter && message.parts[partIndex] is UIMessagePart.Tool) {
        while (end < message.parts.size && message.parts[end] is UIMessagePart.Tool) end++
        val start = (partIndex downTo 0).takeWhile { message.parts[it] is UIMessagePart.Tool }.last()
        require((start until end).all { toolIndex ->
            val tool = message.parts[toolIndex] as UIMessagePart.Tool
            tool.isExecuted || toolIndex in message.contextState.excludedParts || message.contextState.excluded ||
                message.id.toString() in selection.excludedMessages || !selection.includeToolCalls || !selection.includeToolResults ||
                (!selection.includeSkills && tool.toolName == "use_skill")
        }) {
            "This parallel tool group has missing results. Run or exclude the missing calls before continuing."
        }
    }
    return history.take(index) + if (end == 0) emptyList() else listOf(message.copy(parts = message.parts.take(end)))
}

/** Validate strict JSON and current authorization. Unlike inputAsJson(), invalid JSON never becomes {}. */
fun validateToolEdit(part: UIMessagePart.Tool, authorized: List<Tool>): JsonObject {
    val tool = authorized.singleOrNull { it.name == part.toolName }
        ?: error("Tool '${part.toolName}' is not currently authorized")
    val args = requireNotNull(json.parseToJsonElement(part.input) as? JsonObject) { "Tool arguments must be a JSON object" }
    (tool.parameters() as? InputSchema.Obj)?.let { schema ->
        schema.required.orEmpty().forEach { require(it in args) { "Missing required argument: $it" } }
        schema.properties.forEach { (name, value) ->
            val supplied = args[name] ?: return@forEach
            val property = value as? JsonObject ?: return@forEach
            val type = (property["type"] as? JsonPrimitive)?.contentOrNull
            val valid = when (type) {
                "string" -> supplied is JsonPrimitive && supplied.isString
                "object" -> supplied is JsonObject
                "array" -> supplied is kotlinx.serialization.json.JsonArray
                "boolean" -> supplied is JsonPrimitive && !supplied.isString && supplied.content in setOf("true", "false")
                "number", "integer" -> supplied is JsonPrimitive && !supplied.isString &&
                    supplied.content.toDoubleOrNull()?.let { type != "integer" || it % 1.0 == 0.0 } == true
                else -> true
            }
            require(valid) { "Argument '$name' must have type $type" }
        }
    }
    return args
}

fun editedTool(part: UIMessagePart.Tool, name: String, input: String, output: List<UIMessagePart>): UIMessagePart.Tool =
    part.copy(toolName = name, input = input, output = output, approvalState = ToolApprovalState.Pending)

/** All visible kinds are retained in explicit text conversions, including protocol-only metadata. */
fun UIMessagePart.contextText(): String = when (this) {
    is UIMessagePart.Text -> text
    is UIMessagePart.Reasoning -> "[Thinking]\n$reasoning" + (metadata?.let { "\n[Provider metadata] $it" } ?: "")
    is UIMessagePart.Tool -> "[Tool call $toolName / $toolCallId]\n$input\n[Tool result]\n" +
        output.joinToString("\n") { it.contextText() } + (metadata?.let { "\n[Provider metadata] $it" } ?: "")
    else -> json.encodeToString(UIMessagePart.serializer(), this)
}
