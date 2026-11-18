package com.just3bowls.model.chat

import com.just3bowls.facade.JsBridge

import com.just3bowls.api.chat.BrowserTabRef
import com.just3bowls.facade.{ChromeAPI, JsonValue}
import com.just3bowls.model.provider.SessionFreshChats
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Dictionary

/** UI Chat 的本地会话树存储；字段与 Server ChatStore 对齐。 */
object ChatStore {

  /**
   * 存储形状：**一条消息 / 一个会话各占一个 key**（消息 `thChatM:<id>`、会话 `thChatS:<id>`）。
   *
   * 以前整表存 `thLocalChatMessages` / `thLocalChatSessions`，而写这张表的有两个 JS 上下文（插件页 与 SW）：`get` 整表 → 改 → `set` 回去，
   * 两边并发时后写的会把先写的整表覆盖掉（丢消息 / 丢回答）。改成一条记录一个 key 之后 append 就是一次独立 `set`，不再有读-改-写；消息 id 全局唯一，所以 key 不需要带 session 前缀。
   *
   * 旧的整表 key 已废弃，不再读也不再写（残留无害）。
   */
  // 前缀是公开的：外部（如 SW 的 storage.onChanged）要按前缀判断"会话/消息有没有变"。
  val SessionKeyPrefix: String = "thChatS:"
  val FolderKeyPrefix: String  = "thChatF:"
  val MessageKeyPrefix: String = "thChatM:"
  private val UndoStacksKey    = "thChatUndoStacks"
  val UndoKind: String         = "undo"
  val ClearKind: String        = "clear"

  final case class SessionTree(
      sessions: List[ChatSession],
      folders: List[SessionFolder],
      repairedSessionFolders: Int,
      repairedFolderParents: Int,
      changedSessions: List[ChatSession] = Nil,
      changedFolders: List[SessionFolder] = Nil
  )

  /** 一个可恢复的删除步骤；普通 undo 与 clear 共用同一条 redo 栈。 */
  final case class UndoStep(
      messages: List[ChatMessage],
      kind: String = UndoKind,
      /** `/clear` 时被替换掉的 Session 标题；普通 undo 与旧版 step 为空。 */
      sessionTitle: Option[String] = None
  )

  /** 会话列表：按创建时间倒序（与旧整表"新建时前插"的展示顺序一致）。 */
  def listSessions(): Future[List[ChatSession]]                                                  =
    readValues(SessionKeyPrefix).map(_.map(value => parseSession(JsBridge.asObject(value))).sortBy(-_.createdAt))
  def getSession(id: String): Future[Option[ChatSession]]                                        =
    if (id.isEmpty) Future.successful(None)
    else
      BrowserAPIStorage.get(sessionKey(id)).map(_.map(value => parseSession(JsBridge.asObject(value))))
  def createSession(title: String, provider: String, folderId: String = ""): Future[ChatSession] = {
    val now     = js.Date.now()
    val id      = nextId()
    val session = ChatSession(id, title, "default", provider, now, now, folderId = folderId)
    saveSession(session).map(_ => session)
  }
  def listSessionFolders(): Future[List[SessionFolder]]                                          =
    readValues(FolderKeyPrefix)
      .map(_.map(value => parseSessionFolder(JsBridge.asObject(value))).sortBy(folder =>
        (folder.name, folder.id)
      ))

  /** Load the hierarchy and repair references that point at deleted/missing parents. */
  def loadSessionTree(): Future[SessionTree] =
    for {
      sessions <- listSessions()
      folders  <- listSessionFolders()
      tree = repairSessionTree(sessions, folders)
      _ <- persistSessionTreeRepair(tree)
    } yield tree

  private[chat] def repairSessionTree(
      sessions: List[ChatSession],
      folders: List[SessionFolder]
  ): SessionTree = {
    val folderIds = folders.map(_.id).toSet
    val rootFixed = folders.map { folder =>
      if (folder.parentId.nonEmpty && !folderIds.contains(folder.parentId))
        folder.copy(parentId = "")
      else folder
    }

    // A cycle is also unreachable from the root. Promote every unreachable folder to root.
    val childrenByParent              = rootFixed.groupBy(_.parentId).view.mapValues(_.map(_.id)).toMap
    val reachable                     = scala.collection.mutable.Set.empty[String]
    def visit(parentId: String): Unit =
      childrenByParent.getOrElse(parentId, Nil).foreach { id =>
        if (!reachable.contains(id)) {
          reachable += id
          visit(id)
        }
      }
    visit("")
    val normalizedFolders             = rootFixed.map(folder =>
      if (reachable.contains(folder.id)) folder else folder.copy(parentId = "")
    )
    val validFolderIds                = normalizedFolders.map(_.id).toSet
    val normalizedSessions            = sessions.map(session =>
      if (session.folderId.nonEmpty && !validFolderIds.contains(session.folderId))
        session.copy(folderId = "")
      else session
    )

    SessionTree(
      sessions = normalizedSessions,
      folders = normalizedFolders,
      repairedSessionFolders = sessions.zip(normalizedSessions).count { case (before, after) =>
        before.folderId != after.folderId
      },
      repairedFolderParents = folders.zip(normalizedFolders).count { case (before, after) =>
        before.parentId != after.parentId
      },
      changedSessions = sessions.zip(normalizedSessions).collect {
        case (before, after) if before.folderId != after.folderId => after
      },
      changedFolders = folders.zip(normalizedFolders).collect {
        case (before, after) if before.parentId != after.parentId => after
      }
    )
  }

