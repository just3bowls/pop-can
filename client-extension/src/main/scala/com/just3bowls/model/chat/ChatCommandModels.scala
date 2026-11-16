package com.just3bowls.model.chat

/** 输入框命令模式下的一行可执行项。 */
final case class ChatCommandItem(
    id: String,
    label: String,
    description: String = "",
    /** 整行 hover 全文：值的含义、点击/回车会发生什么。 */
    title: String = "",
    /** 当前值或多选项中的已选项；渲染时统一显示 ✓。 */
    selected: Boolean = false,
    run: () => Unit
)

/** 输入框上方状态行的一行提示：kind 为 "info"（中性灰）或 "error"（红色）。 */
final case class FooterNotice(text: String, kind: String)
sealed trait ChatCommandStage
object ChatCommandStage {
  case object Closed        extends ChatCommandStage
  case object Commands      extends ChatCommandStage
  case object AtRoot        extends ChatCommandStage
  case object Providers     extends ChatCommandStage
  case object Agents        extends ChatCommandStage
  case object Tabs          extends ChatCommandStage
  case object RememberScope extends ChatCommandStage
}
final case class ChatCommandSpec(
    name: String,
    usage: String,
    description: String
)
object ChatCommandSpec  {
  val all: List[ChatCommandSpec]                   = List(
    ChatCommandSpec("new", "/new", "Start a new chat"),
    ChatCommandSpec("remember", "/remember", "Choose Global or Session, then enter the text"),
    ChatCommandSpec("clear", "/clear", "Clear this chat's messages"),
    ChatCommandSpec("fork", "/fork", "Fork this chat into a new session"),
    ChatCommandSpec("undo", "/undo", "Undo the last question"),
    ChatCommandSpec("redo", "/redo", "Restore the last undo or cleared chat"),
    ChatCommandSpec(
      "provider",
      "/provider [provider] [new|continue] [inline|file] [pin|unpin]",
      "Choose a provider and configure this send"
    ),
    ChatCommandSpec("sessions", "/sessions", "Browse and switch chat sessions"),
    ChatCommandSpec(
      "agent",
      "/agent",
      "Choose ReAct or Plan for this session"
    ),
    ChatCommandSpec("browser-tools", "/browser-tools [on|off]", "Toggle built-in browser tools for this session")
  )
  def filter(query: String): List[ChatCommandSpec] = {
    val q = query.trim.toLowerCase
    if (q.isEmpty) all
    else
      // 只按命令名过滤：描述里出现关键词的其它命令不能抢答。
      all.filter(_.name.startsWith(q))
  }
}
