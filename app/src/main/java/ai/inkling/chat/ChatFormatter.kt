package ai.inkling.chat

object ChatFormatter {

    data class Message(val role: String, val content: String)

    private const val SEP_L = "<|"
    private const val SEP_R = "|>"

    private const val PHI_USER = SEP_L + "user" + SEP_R
    private const val PHI_ASSISTANT = SEP_L + "assistant" + SEP_R

    fun format(chatTemplate: String, messages: List<Message>): String {
        val t = chatTemplate.lowercase()
        val chatml = t.contains(SEP_L + "im_start" + SEP_R)
        val llama3 = t.contains(SEP_L + "start_header_id" + SEP_R)
        val gemma = t.contains("<start_of_turn>")
        val phi3 = t.contains(PHI_USER) && t.contains(SEP_L + "end" + SEP_R)
        return when {
            chatml -> chatml(messages)
            llama3 -> llama3(messages)
            gemma -> gemma(messages)
            phi3 -> phi3(messages)
            else -> plain(messages)
        }
    }

    private fun chatml(messages: List<Message>): String = buildString {
        messages.forEach { m ->
            append(SEP_L)
            append("im_start")
            append(SEP_R)
            append(m.role)
            append("\n")
            append(m.content)
            append(SEP_L)
            append("im_end")
            append(SEP_R)
            append("\n")
        }
        append(SEP_L)
        append("im_start")
        append(SEP_R)
        append("assistant\n")
    }

    private fun llama3(messages: List<Message>): String = buildString {
        append(SEP_L)
        append("begin_of_text")
        append(SEP_R)
        messages.forEach { m ->
            append(SEP_L)
            append("start_header_id")
            append(SEP_R)
            append(m.role)
            append(SEP_L)
            append("end_header_id")
            append(SEP_R)
            append("\n\n")
            append(m.content)
            append(SEP_L)
            append("eot_id")
            append(SEP_R)
        }
        append(SEP_L)
        append("start_header_id")
        append(SEP_R)
        append("assistant")
        append(SEP_L)
        append("end_header_id")
        append(SEP_R)
        append("\n\n")
    }

    private fun gemma(messages: List<Message>): String = buildString {
        append("<bos>")
        messages.forEach { m ->
            append("<start_of_turn>")
            append(if (m.role == "assistant") "model" else m.role)
            append('\n')
            append(m.content)
            append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }

    private fun phi3(messages: List<Message>): String = buildString {
        messages.forEach { m ->
            append(if (m.role == "assistant") PHI_ASSISTANT else PHI_USER)
            append("\n")
            append(m.content)
            append(SEP_L)
            append("end")
            append(SEP_R)
            append("\n")
        }
        append(PHI_ASSISTANT)
        append("\n")
    }

    private fun plain(messages: List<Message>): String = buildString {
        messages.forEach { m ->
            append(m.role)
            append(": ")
            append(m.content)
            append("\n\n")
        }
        append("assistant: ")
    }
}