  private def persistSessionTreeRepair(tree: SessionTree): Future[Unit]          = {
    val writes = js.Dictionary.empty[js.Any]
    tree.changedSessions.foreach(session =>
      writes.update(sessionKey(session.id), sessionToJs(session))
    )
    tree.changedFolders.foreach(folder =>
      writes.update(folderKey(folder.id), sessionFolderToJs(folder))
    )
    if (writes.isEmpty) Future.successful(())
    else BrowserAPIStorage.setAll(writes)
  }
  def createSessionFolder(name: String, parentId: String): Future[SessionFolder] = {
    val normalized = normalizeFolderName(name)
    if (normalized.isEmpty) Future.failed(new RuntimeException("Folder name cannot be empty"))
    else
      listSessionFolders().flatMap { folders =>
        if (parentId.nonEmpty && !folders.exists(_.id == parentId))
          Future.failed(new RuntimeException("Parent folder no longer exists"))
        else {
          val now    = js.Date.now()
          val folder = SessionFolder(nextId(), normalized, parentId, now, now)
          saveSessionFolder(folder).map(_ => folder)
        }
      }
  }
  def renameSessionFolder(id: String, name: String): Future[Unit]                = {
    val normalized = normalizeFolderName(name)
    if (id.isEmpty) Future.failed(new RuntimeException("Folder no longer exists"))
    else if (normalized.isEmpty) Future.failed(new RuntimeException("Folder name cannot be empty"))
    else
      listSessionFolders().flatMap { folders =>
        folders.find(_.id == id) match {
          case None      => Future.failed(new RuntimeException("Folder no longer exists"))
          case Some(old) =>
            saveSessionFolder(old.copy(name = normalized, updatedAt = js.Date.now()))
        }
      }
  }
  def deleteSessionFolder(id: String): Future[Unit]                              =
    if (id.isEmpty) Future.failed(new RuntimeException("Unfiled is not a folder"))
    else
      for {
        folders  <- listSessionFolders()
        sessions <- listSessions()
        _        <-
          if (folders.exists(_.parentId == id))
            Future.failed(new RuntimeException("Folder still contains child folders"))
          else if (sessions.exists(_.folderId == id))
            Future.failed(new RuntimeException("Folder still contains sessions"))
          else if (!folders.exists(_.id == id))
            Future.failed(new RuntimeException("Folder no longer exists"))
          else BrowserAPIStorage.remove(List(folderKey(id)))
      } yield ()
  def moveSessionToFolder(sessionId: String, folderId: String): Future[Unit]     =
    if (sessionId.isEmpty) Future.failed(new RuntimeException("Open a session before moving it"))
    else
      for {
        folders <- listSessionFolders()
        session <- getSession(sessionId)
        _       <-
          if (folderId.nonEmpty && !folders.exists(_.id == folderId))
            Future.failed(new RuntimeException("Target folder no longer exists"))
          else
            session match {
              case None           => Future.failed(new RuntimeException("Session no longer exists"))
              case Some(existing) => saveSession(existing.copy(folderId = folderId))
            }
      } yield ()
  def moveSessionFolder(folderId: String, parentId: String): Future[Unit]        =
    if (folderId.isEmpty) Future.failed(new RuntimeException("Folder no longer exists"))
    else
      listSessionFolders().flatMap { folders =>
        folders.find(_.id == folderId) match {
          case None      => Future.failed(new RuntimeException("Folder no longer exists"))
          case Some(old) =>
            if (parentId == folderId)
              Future.failed(new RuntimeException("A folder cannot contain itself"))
            else if (parentId.nonEmpty && !folders.exists(_.id == parentId))
              Future.failed(new RuntimeException("Target folder no longer exists"))
            else {
              val byId = folders.map(folder => folder.id -> folder).toMap
              def insideTarget(currentId: String, seen: Set[String]): Boolean =
                if (currentId.isEmpty || seen.contains(currentId)) false
                else
                  byId.get(currentId) match {
                    case Some(folder) if folder.id == folderId => true
                    case Some(folder)                          => insideTarget(folder.parentId, seen + currentId)
                    case None                                  => false
                  }
              if (parentId.nonEmpty && insideTarget(parentId, Set.empty))
                Future.failed(new RuntimeException("A folder cannot be moved into its own descendant"))
              else if (old.parentId == parentId) Future.successful(())
              else saveSessionFolder(old.copy(parentId = parentId, updatedAt = js.Date.now()))
            }
        }
      }
  def sessionFolderFromStorageValue(value: js.Any): Option[SessionFolder]        =
    if (value == null || js.isUndefined(value)) None
    else
      try Some(parseSessionFolder(JsBridge.asObject(value)))
      catch { case _: Throwable => None }
  private def normalizeFolderName(name: String): String                          =
    Option(name).getOrElse("").trim.take(80)

