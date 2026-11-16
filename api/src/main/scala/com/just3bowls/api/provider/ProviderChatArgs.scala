package com.just3bowls.api.provider

/**
 * `/provider` 输入里的"设置参数"解析（纯逻辑，JVM + Scala.js 通用，`server/test` 有金值单测）。
 *
 * 约定：**第一个 token 是 provider 过滤词**，它后面的 token 只认这几类（大小写不敏感、顺序无关）：
 *
 *   - `new` / `continue` → 改 thread（新开一段 provider 原生对话 / 接着现在这段聊；`delta` 是 `continue` 的历史写法，继续接受）；
 *   - `inline` / `file` → 改 prompt（本次投递把正文改写进请求体 / 正文作为附件上传）；
 *   - `pin` / `unpin` → 把当前生效值固定到这家 provider 上（跨会话生效）/ 解除固定。
 *
 * 出现任何别的 token 或 `pin` 与 `unpin` 同时出现 → **整段当过滤词**（= 没有参数时的老行为）—— 写错时只会"匹配不到 provider"，不会静默改设置或误切。
 */
object ProviderChatArgs {

  final case class Parsed(
      /** provider 过滤词（第一个 token；调用方按它过滤 provider 列表）。 */
      query: String,
      /** 这次要写的 thread 值（`new` / `continue`）；没写就是 None。 */
      thread: Option[String],
      /** 这次要写的 prompt 值（`inline` / `file`）；没写就是 None。 */
      prompt: Option[String],
      /** 把当前生效值固定到这家 provider 上。 */
      pin: Boolean,
      /** 解除这家的固定值。 */
      unpin: Boolean
  )

  object Parsed {
    val Empty: Parsed = Parsed("", None, None, pin = false, unpin = false)
  }

  val ThreadNew                  = "new"
  val ThreadContinue             = "continue"
  val ThreadValues: List[String] = List(ThreadNew, ThreadContinue)

  /** 历史写法 → 现在认的值。 */
  val ThreadAliases: Map[String, String] = Map("delta" -> ThreadContinue)

  val PromptInline               = "inline"
  val PromptFile                 = "file"
  val PromptValues: List[String] = List(PromptInline, PromptFile)

  val PinFlag                  = "pin"
  val UnpinFlag                = "unpin"
  val FlagValues: List[String] = List(PinFlag, UnpinFlag)

  def parse(text: String): Parsed = {
    val trimmed = Option(text).getOrElse("").trim
    if (trimmed.isEmpty) Parsed.Empty
    else {
      val tokens = trimmed.split("\\s+").toList
      val query  = tokens.head
      val args   = tokens.tail.map(_.toLowerCase).filter(_.nonEmpty)
      val known  = args.forall(value =>
        ThreadValues.contains(value) || ThreadAliases.contains(value) || PromptValues.contains(value) ||
          FlagValues.contains(value)
      )
      val pin    = args.contains(PinFlag)
      val unpin  = args.contains(UnpinFlag)
      if (!known || (pin && unpin))
        Parsed(trimmed, None, None, pin = false, unpin = false)
      else
        Parsed(
          query = query,
          thread = args.collectFirst {
            case value if ThreadValues.contains(value)  => value
            case value if ThreadAliases.contains(value) => ThreadAliases(value)
          },
          prompt = args.find(PromptValues.contains),
          pin = pin,
          unpin = unpin
        )
    }
  }
}
