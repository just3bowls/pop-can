package com.just3bowls.model.provider

/**
 * 投递 prompt 的模式（两个消费者各自独立，这里只放名字与 `normalize`）：
 *
 *   - **插件页**（chat）：`/provider` 行里打的值 —— 只作用于该 provider 的下一次发送；打了 `pin` 才长期固定（`utils/ProviderChatSettings`）。
 *     `inline`（整份模版）；
 *   - **server 转发**：按鉴权命中的那个 API key 的 `promptMode`（`Config → API keys` 里可改，见 `ServerWorkerClient`）。
 *
 * 它管两件事，取决于这一轮投什么：
 *
 *   - **要投全量**（新开一段对话 / 首投）：`inline` = request body 嵌进 `ThinOpenAi` 模版、由 `RequestRewrite` 改写请求体注入；`file` = body 作为
 *     `.txt` 附件上传， 输入框里只打指针句；
 *   - **要投增量**（`thread: continue` 且水位线对得上）：`inline` = 只把消息增量改写进请求体；`file` = 同一份增量装成 `.txt` 附件（增量很大时不必挤输入框、也不赌改写能不能过）。
 *
 * 增量轮（`delta`）只投消息增量本身，正文既不进输入框也不需要附件，所以这个开关对增量轮没有影响 —— 用户在同一条 provider 对话里持续聊下去，不会每轮都传一个文件。
 */
object PromptSettings {
  val Inline = "inline"

  /** 把 request body 作为 `.txt` 附件上传到输入框，输入框里只打 persona + protocol + 指针句。 */
  val File                           = "file"
  def normalize(raw: String): String =
    if (raw == File) File else Inline
}

/** `file` 模式的附件：文件名（含 `.txt`）与整份 request body。 */
final case class PromptAttachment(name: String, content: String)