  /** 从 `storage.onChanged` 的原始 Session 值恢复摘要；损坏或已删除的值返回 `None`。 */
  def sessionFromStorageValue(value: js.Any): Option[ChatSession] =
    if (value == null || js.isUndefined(value)) None
    else
      try Some(parseSession(JsBridge.asObject(value)))
      catch { case _: Throwable => None }
  def deleteSession(id: String): Future[Unit]                     =
    for {
      messages <- listMessages(id)
      _        <- BrowserAPIStorage.remove(List(sessionKey(id)))
      _        <- removeMessages(messages.map(_.id))
      _        <- clearUndoStack(id)
      // 会话没了，它在各 provider 上的上下文覆盖也一起清掉。
      // 「这个 provider 下次开新对话」的待办同理。
      _        <- SessionFreshChats.removeSession(id)
      _        <- SessionSettingsStore.clear(id)
    } yield ()

  /** 撤销栈：会话 id -> 若干删除步骤，最近一次在最后。 */
  def loadUndoStack(sessionId: String): Future[List[UndoStep]] =
    loadUndoStacks().map(_.getOrElse(sessionId, Nil))
  def pushUndoStep(
      sessionId: String,
      messages: List[ChatMessage],
      kind: String = UndoKind,
      sessionTitle: Option[String] = None
  ): Future[Unit] =
    if (sessionId.isEmpty || (messages.isEmpty && sessionTitle.isEmpty))
      Future.successful(())
    else
      loadUndoStacks().flatMap { stacks =>
        val step = UndoStep(
          messages,
          if (kind == ClearKind) ClearKind else UndoKind,
          if (kind == ClearKind) sessionTitle else None
        )
        saveUndoStacks(stacks.updated(sessionId, stacks.getOrElse(sessionId, Nil) :+ step))
      }

  /** 弹出最近一步删除；没有可重做内容时返回 None。 */
  def popUndoStep(sessionId: String): Future[Option[UndoStep]]   =
    loadUndoStacks().flatMap { stacks =>
      stacks.getOrElse(sessionId, Nil).lastOption match {
        case Some(step) =>
          val remaining = stacks.getOrElse(sessionId, Nil).dropRight(1)
          val next      = if (remaining.isEmpty) stacks - sessionId else stacks.updated(sessionId, remaining)
          saveUndoStacks(next).map(_ => Some(step))
        case None       =>
          Future.successful(None)
      }
    }
  def clearUndoStack(sessionId: String): Future[Unit]            =
    if (sessionId.isEmpty) Future.successful(())
    else loadUndoStacks().flatMap(stacks => saveUndoStacks(stacks - sessionId))
  def listMessages(sessionId: String): Future[List[ChatMessage]] =
    listAllMessages().map(_.filter(_.sessionId == sessionId).sortBy(_.createdAt))

  /** append / 覆盖：同 id 直接覆盖那一个 key —— "一条消息只有一个 id"在存储层也成立。 */
  def addMessage(message: ChatMessage): Future[Unit] =
    saveMessages(List(message))

  /** 批量写入：一次 `storage.set` 带上所有 key。 */
  def addMessages(newMessages: List[ChatMessage]): Future[Unit] =
    saveMessages(newMessages)

  /** 追加或覆盖一条 assistant Attempt，并同步 active 的顶层镜像字段。 */
  def saveAttempt(baseMessage: ChatMessage, attempt: ChatAttempt, makeActive: Boolean = true): Future[ChatMessage] = {
    val current  = ChatMessage.withAttempts(baseMessage)
    val attempts = current.attempts.filterNot(_.id == attempt.id) :+ attempt
    val activeId = if (makeActive) attempt.id else current.activeAttemptIdOrLegacy
    val active   = attempts.find(_.id == activeId).getOrElse(attempt)
    val updated  = ChatRuns.mirror(current.copy(attempts = attempts), active)
    addMessage(updated).map(_ => updated)
  }

  /** Persist the user's selected A/R view without changing the answer itself. */
  def setActiveAttempt(messageId: String, attemptId: String): Future[Option[ChatMessage]] =
    getMessage(messageId).flatMap {
      case Some(message) =>
        val normalized = ChatMessage.withAttempts(message)
        if (!normalized.attempts.exists(_.id == attemptId)) Future.successful(Some(normalized))
        else {
          val updated = ChatRuns.activate(normalized, attemptId)
          addMessage(updated).map(_ => Some(updated))
        }
      case None          => Future.successful(None)
    }

  /** 网页原生会话：来源标记写 `web`（只作展示，插件页也能继续聊）。 */
  def createWebSession(title: String, provider: String): Future[ChatSession] =
    createSession(title, provider).flatMap { session =>
      updateSession(session.copy(source = "web"))
    }

  /** 新会话的占位标题：首条用户消息到位后会被它顶掉（插件页与胶囊共用同一套命名）。 */
  val NewSessionTitle = "Untitle"

  /** 这条标题是不是"还没被命名"的占位。 */
  def isPlaceholderTitle(title: String): Boolean = {
    val trimmed = title.trim
    trimmed.isEmpty ||
    trimmed == NewSessionTitle
  }

  /**
   * 手动重命名：**不动 `updatedAt`** —— 列表按 `updatedAt` 排序，改名不该让会话跳到最前。
   *
   * 空标题由调用方（UI）按"取消"处理；这里再兜一层（空则不改）。
   */
  def renameSession(id: String, title: String): Future[Unit] = {
    val next = title.trim.take(120)
    if (id.isEmpty || next.isEmpty) Future.successful(())
    else
      setSessionTitle(id, next)
  }

  /** 内部标题写入：允许设置 `Untitle` 或空串，仍不改变 Session 排序。 */
  def setSessionTitle(id: String, title: String): Future[Unit]         =
    if (id.isEmpty) Future.successful(())
    else
      listSessions().flatMap { sessions =>
        sessions.find(_.id == id) match {
          case None          => Future.successful(())
          case Some(session) => saveSession(session.copy(title = Option(title).getOrElse("").trim.take(120)))
        }
      }
  private def updateSession(session: ChatSession): Future[ChatSession] = {
    val now  = js.Date.now()
    val next = session.copy(updatedAt = now)
    saveSession(next).map(_ => next)
  }

  /** session 当前路径上的消息（沿 parentId 从最新的叶子回溯），网页原生模式用它算增量。 */
  def sessionPathMessages(sessionId: String): Future[List[ChatMessage]] =
    listMessages(sessionId).map { messages =>
      val byId = messages.map(message => message.id -> message).toMap
      tipOf(messages) match {
        case None      => Nil
        case Some(tip) =>
          def loop(message: ChatMessage, acc: List[ChatMessage]): List[ChatMessage] =
            message.parentId.flatMap(byId.get) match {
              case Some(parent) => loop(parent, message :: acc)
              case None         => message :: acc
            }
          loop(tip, Nil)
      }
    }

  def tipMessageId(sessionId: String): Future[Option[String]] =
    sessionPathMessages(sessionId).map(_.lastOption.map(_.id))

  /**
   * 会话树的树梢：没有任何消息以它为 parent 的那批里，`(createdAt, id)` 最大的那条。
   *
   * 这就是"这段对话现在停在哪"——插件页选叶子、网页原生推水位线都按它算；`id` 兜底只是为了让并列时结果确定，别让同一份数据每次排出不同分支。
   */
  def tipOf(messages: List[ChatMessage]): Option[ChatMessage] = {
    val childIds = messages.flatMap(_.parentId).toSet
    messages
      .filterNot(message => childIds.contains(message.id))
      .sortBy(message => (message.createdAt, message.id))
      .lastOption
  }

  /**
   * 子消息的 createdAt：严格大于父消息。
   *
   * 同毫秒并列时，任何"按 createdAt 取最后一条"的排序都可能把父消息排到子消息后面（网页原生一轮问答里 user / assistant 就是这样并列的）， 于是插件的渲染路径会走到提问就停、回答看不见。
   */
  private[chat] def childCreatedAt(parentAt: Option[Double]): Double =
    Math.max(js.Date.now(), parentAt.map(_ + 1).getOrElse(0.0))

  /**
   * 网页原生模式记一轮问答。
   *
   * `treatAsRegenerate` 为真时（本轮用户文本与上一轮相同）只替换路径上最后一条 assistant，不新增消息。
   */
  def appendWebTurn(
      sessionId: String,
      provider: String,
      origin: String,
      userText: String,
      assistantText: String,
      treatAsRegenerate: Boolean,
      /**
       * marker 里的 `reasoning_content`（模型的思考）。
       *
       * 网页原生这条链路没有 steps 面板，reason 只能放在 `thinking` 上 —— 渲染侧在没有 steps 时会回退去读它（折叠的 "Thinking" 块）。
       */
      thinking: String = "",
      /**
       * 这一轮是不是**失败**（marker 解析失败 / 交接失败）：失败时 assistant 落 `status = error`。
       *
       * 以前失败也写成 success（正文是模型原文），于是气泡上看不出失败、也没有 Retry 入口；现在由自动重试接手替换。
       */
      failed: Boolean = false,
      browserTools: Boolean = false
  ): Future[Unit] =
    if (sessionId.isEmpty) Future.successful(())
    else
      sessionPathMessages(sessionId).flatMap { path =>
        val now = js.Date.now()
        val tip = path.lastOption
        if (treatAsRegenerate) {
          path.reverse.find(_.role == "assistant") match {
            case Some(previous) =>
              // 重新生成是一条完整的新结果：正文、reason、状态与过程字段必须一起替换，不能留下上一次失败态。
              updateMessage(
                previous.copy(
                  content = assistantText,
                  provider = provider,
                  thinking = thinking,
                  status = if (failed) "error" else "success",
                  toolCalls = "",
                  toolCallId = "",
                  toolResults = "",
                  steps = "",
                  origin = origin,
                  browserTools = browserTools,
                  updatedAt = now
                )
              ).flatMap(_ =>
                listSessions().flatMap { sessions =>
                  sessions.find(_.id == sessionId) match {
                    case Some(session) => updateSession(session).map(_ => ())
                    case None          => Future.successful(())
                  }
                }
              )
            case None           => Future.successful(())
          }
        } else {
          val userId    = nextId()
          val user      = ChatMessage(
            id = userId,
            sessionId = sessionId,
            parentId = tip.map(_.id),
            role = "user",
            content = userText,
            thinking = "",
            status = "success",
            toolCalls = "",
            toolCallId = "",
            toolResults = "",
            provider = provider,
            mergedFrom = Nil,
            createdAt = now,
            updatedAt = now,
            origin = origin,
            browserTools = browserTools
          )
          // assistant 的 createdAt 必须严格大于它的父消息：同毫秒并列会让"最后一条 / 树梢"这类排序选出错误的分支。
          val parentAt  = if (userText.nonEmpty) Some(now) else tip.map(_.createdAt)
          val answerAt  = childCreatedAt(parentAt)
          val assistant = ChatMessage(
            id = nextId(),
            sessionId = sessionId,
            parentId = if (userText.nonEmpty) Some(userId) else tip.map(_.id),
            role = "assistant",
            content = assistantText,
            thinking = thinking,
            status = if (failed) "error" else "success",
            toolCalls = "",
            toolCallId = "",
            toolResults = "",
            provider = provider,
            mergedFrom = Nil,
            createdAt = answerAt,
            updatedAt = answerAt,
            origin = origin,
            browserTools = browserTools
          )
          val appended  = if (userText.nonEmpty) List(user, assistant) else List(assistant)
          addMessages(appended).flatMap { _ =>
            // 会话标题还是占位时，用第一条用户消息命名（与插件页命名规则一致）。
            listSessions().flatMap { sessions =>
              sessions.find(_.id == sessionId) match {
                case Some(session) if userText.nonEmpty && isPlaceholderTitle(session.title) =>
                  updateSession(session.copy(title = userText.take(40))).map(_ => ())
                case Some(session)                                                           =>
                  updateSession(session).map(_ => ())
                case None                                                                    =>
                  Future.successful(())
              }
            }
          }
        }
      }

  /** 把一条消息路径复制成新的 session（消息是独立副本），返回新 session。 */
  def forkSession(source: ChatSession, path: List[ChatMessage], title: String): Future[ChatSession] =
    createSession(title, source.provider, folderId = source.folderId).flatMap { session =>
      val idMap           = path.map(message => message.id -> nextId()).toMap
      val base            = js.Date.now()
      val copies          = path.zipWithIndex.map { case (message, index) =>
        message.copy(
          id = idMap.getOrElse(message.id, nextId()),
          sessionId = session.id,
          parentId = message.parentId.flatMap(idMap.get),
          createdAt = base + index,
          updatedAt = base + index
        )
      }
      val inheritSettings =
        if (source.id.trim.isEmpty) Future.successful(())
        else
          SessionSettingsStore
            .load(source.id)
            .flatMap(settings =>
              SessionSettingsStore.ensure(session.id, settings.browserTools, settings.agentMode)
            )
      inheritSettings.flatMap(_ => addMessages(copies).map(_ => session))
    }
  def updateMessage(message: ChatMessage): Future[Unit]                                             =
    saveMessages(List(message))
  def deleteMessages(ids: List[String]): Future[Unit]                                               =
    removeMessages(ids)

  /** 为已有 user 问题生成一个新的 assistant 回答变体（不新增 user 消息）。 */
  def getMessagePath(id: String): Future[List[ChatMessage]] = {
    def loop(currentId: String, acc: List[ChatMessage]): Future[List[ChatMessage]] =
      listAllMessages().flatMap { all =>
        all.find(_.id == currentId) match {
          case Some(message) =>
            message.parentId match {
              case Some(parent) if !acc.exists(_.id == parent) =>
                loop(parent, message :: acc)
              case _                                           =>
                Future.successful(message :: acc)
            }
          case None          =>
            Future.successful(acc)
        }
      }
    loop(id, Nil)
  }

  private[chat] def nameSessionIfPlaceholder(sessionId: String, userText: String): Future[Unit] =
    if (sessionId.isEmpty || userText.isEmpty) Future.successful(())
    else
      listSessions().flatMap { sessions =>
        sessions.find(_.id == sessionId) match {
          case Some(session) if isPlaceholderTitle(session.title) =>
            updateSession(session.copy(title = userText.take(40))).map(_ => ())
          case _                                                  => Future.successful(())
        }
      }

  /** 和插件页 chat 同一条 agent 内核：collector 聚合过程、节流写进度、结束写最终回答（带 steps）。 */
  private def listAllMessages(): Future[List[ChatMessage]]        =
    readValues(MessageKeyPrefix).map(_.map(value => parseMessage(JsBridge.asObject(value))))
  private def getMessage(id: String): Future[Option[ChatMessage]] =
    BrowserAPIStorage.get(messageKey(id)).map(_.map(value => parseMessage(JsBridge.asObject(value))))
  private def sessionKey(id: String): String                      = s"$SessionKeyPrefix$id"
  private def folderKey(id: String): String                       = s"$FolderKeyPrefix$id"
  private def messageKey(id: String): String                      = s"$MessageKeyPrefix$id"

  /** 按 key 前缀取出所有记录：只读匹配到的 key，不把整份 storage（provider 配置 / trace 正文）拖过来。 */
  private def readValues(prefix: String): Future[List[js.Any]]                  =
    BrowserAPIStorage.keys().flatMap { all =>
      val matched = all.filter(_.startsWith(prefix))
      if (matched.isEmpty) Future.successful(Nil)
      else {
        val BatchSize                                                             = 200
        def loop(rest: List[String], acc: Vector[js.Any]): Future[Vector[js.Any]] =
          if (rest.isEmpty) Future.successful(acc)
          else {
            val (batch, tail) = rest.splitAt(BatchSize)
            BrowserAPIStorage.getMany(batch).map { values =>
              acc ++ batch.flatMap(key => values.get(key))
            }.flatMap(next => loop(tail, next))
          }
        loop(matched, Vector.empty).map(_.toList)
      }
    }
  private def parseSession(item: js.Any): ChatSession                           =
    ChatSession(
      id = JsBridge.stringField(item, "id").getOrElse(""),
      title = JsBridge.stringField(item, "title").getOrElse(""),
      theme = JsBridge.stringField(item, "theme").getOrElse("default"),
      provider = JsBridge.stringField(item, "provider").getOrElse(""),
      createdAt = JsBridge.doubleField(item, "createdAt").getOrElse(0.0),
      updatedAt = JsBridge.doubleField(item, "updatedAt").getOrElse(0.0),
      source =
        JsBridge.stringField(item, "source").filter(_.nonEmpty).getOrElse("local"),
      folderId = JsBridge.stringField(item, "folderId").getOrElse("")
    )
  private def parseSessionFolder(item: js.Any): SessionFolder                   =
    SessionFolder(
      id = JsBridge.stringField(item, "id").getOrElse(""),
      name = JsBridge.stringField(item, "name").getOrElse("").trim.take(80),
      parentId = JsBridge.stringField(item, "parentId").getOrElse(""),
      createdAt = JsBridge.doubleField(item, "createdAt").getOrElse(0.0),
      updatedAt = JsBridge.doubleField(item, "updatedAt").getOrElse(0.0)
    )
  private def parseMessage(item: js.Any): ChatMessage                           =
    ChatMessage.withAttempts(ChatMessage(
      id = JsBridge.stringField(item, "id").getOrElse(""),
      sessionId = JsBridge.stringField(item, "sessionId").getOrElse(""),
      parentId = JsBridge.stringField(item, "parentId").filter(_.nonEmpty),
      role = JsBridge.stringField(item, "role").getOrElse("user"),
      content = JsBridge.stringField(item, "content").getOrElse(""),
      thinking = JsBridge.stringField(item, "thinking").getOrElse(""),
      status = JsBridge.stringField(item, "status").getOrElse("success"),
      toolCalls = JsBridge.stringField(item, "toolCalls").getOrElse(""),
      toolCallId = JsBridge.stringField(item, "toolCallId").getOrElse(""),
      toolResults = JsBridge.stringField(item, "toolResults").getOrElse(""),
      provider = JsBridge.stringField(item, "provider").getOrElse(""),
      mergedFrom =
        JsBridge.undefinedStringArray(JsBridge.field(item, "mergedFrom")).getOrElse(js.Array[String]()).toList,
      createdAt = JsBridge.doubleField(item, "createdAt").getOrElse(0.0),
      updatedAt = JsBridge.doubleField(item, "updatedAt").getOrElse(0.0),
      mode = "chat",
      steps = JsBridge.stringField(item, "steps").getOrElse(""),
      origin = JsBridge.stringField(item, "origin").getOrElse(""),
      attempts = jsArray(JsBridge.field(item, "attempts")).map(attemptFromJs),
      activeAttemptId = JsBridge.stringField(item, "activeAttemptId").getOrElse(""),
      requestedMode = JsBridge.stringField(item, "requestedMode").getOrElse(""),
      effectiveMode = JsBridge.stringField(item, "effectiveMode").getOrElse(""),
      modePath = JsBridge.stringField(item, "modePath").getOrElse(""),
      agentRunId = JsBridge.stringField(item, "agentRunId").getOrElse(""),
      browserTools = JsBridge.booleanField(item, "browserTools").getOrElse(false),
      tabRefs = parseBrowserTabRefs(JsBridge.asObject(JsBridge.field(item, "tabRefs")))
    ))
  private def sessionToJs(s: ChatSession): js.Any                               =
    JsBridge.obj(
      "id"        -> (s.id),
      "title"     -> (s.title),
      "theme"     -> (s.theme),
      "provider"  -> (s.provider),
      "createdAt" -> (s.createdAt),
      "updatedAt" -> (s.updatedAt),
      "source"    -> (s.source),
      "folderId"  -> (s.folderId)
    )
  private def saveSession(session: ChatSession): Future[Unit]                   =
    BrowserAPIStorage.set(sessionKey(session.id), sessionToJs(session))
  private def saveSessionFolder(folder: SessionFolder): Future[Unit]            =
    BrowserAPIStorage.set(folderKey(folder.id), sessionFolderToJs(folder))
  private def sessionFolderToJs(folder: SessionFolder): js.Any                  =
    JsBridge.obj(
      "id"        -> (folder.id),
      "name"      -> (folder.name),
      "parentId"  -> (folder.parentId),
      "createdAt" -> (folder.createdAt),
      "updatedAt" -> (folder.updatedAt)
    )
  private def saveMessages(messages: List[ChatMessage]): Future[Unit]           =
    BrowserAPIStorage.setAll(js.Dictionary(messages.map(message =>
      messageKey(message.id) -> JsBridge.asObject(messageToJs(message))
    )*))
  private def removeMessages(ids: List[String]): Future[Unit]                   =
    BrowserAPIStorage.remove(ids.map(messageKey))
  private def messageToJs(raw: ChatMessage): js.Any                             = {
    val m              = ChatMessage.withAttempts(raw)
    JsBridge.obj(
      "id"              -> (m.id),
      "sessionId"       -> (m.sessionId),
      "parentId"        -> (m.parentId.getOrElse("")),
      "role"            -> (m.role),
      "content"         -> (m.content),
      "thinking"        -> (m.thinking),
      "status"          -> (m.status),
      "toolCalls"       -> (m.toolCalls),
      "toolCallId"      -> (m.toolCallId),
      "toolResults"     -> (m.toolResults),
      "provider"        -> (m.provider),
      "mergedFrom"      -> (js.Array(m.mergedFrom*)),
      "createdAt"       -> (m.createdAt),
      "updatedAt"       -> (m.updatedAt),
      "mode"            -> (m.mode),
      "steps"           -> (m.steps),
      "origin"          -> (m.origin),
      "attempts"        -> (js.Array(m.attempts.map(attemptToJs)*)),
      "activeAttemptId" -> (m.activeAttemptIdOrLegacy),
      "requestedMode"   -> (m.requestedMode),
      "effectiveMode"   -> (m.effectiveMode),
      "modePath"        -> (m.modePath),
      "agentRunId"      -> (m.agentRunId),
      "browserTools"    -> (m.browserTools),
      "tabRefs"         -> (js.Array(m.tabRefs.map(tabRefToJs)*))
    )
  }
  private def parseBrowserTabRefs(raw: js.Any): List[BrowserTabRef]             =
    jsArray(raw).flatMap { value =>
      val item = JsBridge.asObject(value)
      val ref  = JsBridge.undefinedString(item.ref).getOrElse("")
      val url  = JsBridge.undefinedString(item.url).getOrElse("")
      if (ref.isEmpty || url.isEmpty) None
      else
        Some(BrowserTabRef(
          ref = ref,
          tabId = JsBridge.undefinedInt(item.tabId).getOrElse(0),
          title = JsBridge.undefinedString(item.title).getOrElse(url),
          url = url,
          windowId = JsBridge.undefinedInt(item.windowId).getOrElse(0),
          index = JsBridge.undefinedInt(item.index).getOrElse(0),
          pinned = JsBridge.undefinedBoolean(item.pinned).getOrElse(false),
          groupId = JsBridge.undefinedInt(item.groupId).getOrElse(-1),
          groupTitle = JsBridge.undefinedString(item.groupTitle).getOrElse(""),
          groupColor = JsBridge.undefinedString(item.groupColor).getOrElse("")
        ))
    }
  private def tabRefToJs(tab: BrowserTabRef): js.Any                            =
    JsBridge.obj(
      "ref"        -> (tab.ref),
      "tabId"      -> (tab.tabId),
      "title"      -> (tab.title),
      "url"        -> (tab.url),
      "windowId"   -> (tab.windowId),
      "index"      -> (tab.index),
      "pinned"     -> (tab.pinned),
      "groupId"    -> (tab.groupId),
      "groupTitle" -> (tab.groupTitle),
      "groupColor" -> (tab.groupColor)
    )
  private def attemptFromJs(raw: js.Any): ChatAttempt                           = {
    val m = JsBridge.asObject(raw)
    ChatAttempt(
      id = JsBridge.undefinedString(m.id).getOrElse(""),
      index = JsBridge.undefinedInt(m.index).getOrElse(1),
      trigger = JsBridge.undefinedString(m.trigger).getOrElse(ChatAttempt.Initial),
      status = JsBridge.undefinedString(m.status).getOrElse("success"),
      provider = JsBridge.undefinedString(m.provider).getOrElse(""),
      content = JsBridge.undefinedString(m.content).getOrElse(""),
      thinking = JsBridge.undefinedString(m.thinking).getOrElse(""),
      steps = JsBridge.undefinedString(m.steps).getOrElse(""),
      toolCalls = JsBridge.undefinedString(m.toolCalls).getOrElse(""),
      toolCallId = JsBridge.undefinedString(m.toolCallId).getOrElse(""),
      toolResults = JsBridge.undefinedString(m.toolResults).getOrElse(""),
      error = JsBridge.undefinedString(m.error).getOrElse(""),
      createdAt = JsBridge.undefinedDouble(m.createdAt).getOrElse(0.0),
      updatedAt = JsBridge.undefinedDouble(m.updatedAt).getOrElse(0.0),
      parentAttemptId = JsBridge.undefinedString(m.parentAttemptId).getOrElse(""),
      requestedMode = JsBridge.undefinedString(m.requestedMode).getOrElse(""),
      effectiveMode = JsBridge.undefinedString(m.effectiveMode).getOrElse(""),
      modePath = JsBridge.undefinedString(m.modePath).getOrElse(""),
      agentRunId = JsBridge.undefinedString(m.agentRunId).getOrElse(""),
      browserTools = JsBridge.undefinedBoolean(m.browserTools).getOrElse(false)
    )
  }
  private def attemptToJs(attempt: ChatAttempt): js.Any                         =
    JsBridge.obj(
      "id"              -> (attempt.id),
      "index"           -> (attempt.index),
      "trigger"         -> (attempt.trigger),
      "status"          -> (attempt.status),
      "provider"        -> (attempt.provider),
      "content"         -> (attempt.content),
      "thinking"        -> (attempt.thinking),
      "steps"           -> (attempt.steps),
      "toolCalls"       -> (attempt.toolCalls),
      "toolCallId"      -> (attempt.toolCallId),
      "toolResults"     -> (attempt.toolResults),
      "error"           -> (attempt.error),
      "createdAt"       -> (attempt.createdAt),
      "updatedAt"       -> (attempt.updatedAt),
      "parentAttemptId" -> (attempt.parentAttemptId),
      "requestedMode"   -> (attempt.requestedMode),
      "effectiveMode"   -> (attempt.effectiveMode),
      "modePath"        -> (attempt.modePath),
      "agentRunId"      -> (attempt.agentRunId),
      "browserTools"    -> (attempt.browserTools)
    )
  private def loadUndoStacks(): Future[Map[String, List[UndoStep]]]             =
    BrowserAPIStorage.get(UndoStacksKey).map {
      case Some(value) =>
        JsBridge.asDictionary(value).toMap.map { case (sessionId, stepsRaw) =>
          val steps = jsArray(stepsRaw).map(undoStepFromJs)
          sessionId -> steps
        }
      case None        =>
        Map.empty[String, List[UndoStep]]
    }
  private def saveUndoStacks(stacks: Map[String, List[UndoStep]]): Future[Unit] =
    BrowserAPIStorage.set(
      UndoStacksKey,
      js.Dictionary(stacks.toSeq.map { case (sessionId, steps) =>
        sessionId -> JsBridge.asObject(js.Array(steps.map(undoStepToJs)*))
      }*)
    )
  private def undoStepToJs(step: UndoStep): js.Any                              =
    JsBridge.obj(
      "version"      -> (4),
      "messages"     -> (js.Array(step.messages.map(message => JsBridge.asObject(messageToJs(message)))*)),
      "kind"         -> (if (step.kind == ClearKind) ClearKind else UndoKind),
      "sessionTitle" -> (step.sessionTitle.map(JsBridge.asObject(_)).getOrElse(js.undefined))
    )
  private def undoStepFromJs(raw: js.Any): UndoStep                             = {
    val dynamic = JsBridge.asObject(raw)
    UndoStep(
      messages = jsArray(dynamic.messages).map(message => parseMessage(JsBridge.asObject(message))),
      kind = JsBridge.undefinedString(dynamic.kind).getOrElse(UndoKind) match {
        case ClearKind => ClearKind
        case _         => UndoKind
      },
      sessionTitle = JsBridge.undefinedString(dynamic.sessionTitle).toOption.filter(_ != null)
    )
  }
  private def jsArray(raw: js.Any): List[js.Any]                                =
    JsBridge.undefinedArrayAny(raw).toOption.map(_.toList).getOrElse(Nil)
  private object BrowserAPIStorage {
    def get(key: String): Future[Option[js.Any]]      =
      ChromeAPI.storage.local.get(key).toFuture.map(result => result.get(key))
    def set(key: String, value: js.Any): Future[Unit] =
      ChromeAPI.storage.local.set(Dictionary[js.Any](key -> value)).toFuture.map(_ => ())

    /** 批量写：一次 `set` 带上所有 key，避免逐条往返。 */
    def setAll(items: js.Dictionary[js.Any]): Future[Unit]         =
      if (items.isEmpty) Future.successful(())
      else ChromeAPI.storage.local.set(items).toFuture.map(_ => ())
    def getMany(keys: List[String]): Future[js.Dictionary[js.Any]] =
      if (keys.isEmpty) Future.successful(js.Dictionary.empty[js.Any])
      else ChromeAPI.storage.local.get(js.Array(keys*)).toFuture
    def remove(keys: List[String]): Future[Unit]                   =
      if (keys.isEmpty) Future.successful(())
      else ChromeAPI.storage.local.remove(js.Array(keys*)).toFuture.map(_ => ())

    /** key 列表：优先 `getKeys()`（Chrome 130+），老版本退回整份 `get()` 再取 key（只取 key、值丢弃）。 */
    def keys(): Future[List[String]] = {
      val area       = ChromeAPI.storage.local
      val hasGetKeys = !js.isUndefined(JsBridge.field(area, "getKeys"))
      if (hasGetKeys) area.getKeys().toFuture.map(_.toList)
      else
        area.get().toFuture.map(values => JsonValue.dictionary(values).keys.toList)
    }
  }
  private[chat] def nextId(): String =
    s"${js.Date.now()}-${Math.round(Math.random() * 1e9)}"
}
