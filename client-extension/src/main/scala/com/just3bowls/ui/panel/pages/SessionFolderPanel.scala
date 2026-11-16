package com.just3bowls.ui.panel.pages

import com.just3bowls.facade.JsBridge
import com.just3bowls.model.chat.*

import com.just3bowls.ui.components.*

import com.raquo.laminar.api.L._
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import org.scalajs.dom
import scala.concurrent.Future
import scala.scalajs.js
final private[ui] class SessionFolderPanel(
    currentSessionId: Var[String],
    sessions: Var[List[ChatSession]],
    folders: Var[List[SessionFolder]],
    draftFolderId: Var[String],
    onMove: (String, String) => Future[Unit],
    onMoveFolder: (String, String) => Future[Unit],
    onCreate: (String, String) => Future[SessionFolder],
    onRename: (String, String) => Future[Unit],
    onDelete: String => Future[Unit],
    onNewSession: String => Future[String],
    onOpenSession: String => Unit,
    onRenameSession: (String, String) => Future[Unit],
    onDeleteSession: String => Future[Unit],
    onOpen: () => Unit
) {
  import SessionFolderPanel._
  val TriggerId                                            = "popcan-session-folder-trigger"
  val PanelId                                              = "popcan-session-folder-panel"
  private val NameId                                       = "popcan-session-folder-name"
  private val SessionNameId                                = "popcan-session-name"
  private val selectedSessionClass                         = "th-session-selected"
  private val DragMime                                     = "application/x-popcan-session-node"
  private val open                                         = Var(false)
  private val collapsed                                    = Var(Set.empty[String])
  private val selected                                     = Var[TreeSelection](NoSelection)
  private val editing                                      = Var("")
  private val draft                                        = Var("")
  private val editingSessionId                             = Var("")
  private val sessionDraft                                 = Var("")
  private val confirmDelete                                = Var(Option.empty[TreeSelection])
  private val busy                                         = Var(false)
  private val creating                                     = Var(false)
  private val creatingParentId                             = Var("")
  private val dragging                                     = Var[Option[TreeSelection]](None)
  private val dragTarget                                   = Var[Option[DropTarget]](None)
  private val moving                                       = Var[Option[TreeSelection]](None)
  private val moveTarget                                   = Var("")
  private var pendingClickTimer                            = Option.empty[js.timers.SetTimeoutHandle]
  private var pendingClickTarget                           = Option.empty[TreeSelection]
  private var dragExpandTimer                              = Option.empty[js.timers.SetTimeoutHandle]
  private var expansionInitialized                         = false
  private val currentFolder                                =
    Signal.combine(currentSessionId.signal, sessions.signal, draftFolderId, folders.signal).map {
      case (sessionId, allSessions, draftId, allFolders) =>
        val candidate = allSessions.find(_.id == sessionId).map(_.folderId).getOrElse(draftId)
        if (candidate.nonEmpty && allFolders.exists(_.id == candidate)) candidate else ""
    }
  private val currentPath                                  =
    currentFolder.combineWith(folders.signal).map { case (folderId, all) =>
      path(all, folderId)
    }
  val pathPrefix: Signal[String]                           = currentPath.map(SessionFolderPanel.pathPrefix)
  val isOpen: Signal[Boolean]                              = open.signal
  def close(): Unit                                        = {
    clearPendingTimers()
    open.set(false)
    creating.set(false)
    creatingParentId.set("")
    editing.set("")
    draft.set("")
    editingSessionId.set("")
    sessionDraft.set("")
    confirmDelete.set(None)
    dragging.set(None)
    dragTarget.set(None)
    moving.set(None)
    moveTarget.set("")
  }
  def renderPanel: HtmlElement                             =
    div(
      idAttr          := PanelId,
      role            := "dialog",
      aria.label      := "Browse sessions and folders",
      aria.hidden <-- open.signal.map(!_),
      tabIndex        := -1,
      position        := "absolute",
      left            := "0",
      bottom          := "calc(100% + 6px)",
      width           := "min(320px, calc(100vw - 20px))",
      maxHeight       := "min(420px, calc(100vh - 120px))",
      zIndex          := "215",
      flexDirection.column,
      backgroundColor := "#ffffff",
      border          := "1px solid #e2e8f0",
      borderRadius    := "8px",
      boxShadow       := "0 14px 34px rgba(15,23,42,0.16)",
      overflow.hidden,
      display <-- open.signal.map(value => if (value) "flex" else "none"),
      documentEvents(_.onKeyDown).filter(event =>
        open.now() && moving.now().isEmpty && event.key == "Escape"
      ) --> { event =>
        event.preventDefault()
        if (confirmDelete.now().nonEmpty) confirmDelete.set(None)
        else close()
      },
      documentEvents(_.onKeyDown).filter(_ =>
        open.now() && editingSessionId.now().isEmpty && confirmDelete.now().isEmpty
      ) --> { event =>
        val target   = event.target.asInstanceOf[dom.Node]
        val panel    = dom.document.getElementById(PanelId)
        val isEditor = {
          val element = event.target.asInstanceOf[dom.Element]
          panel != null &&
          panel.contains(target) &&
          (element.tagName == "INPUT" || element.tagName == "TEXTAREA")
        }
        if (!isEditor && !handleMoveModeKey(event))
          event.key match {
            case "ArrowDown"                           =>
              event.preventDefault()
              moveTreeSelection(1)
            case "ArrowUp"                             =>
              event.preventDefault()
              moveTreeSelection(-1)
            case "ArrowLeft"                           =>
              event.preventDefault()
              collapseSelectionOrGoToParent()
            case "ArrowRight"                          =>
              event.preventDefault()
              expandSelectionOrGoToFirstChild()
            case "Enter"                               =>
              event.preventDefault()
              activateSelection()
            case "F2"                                  =>
              event.preventDefault()
              renameSelection()
            case "m" | "M"                             =>
              event.preventDefault()
              startMoveMode()
            case "x" if event.ctrlKey || event.metaKey =>
              event.preventDefault()
              requestDeleteSelection()
            case _                                     =>
              ()
          }
      },
      documentEvents(_.onMouseDown).filter { event =>
        open.now() && {
          val target  = event.target.asInstanceOf[dom.Node]
          val panel   = dom.document.getElementById(PanelId)
          val trigger = dom.document.getElementById(TriggerId)
          val inside  =
            (panel != null && panel.contains(target)) || (trigger != null && trigger.contains(target))
          !inside
        }
      } --> { _ => close() },
      div(
        display.flex,
        alignItems.center,
        gap                              := "2px",
        padding                          := "7px 8px 7px 12px",
        borderBottom                     := "1px solid #f1f5f9",
        span("Sessions", flexGrow := "1", fontSize := "11px", fontWeight.bold, color := "#475569"),
        actionText("+ Session", "Create a session in the selected folder", createSessionInContext),
        actionText("+ Folder", "Create a folder in the current location", createFolderInContext),
        actionIcon("×", "Close sessions", () => close())
      ),
      rootDropZone,
      div(
        flexGrow                         := "1",
        minHeight                        := "0",
        overflowY.auto,
        styleProp("overscroll-behavior") := "contain",
        padding                          := "4px 0",
        children <-- Signal
          .combine(
            folders.signal,
            currentFolder,
            sessions.signal,
            creating.signal,
            editing.signal,
            creatingParentId,
            collapsed.signal,
            selected.signal
          )
          .map {
            case (
                  allFolders,
                  folderId,
                  allSessions,
                  isCreating,
                  editId,
                  createParentId,
                  collapsedIds,
                  selectedRow
                ) =>
              val rows           = tree(allFolders, allSessions, collapsedIds).flatMap {
                case row: FolderTreeRow  =>
                  val rowNode = folderRow(
                    id = row.folder.id,
                    name = row.folder.name,
                    level = row.level,
                    isLast = row.isLast,
                    ancestorContinues = row.ancestorContinues,
                    currentLocation = row.folder.id == folderId,
                    rowSelected = selectedRow == FolderSelection(row.folder.id),
                    expanded = !collapsedIds.contains(row.folder.id),
                    canDelete = canDelete(allFolders, allSessions, row.folder.id),
                    isEditing = editId == row.folder.id
                  )
                  val editor  =
                    if (isCreating && createParentId == row.folder.id)
                      List(
                        folderEditor(
                          level = row.level + 1,
                          isLast =
                            !allFolders.exists(_.parentId == row.folder.id) &&
                              !allSessions.exists(_.folderId == row.folder.id),
                          ancestorContinues = row.ancestorContinues :+ !row.isLast,
                          hint = "New child folder",
                          saveNew,
                          cancelEditing
                        )
                      )
                    else Nil
                  rowNode :: editor
                case row: SessionTreeRow =>
                  List(
                    sessionRow(
                      session = row.session,
                      level = row.level,
                      isLast = row.isLast,
                      ancestorContinues = row.ancestorContinues,
                      rowSelected = selectedRow == SessionSelection(row.session.id)
                    )
                  )
              }
              val topLevelEditor =
                if (isCreating && createParentId.isEmpty)
                  List(
                    folderEditor(
                      level = 0,
                      isLast = allFolders.forall(_.parentId.nonEmpty) && allSessions.forall(_.folderId.nonEmpty),
                      ancestorContinues = Nil,
                      hint = "New top-level folder",
                      saveNew,
                      cancelEditing
                    )
                  )
                else Nil
              topLevelEditor ::: rows
          }
      )
    )
  def openPanel(): Unit                                    = {
    onOpen()
    expandCurrentPath()
    ensureTreeSelection()
    open.set(true)
    focusPanel()
  }
  def toggle(): Unit                                       =
    if (open.now()) close() else openPanel()
  private def focusPanel(): Unit                           =
    js.timers.setTimeout(0) {
      Option(dom.document.getElementById(PanelId).asInstanceOf[dom.HTMLElement]).foreach(_.focus())
    }
  private def expandCurrentPath(): Unit                    = {
    val allFolderIds = folders.now().map(_.id).toSet
    val pathIds      = path(folders.now(), currentFolderNow()).map(_.id).toSet
    collapsed.set(
      if (expansionInitialized) collapsed.now() -- pathIds
      else allFolderIds -- pathIds
    )
    expansionInitialized = true
  }
  private def ensureTreeSelection(): Unit                  = {
    val nodes      = visibleRowsNow()
    val choices    = nodes.map(selectionOf)
    val current    = SessionSelection(currentSessionId.now())
    val currentDir = FolderSelection(currentFolderNow())
    val previous   = selected.now()
    val next       =
      if (currentSessionId.now().nonEmpty && choices.contains(current)) current
      else if (previous != NoSelection && choices.contains(previous)) previous
      else if (currentFolderNow().nonEmpty && choices.contains(currentDir)) currentDir
      else choices.headOption.getOrElse(NoSelection)
    selected.set(next)
  }
  private def moveTreeSelection(delta: Int): Unit          = {
    val choices = visibleRowsNow().map(selectionOf)
    if (choices.nonEmpty) {
      val current = choices.indexOf(selected.now())
      val next    = if (current < 0) 0 else (current + delta + choices.size) % choices.size
      selected.set(choices(next))
      scrollSelectedRowIntoView()
    }
  }
  private def visibleRowsNow(): List[TreeNode]             =
    tree(folders.now(), sessions.now(), collapsed.now())
  private def selectionOf(row: TreeNode): TreeSelection    =
    row match {
      case row: FolderTreeRow  => FolderSelection(row.folder.id)
      case row: SessionTreeRow => SessionSelection(row.session.id)
    }
  private def rowLevel(row: TreeNode): Int                 =
    row match {
      case row: FolderTreeRow  => row.level
      case row: SessionTreeRow => row.level
    }
  private def scrollSelectedRowIntoView(): Unit            =
    js.timers.setTimeout(0) {
      Option(dom.document.querySelector(s".$selectedSessionClass")).foreach { node =>
        JsBridge.call(
          node,
          "scrollIntoView",
          JsBridge.obj("block" -> ("nearest"), "inline" -> ("nearest"))
        )
        ()
      }
    }
  private def toggleFolder(id: String): Unit               = {
    clearPendingClick()
    confirmDelete.set(None)
    selected.set(FolderSelection(id))
    collapsed.update(ids => if (ids.contains(id)) ids - id else ids + id)
  }
  private def collapseSelectionOrGoToParent(): Unit        =
    selected.now() match {
      case FolderSelection(id)  =>
        if (!collapsed.now().contains(id)) collapsed.update(_ + id)
        else parentFolderId(id).foreach(parent => selected.set(FolderSelection(parent)))
      case SessionSelection(id) =>
        sessions.now().find(_.id == id).map(_.folderId).filter(_.nonEmpty).foreach { parent =>
          selected.set(FolderSelection(parent))
        }
      case NoSelection          =>
        ()
    }
  private def expandSelectionOrGoToFirstChild(): Unit      =
    selected.now() match {
      case FolderSelection(id) =>
        if (collapsed.now().contains(id)) {
          collapsed.update(_ - id)
        } else {
          val rows        = visibleRowsNow()
          val index       = rows.indexWhere {
            case row: FolderTreeRow => row.folder.id == id
            case _                  => false
          }
          val parentLevel = rows.lift(index).fold(-1)(rowLevel)
          rows.lift(index + 1).filter(rowLevel(_) > parentLevel).foreach { child =>
            selected.set(selectionOf(child))
            scrollSelectedRowIntoView()
          }
        }
      case _                   =>
        ()
    }
  private def activateSelection(): Unit                    =
    selected.now() match {
      case FolderSelection(id)  => toggleFolder(id)
      case SessionSelection(id) => onOpenSession(id)
      case NoSelection          => ()
    }
  private def renameSelection(): Unit                      =
    selected.now() match {
      case FolderSelection(id)  =>
        folders.now().find(_.id == id).foreach(folder => beginRename(folder.id))
      case SessionSelection(id) =>
        sessions.now().find(_.id == id).foreach(beginSessionRename)
      case NoSelection          =>
        ()
    }
  private def requestDeleteSelection(): Unit               =
    selected.now() match {
      case FolderSelection(id)  =>
        folders.now().find(_.id == id).foreach { folder =>
          if (canDelete(folders.now(), sessions.now(), folder.id))
            confirmDelete.set(Some(FolderSelection(id)))
          else Toast.info("Move or delete the folder contents first")
        }
      case SessionSelection(id) =>
        sessions.now().find(_.id == id).foreach(_ => confirmDelete.set(Some(SessionSelection(id))))
      case NoSelection          =>
        ()
    }
  private def createSessionInContext(): Unit               =
    if (!busy.now()) {
      confirmDelete.set(None)
      val folderId = validFolderContext(selectedFolderContextId())
      busy.set(true)
      onNewSession(folderId)
        .map { sessionId =>
          if (folderId.nonEmpty) collapsed.update(_ - folderId)
          selected.set(SessionSelection(sessionId))
          scrollSelectedRowIntoView()
          sessions.now().find(_.id == sessionId).foreach(beginSessionRename)
        }
        .recover { case error =>
          Toast.error(s"Create session failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    }
  private def createFolderInContext(): Unit                =
    beginCreate(selectedFolderContextId())
  private def selectedFolderContextId(): String            =
    selected.now() match {
      case FolderSelection(id)  => id
      case SessionSelection(id) =>
        sessions.now().find(_.id == id).map(_.folderId).getOrElse(currentFolderNow())
      case NoSelection          => currentFolderNow()
    }
  private def validFolderContext(folderId: String): String =
    if (folderId.nonEmpty && folders.now().exists(_.id == folderId)) folderId else ""
  private def parentFolderId(id: String): Option[String]   =
    folders.now().find(_.id == id).map(_.parentId).filter(_.nonEmpty)
  private def clearPendingTimers(): Unit                   = {
    pendingClickTimer.foreach(js.timers.clearTimeout)
    pendingClickTimer = None
    pendingClickTarget = None
    dragExpandTimer.foreach(js.timers.clearTimeout)
    dragExpandTimer = None
  }
  private def handleNodeClick(
      selection: TreeSelection,
      singleAction: () => Unit,
      doubleAction: () => Unit
  ): Unit = {
    confirmDelete.set(None)
    selected.set(selection)
    if (pendingClickTarget.contains(selection)) {
      clearPendingClick()
      doubleAction()
    } else {
      clearPendingClick()
      pendingClickTarget = Some(selection)
      pendingClickTimer = Some(js.timers.setTimeout(220) {
        pendingClickTimer = None
        pendingClickTarget = None
        singleAction()
      })
    }
  }
  private def clearPendingClick(): Unit                    = {
    pendingClickTimer.foreach(js.timers.clearTimeout)
    pendingClickTimer = None
    pendingClickTarget = None
  }
  private def beginNodeDrag(event: dom.DragEvent, selection: TreeSelection): Unit      = {
    event.stopPropagation()
    clearPendingClick()
    selected.set(selection)
    dragging.set(Some(selection))
    dragTarget.set(None)
    event.dataTransfer.effectAllowed = "move".asInstanceOf[dom.DataTransferEffectAllowedKind]
    event.dataTransfer.setData(DragMime, selectionKey(selection))
    event.dataTransfer.setData("text/plain", selectionName(selection))
  }
  private def clearDrag(): Unit                                                        = {
    dragExpandTimer.foreach(js.timers.clearTimeout)
    dragExpandTimer = None
    dragging.set(None)
    dragTarget.set(None)
  }
  private def dragOverTarget(event: dom.DragEvent, target: DropTarget): Unit           =
    dragging.now().foreach { source =>
      if (canDrop(source, target)) {
        event.preventDefault()
        event.stopPropagation()
        event.dataTransfer.dropEffect = "move".asInstanceOf[dom.DataTransferDropEffectKind]
        dragTarget.set(Some(target))
        scheduleDragExpand(target)
      } else {
        dragTarget.set(None)
        dragExpandTimer.foreach(js.timers.clearTimeout)
        dragExpandTimer = None
        event.dataTransfer.dropEffect = "none".asInstanceOf[dom.DataTransferDropEffectKind]
      }
    }
  private def dropOnTarget(event: dom.DragEvent, target: DropTarget): Unit             = {
    event.preventDefault()
    event.stopPropagation()
    dragging.now().foreach { source =>
      if (canDrop(source, target)) {
        val destination = targetFolderId(target)
        clearDrag()
        moveNodeTo(source, destination)
      }
    }
  }
  private def scheduleDragExpand(target: DropTarget): Unit                             = {
    dragExpandTimer.foreach(js.timers.clearTimeout)
    dragExpandTimer = target match {
      case FolderDropTarget(id) if collapsed.now().contains(id) =>
        Some(js.timers.setTimeout(500) {
          dragExpandTimer = None
          collapsed.update(_ - id)
        })
      case _                                                    =>
        None
    }
  }
  private def canDrop(source: TreeSelection, target: DropTarget): Boolean              = {
    val destination = targetFolderId(target)
    source match {
      case SessionSelection(id) =>
        sessions.now().find(_.id == id).exists(_.folderId != destination)
      case FolderSelection(id)  =>
        folders.now().find(_.id == id).exists(_.parentId != destination) &&
        !folderIsInside(destination, id)
      case NoSelection          =>
        false
    }
  }
  private def folderIsInside(candidateId: String, ancestorId: String): Boolean         = {
    val byId                                         = folders.now().map(folder => folder.id -> folder).toMap
    def loop(id: String, seen: Set[String]): Boolean =
      if (id.isEmpty || seen.contains(id)) false
      else if (id == ancestorId) true
      else byId.get(id).exists(folder => loop(folder.parentId, seen + id))
    loop(candidateId, Set.empty)
  }
  private def targetFolderId(target: DropTarget): String                               =
    target match {
      case RootDropTarget        => ""
      case FolderDropTarget(id)  => id
      case SessionDropTarget(id) => sessions.now().find(_.id == id).map(_.folderId).getOrElse("")
    }
  private def startMoveMode(): Unit                                                    =
    confirmDelete.set(None)
    selected.now() match {
      case NoSelection =>
        Toast.info("Select a session or folder before moving")
      case selection   =>
        val targets = moveTargets(selection)
        if (targets.isEmpty) Toast.info("No valid move targets")
        else {
          moving.set(Some(selection))
          val initial = defaultParent(selection)
          setMoveTarget(if (targets.contains(initial)) initial else targets.head)
          Toast.info(s"Moving ${selectionName(selection)} — choose a folder · Enter confirms · Esc cancels")
        }
    }
  private def handleMoveModeKey(event: dom.KeyboardEvent): Boolean                     =
    moving.now() match {
      case None         => false
      case Some(source) =>
        event.key match {
          case "ArrowDown" =>
            event.preventDefault()
            moveMoveTarget(1)
          case "ArrowUp"   =>
            event.preventDefault()
            moveMoveTarget(-1)
          case "Enter"     =>
            event.preventDefault()
            val target = moveTarget.now()
            moving.set(None)
            moveNodeTo(source, target)
          case "Escape"    =>
            event.preventDefault()
            moving.set(None)
            moveTarget.set("")
            Toast.info("Move canceled")
          case _           =>
            ()
        }
        true
    }
  private def moveMoveTarget(delta: Int): Unit                                         =
    moving.now().foreach { source =>
      val targets = moveTargets(source)
      if (targets.nonEmpty) {
        val current = targets.indexOf(moveTarget.now())
        val next    = if (current < 0) 0 else (current + delta + targets.size) % targets.size
        setMoveTarget(targets(next))
        scrollMoveTargetIntoView()
      }
    }
  private def moveTargets(source: TreeSelection): List[String]                         = {
    val folderIds = tree(folders.now(), Nil, Set.empty).collect {
      case row: FolderTreeRow if canMoveTo(source, row.folder.id) => row.folder.id
    }
    "" :: folderIds
  }
  private def canMoveTo(source: TreeSelection, destination: String): Boolean           =
    canDrop(source, FolderDropTarget(destination))
  private def defaultParent(source: TreeSelection): String                             =
    source match {
      case SessionSelection(id) => sessions.now().find(_.id == id).map(_.folderId).getOrElse("")
      case FolderSelection(id)  => folders.now().find(_.id == id).map(_.parentId).getOrElse("")
      case NoSelection          => ""
    }
  private def setMoveTarget(folderId: String): Unit                                    = {
    moveTarget.set(folderId)
    if (folderId.nonEmpty)
      collapsed.update(_ -- path(folders.now(), folderId).map(_.id))
  }
  private def scrollMoveTargetIntoView(): Unit                                         =
    js.timers.setTimeout(0) {
      val selector = if (moveTarget.now().isEmpty) ".th-root-move-target" else ".th-folder-move-target"
      Option(dom.document.querySelector(selector)).foreach { node =>
        JsBridge.call(
          node,
          "scrollIntoView",
          JsBridge.obj("block" -> ("nearest"), "inline" -> ("nearest"))
        )
        ()
      }
    }
  private def moveNodeTo(source: TreeSelection, folderId: String): Unit                =
    if (!busy.now()) {
      busy.set(true)
      val operation = source match {
        case SessionSelection(id) =>
          val same = sessions.now().find(_.id == id).exists(_.folderId == folderId)
          if (same) Future.successful(()) else onMove(id, folderId)
        case FolderSelection(id)  =>
          val same = folders.now().find(_.id == id).exists(_.parentId == folderId)
          if (same) Future.successful(()) else onMoveFolder(id, folderId)
        case NoSelection          =>
          Future.successful(())
      }
      operation
        .map { _ =>
          if (folderId.nonEmpty) collapsed.update(_ - folderId)
          selected.set(source)
          js.timers.setTimeout(0)(scrollSelectedRowIntoView())
        }
        .recover { case error =>
          Toast.error(s"Move failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    }
  private def selectionKey(selection: TreeSelection): String                           =
    selection match {
      case SessionSelection(id) => s"session:$id"
      case FolderSelection(id)  => s"folder:$id"
      case NoSelection          => ""
    }
  private def selectionName(selection: TreeSelection): String                          =
    selection match {
      case SessionSelection(id) =>
        sessions.now()
          .find(_.id == id)
          .flatMap(session => Option(session.title).map(_.trim).filter(_.nonEmpty))
          .getOrElse(ChatStore.NewSessionTitle)
      case FolderSelection(id)  => folders.now().find(_.id == id).map(_.name).getOrElse("folder")
      case NoSelection          => ""
    }
  private def rootDropZone: HtmlElement                                                = {
    val active            = Signal.combine(dragging.signal, moving.signal).map {
      case (None, None) => false
      case _            => true
    }
    val highlighted       = Signal.combine(dragTarget.signal, moving.signal, moveTarget.signal).map {
      case (Some(RootDropTarget), _, _) => true
      case (_, Some(_), "")             => true
      case _                            => false
    }
    div(
      display <-- active.map(if (_) "block" else "none"),
      margin       := "4px 8px",
      padding      := "6px 8px",
      border       := "1px dashed #7dd3fc",
      borderRadius := "5px",
      fontSize     := "10px",
      color        := "#0369a1",
      textAlign.center,
      cursor.pointer,
      className <-- highlighted.map(value => if (value) "th-root-move-target" else ""),
      backgroundColor <-- highlighted.map(value => if (value) "#dbeafe" else "#f8fafc"),
      onDragOver --> { event => dragOverTarget(event, RootDropTarget) },
      onDrop --> { event => dropOnTarget(event, RootDropTarget) },
      onClick --> { _ =>
        if (moving.now().nonEmpty) setMoveTarget("")
      },
      "Move to root"
    )
  }
  private def folderRow(
      id: String,
      name: String,
      level: Int,
      isLast: Boolean,
      ancestorContinues: List[Boolean],
      currentLocation: Boolean,
      rowSelected: Boolean,
      expanded: Boolean,
      canDelete: Boolean,
      isEditing: Boolean
  ): HtmlElement = {
    val hovered          = Var(false)
    val isDropTarget     = dragTarget.signal.map(_ == FolderDropTarget(id))
    val confirming       = confirmDelete.signal.map(_ == FolderSelection(id))
    val isMoveTarget     = Signal.combine(moving.signal, moveTarget.signal).map {
      case (Some(_), target) => target == id
      case _                 => false
    }
    div(
      position     := "relative",
      display.flex,
      alignItems.center,
      minHeight    := "26px",
      paddingLeft  := "4px",
      paddingRight := "6px",
      className <-- Signal.combine(isMoveTarget, isDropTarget).map {
        case (true, _) => "th-session-row th-folder-move-target"
        case (_, true) => s"th-session-row $selectedSessionClass"
        case _         => if (rowSelected) s"th-session-row $selectedSessionClass" else "th-session-row"
      },
      backgroundColor <-- Signal.combine(isMoveTarget, isDropTarget).map {
        case (true, _) => "#dbeafe"
        case (_, true) => "#e0f2fe"
        case _         => if (rowSelected) "#eff6ff" else "transparent"
      },
      boxShadow <-- isDropTarget.map(if (_) "inset 0 0 0 1px #7dd3fc" else "none"),
      opacity <-- dragging.signal.map {
        case Some(FolderSelection(draggedId)) if draggedId == id => "0.45"
        case _                                                   => "1"
      },
      draggable    := !isEditing,
      onDragStart --> { event =>
        if (!isEditing) beginNodeDrag(event, FolderSelection(id))
      },
      onDragEnd --> { _ => clearDrag() },
      onDragOver --> { event => dragOverTarget(event, FolderDropTarget(id)) },
      onDrop --> { event => dropOnTarget(event, FolderDropTarget(id)) },
      onMouseEnter --> { _ => hovered.set(true) },
      onMouseLeave --> { _ => hovered.set(false) },
      treePrefix(level, isLast, ancestorContinues),
      if (isEditing) emptyNode else expander(id, expanded),
      if (isEditing)
        folderEditorFields("", saveRename, cancelEditing)
      else
        button(
          `type`          := "button",
          display.flex,
          alignItems.center,
          gap             := "6px",
          flexGrow        := "1",
          minWidth        := "0",
          padding         := "4px 0",
          cursor.pointer,
          textAlign.left,
          fontSize        := "11px",
          color           := (if (currentLocation || rowSelected) "#1d4ed8" else "#475569"),
          fontWeight      := (if (currentLocation) "600" else "400"),
          backgroundColor := "transparent",
          border          := "none",
          disabled <-- busy.signal,
          title           := s"Select $name. Double-click to rename.",
          span(name, minWidth := "0", overflow.hidden, textOverflow.ellipsis, whiteSpace.nowrap),
          onClick --> { _ =>
            if (moving.now().nonEmpty) setMoveTarget(id)
            else
              handleNodeClick(
                FolderSelection(id),
                singleAction = () => toggleFolder(id),
                doubleAction = () => beginRename(id)
              )
          }
        ),
      if (!isEditing)
        div(
          display.flex,
          alignItems.center,
          gap        := "1px",
          flexShrink := "0",
          opacity <-- Signal
            .combine(hovered.signal, confirming)
            .map { case (isHovered, isConfirming) => if (isHovered || isConfirming) "1" else "0" },
          pointerEvents <-- Signal
            .combine(hovered.signal, confirming)
            .map { case (isHovered, isConfirming) => if (isHovered || isConfirming) "auto" else "none" },
          child <-- confirming.map {
            case true  =>
              InlineDeleteConfirmation.render(
                onConfirm = () => deleteFolder(id, s"Deleted folder: $name"),
                onCancel = () =>
                  if (confirmDelete.now().contains(FolderSelection(id))) confirmDelete.set(None),
                busySignal = busy.signal
              )
            case false =>
              if (canDelete)
                actionIcon("×", s"Delete empty folder $name", () => confirmDelete.set(Some(FolderSelection(id))))
              else span("")
          }
        )
      else emptyNode
    )
  }
  private def sessionRow(
      session: ChatSession,
      level: Int,
      isLast: Boolean,
      ancestorContinues: List[Boolean],
      rowSelected: Boolean
  ): HtmlElement = {
    val hovered          = Var(false)
    val isCurrent        = currentSessionId.signal.map(_ == session.id)
    val isEditing        = editingSessionId.signal.map(_ == session.id)
    val confirming       = confirmDelete.signal.map(_ == SessionSelection(session.id))
    val displayTitle     = Option(session.title).map(_.trim).filter(_.nonEmpty).getOrElse(ChatStore.NewSessionTitle)
    val displayPath      = s"${SessionFolderPanel.pathPrefix(path(folders.now(), session.folderId))}$displayTitle"
    val isDropTarget     = dragTarget.signal.map(_ == SessionDropTarget(session.id))
    div(
      position     := "relative",
      display.flex,
      alignItems.center,
      minHeight    := "26px",
      paddingLeft  := "4px",
      paddingRight := "6px",
      className <-- isDropTarget.map(drop =>
        if (drop || rowSelected) s"th-session-row $selectedSessionClass" else "th-session-row"
      ),
      backgroundColor <-- isDropTarget.map(drop =>
        if (drop) "#e0f2fe" else if (rowSelected) "#eff6ff" else "transparent"
      ),
      boxShadow <-- isDropTarget.map(if (_) "inset 0 0 0 1px #7dd3fc" else "none"),
      opacity <-- dragging.signal.map {
        case Some(SessionSelection(draggedId)) if draggedId == session.id => "0.45"
        case _                                                            => "1"
      },
      draggable <-- isEditing.map(!_),
      onDragStart --> { event =>
        if (editingSessionId.now() == session.id) ()
        else beginNodeDrag(event, SessionSelection(session.id))
      },
      onDragEnd --> { _ => clearDrag() },
      onDragOver --> { event => dragOverTarget(event, SessionDropTarget(session.id)) },
      onDrop --> { event => dropOnTarget(event, SessionDropTarget(session.id)) },
      onMouseEnter --> { _ => hovered.set(true) },
      onMouseLeave --> { _ => hovered.set(false) },
      treePrefix(level, isLast, ancestorContinues),
      sessionMarker,
      child <-- isEditing.map {
        case true  =>
          div(
            display.flex,
            alignItems.center,
            gap           := "4px",
            flexGrow      := "1",
            minWidth      := "0",
            paddingTop    := "3px",
            paddingBottom := "3px",
            input(
              idAttr       := SessionNameId,
              `type`       := "text",
              minWidth     := "0",
              flexGrow     := "1",
              padding      := "2px 5px",
              fontSize     := "11px",
              border       := "1px solid #93c5fd",
              borderRadius := "4px",
              outline      := "none",
              controlled(
                value <-- sessionDraft,
                onInput.mapToValue --> sessionDraft.set
              ),
              onKeyDown --> { event =>
                event.key match {
                  case "Enter"  =>
                    event.preventDefault()
                    event.stopPropagation()
                    saveSessionRename()
                  case "Escape" =>
                    event.preventDefault()
                    event.stopPropagation()
                    cancelSessionRename()
                  case _        => ()
                }
              }
            ),
            NeutralIconButton.render(
              "✓",
              "Save session name",
              saveSessionRename,
              disabledSignal = busy.signal
            ),
            NeutralIconButton.render(
              "×",
              "Cancel session rename",
              cancelSessionRename,
              disabledSignal = busy.signal
            )
          )
        case false =>
          div(
            display.flex,
            alignItems.center,
            minWidth := "0",
            flexGrow := "1",
            button(
              `type`          := "button",
              display.flex,
              alignItems.center,
              gap             := "6px",
              minWidth        := "0",
              flexGrow        := "1",
              padding         := "4px 0",
              cursor.pointer,
              textAlign.left,
              fontSize        := "11px",
              color <-- Signal.combine(isCurrent, Val(rowSelected)).map {
                case (current, selected) if current || selected => "#1d4ed8"
                case _                                          => "#475569"
              },
              fontWeight <-- isCurrent.map(current => if (current) "600" else "400"),
              backgroundColor := "transparent",
              border          := "none",
              disabled <-- busy.signal,
              title           := s"$displayPath — click to open · double-click or F2 to rename",
              span(displayTitle, minWidth := "0", overflow.hidden, textOverflow.ellipsis, whiteSpace.nowrap),
              onClick --> { _ =>
                if (moving.now().nonEmpty) setMoveTarget(session.folderId)
                else
                  handleNodeClick(
                    SessionSelection(session.id),
                    singleAction = () => onOpenSession(session.id),
                    doubleAction = () => beginSessionRename(session)
                  )
              }
            ),
            div(
              display.flex,
              alignItems.center,
              flexShrink      := "0",
              opacity <-- Signal
                .combine(hovered.signal, confirming)
                .map { case (isHovered, isConfirming) => if (isHovered || isConfirming) "1" else "0" },
              pointerEvents <-- Signal
                .combine(hovered.signal, confirming)
                .map { case (isHovered, isConfirming) => if (isHovered || isConfirming) "auto" else "none" },
              child <-- confirming.map {
                case true  =>
                  InlineDeleteConfirmation.render(
                    onConfirm = () => deleteSession(session.id, displayTitle),
                    onCancel = () =>
                      if (confirmDelete.now().contains(SessionSelection(session.id))) confirmDelete.set(None),
                    busySignal = busy.signal
                  )
                case false =>
                  actionIcon(
                    "×",
                    s"Delete session $displayTitle",
                    () => confirmDelete.set(Some(SessionSelection(session.id)))
                  )
              }
            )
          )
      }
    )
  }
  private def treePrefix(
      level: Int,
      isLast: Boolean,
      ancestorContinues: List[Boolean]
  ): HtmlElement =
    if (level <= 0) span("")
    else
      span(
        ancestorContinues.map(continues => if (continues) "│ " else "  ").mkString +
          (if (isLast) "└─" else "├─"),
        flexShrink               := "0",
        marginRight              := "5px",
        color                    := "#cbd5e1",
        fontFamily               := "ui-monospace, SFMono-Regular, Menlo, monospace",
        fontSize                 := "11px",
        lineHeight               := "1",
        whiteSpace               := "pre",
        styleProp("user-select") := "none"
      )
  private def expander(id: String, expanded: Boolean): HtmlElement                     =
    button(
      `type`          := "button",
      width           := "16px",
      height          := "20px",
      padding         := "0",
      flexShrink      := "0",
      cursor.pointer,
      fontSize        := "9px",
      lineHeight      := "1",
      color           := "#94a3b8",
      backgroundColor := "transparent",
      border          := "none",
      title           := (if (expanded) "Collapse folder" else "Expand folder"),
      onClick --> { event =>
        event.preventDefault()
        event.stopPropagation()
        toggleFolder(id)
      },
      if (expanded) "▾" else "▸"
    )
  private def sessionMarker: HtmlElement                                               =
    span(
      "-",
      width                    := "16px",
      height                   := "20px",
      flexShrink               := "0",
      display                  := "inline-flex",
      alignItems               := "center",
      justifyContent           := "center",
      fontSize                 := "9px",
      lineHeight               := "1",
      color                    := "#94a3b8",
      styleProp("user-select") := "none"
    )
  private def folderEditor(
      level: Int,
      isLast: Boolean,
      ancestorContinues: List[Boolean],
      hint: String,
      onSave: () => Unit,
      onCancel: () => Unit
  ): HtmlElement    =
    div(
      display.flex,
      alignItems.center,
      minHeight    := "26px",
      paddingLeft  := "4px",
      paddingRight := "6px",
      treePrefix(level, isLast, ancestorContinues),
      emptyNode,
      folderEditorFields(hint, onSave, onCancel)
    )
  private def folderEditorFields(
      hint: String,
      onSave: () => Unit,
      onCancel: () => Unit
  ): HtmlElement           =
    div(
      display.flex,
      alignItems.center,
      gap           := "4px",
      flexGrow      := "1",
      minWidth      := "0",
      paddingTop    := "3px",
      paddingBottom := "3px",
      input(
        idAttr       := NameId,
        `type`       := "text",
        placeholder  := hint,
        minWidth     := "0",
        flexGrow     := "1",
        padding      := "2px 5px",
        fontSize     := "11px",
        border       := "1px solid #93c5fd",
        borderRadius := "4px",
        outline      := "none",
        controlled(
          value <-- draft,
          onInput.mapToValue --> draft.set
        ),
        onKeyDown --> { event =>
          event.key match {
            case "Enter"  =>
              event.preventDefault()
              event.stopPropagation()
              onSave()
            case "Escape" =>
              event.preventDefault()
              event.stopPropagation()
              onCancel()
            case _        => ()
          }
        }
      ),
      NeutralIconButton.render("✓", "Save folder name", onSave, disabledSignal = busy.signal),
      NeutralIconButton.render("×", "Cancel folder rename", onCancel, disabledSignal = busy.signal)
    )
  private def actionIcon(label: String, tooltip: String, run: () => Unit): HtmlElement =
    button(
      `type`          := "button",
      width           := "20px",
      height          := "20px",
      padding         := "0",
      cursor.pointer,
      fontSize        := "11px",
      lineHeight      := "1",
      color           := "#94a3b8",
      backgroundColor := "transparent",
      border          := "none",
      title           := tooltip,
      onMouseEnter --> { event =>
        event.currentTarget.asInstanceOf[dom.HTMLElement].style.color = "#475569"
      },
      onMouseLeave --> { event =>
        event.currentTarget.asInstanceOf[dom.HTMLElement].style.color = "#94a3b8"
      },
      onClick --> { event =>
        event.stopPropagation()
        run()
      },
      label
    )
  private def actionText(label: String, tooltip: String, run: () => Unit): HtmlElement =
    button(
      `type`          := "button",
      flexShrink      := "0",
      padding         := "2px 5px",
      cursor.pointer,
      fontSize        := "9px",
      lineHeight      := "1.2",
      color           := "#64748b",
      backgroundColor := "transparent",
      border          := "1px solid #e2e8f0",
      borderRadius    := "4px",
      title           := tooltip,
      disabled <-- busy.signal,
      onClick --> { event =>
        event.stopPropagation()
        run()
      },
      label
    )
  private def currentFolderNow(): String                                               =
    validFolderContext(
      sessions.now().find(_.id == currentSessionId.now()).map(_.folderId).getOrElse(draftFolderId.now())
    )
  private def beginCreate(parentId: String): Unit                                      = {
    confirmDelete.set(None)
    creating.set(true)
    creatingParentId.set(validFolderContext(parentId))
    editing.set("")
    draft.set("")
    cancelSessionRename()
    focusNameEditor()
  }
  private def beginRename(folderId: String): Unit                                      =
    folders.now().find(_.id == folderId).foreach { folder =>
      confirmDelete.set(None)
      selected.set(FolderSelection(folder.id))
      editing.set(folder.id)
      creating.set(false)
      creatingParentId.set("")
      draft.set(folder.name)
      cancelSessionRename()
      focusNameEditor()
    }
  private def beginSessionRename(session: ChatSession): Unit                           = {
    confirmDelete.set(None)
    cancelEditing()
    selected.set(SessionSelection(session.id))
    editingSessionId.set(session.id)
    sessionDraft.set(Option(session.title).map(_.trim).getOrElse(""))
    js.timers.setTimeout(0) {
      Option(dom.document.getElementById(SessionNameId).asInstanceOf[dom.HTMLInputElement]).foreach { input =>
        input.focus()
        input.select()
      }
    }
  }
  private def cancelEditing(): Unit                                                    = {
    creating.set(false)
    creatingParentId.set("")
    editing.set("")
    draft.set("")
  }
  private def cancelSessionRename(): Unit                                              = {
    editingSessionId.set("")
    sessionDraft.set("")
  }
  private def saveNew(): Unit                                                          = {
    val name     = draft.now().trim
    val parentId = creatingParentId.now()
    if (name.nonEmpty && !busy.now()) {
      busy.set(true)
      onCreate(parentId, name)
        .map { folder =>
          collapsed.update(_ - parentId)
          selected.set(FolderSelection(folder.id))
          Toast.success(s"Created folder: $name")
          cancelEditing()
        }
        .recover { case error =>
          Toast.error(s"Create folder failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    }
  }
  private def saveRename(): Unit                                                       = {
    val folderId = editing.now()
    val name     = draft.now().trim
    if (folderId.nonEmpty && name.nonEmpty && !busy.now()) {
      busy.set(true)
      onRename(folderId, name)
        .map { _ =>
          Toast.success(s"Renamed folder to: $name")
          cancelEditing()
        }
        .recover { case error =>
          Toast.error(s"Rename folder failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    }
  }
  private def saveSessionRename(): Unit                                                = {
    val sessionId    = editingSessionId.now()
    val name         = sessionDraft.now().trim
    val currentTitle = sessions.now().find(_.id == sessionId).map(_.title).getOrElse("")
    if (sessionId.nonEmpty && name.nonEmpty && name != currentTitle && !busy.now()) {
      busy.set(true)
      onRenameSession(sessionId, name)
        .map { _ =>
          Toast.success(s"Renamed to: $name")
          cancelSessionRename()
        }
        .recover { case error =>
          Toast.error(s"Rename failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    } else if (name == currentTitle) cancelSessionRename()
  }
  private def deleteSession(sessionId: String, title: String): Unit                    =
    if (sessionId.nonEmpty && !busy.now()) {
      busy.set(true)
      onDeleteSession(sessionId)
        .map { _ =>
          if (confirmDelete.now().contains(SessionSelection(sessionId))) confirmDelete.set(None)
          if (selected.now() == SessionSelection(sessionId)) selected.set(NoSelection)
          Toast.success(s"Deleted session: $title")
          js.timers.setTimeout(0)(ensureTreeSelection())
        }
        .recover { case error =>
          Toast.error(s"Delete session failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    }
  private def deleteFolder(folderId: String, successMessage: String): Unit             =
    if (folderId.nonEmpty && !busy.now()) {
      val parentId = folders.now().find(_.id == folderId).map(_.parentId).getOrElse("")
      busy.set(true)
      onDelete(folderId)
        .map { _ =>
          if (confirmDelete.now().contains(FolderSelection(folderId))) confirmDelete.set(None)
          if (currentSessionId.now().isEmpty && draftFolderId.now() == folderId) draftFolderId.set(parentId)
          if (selected.now() == FolderSelection(folderId))
            selected.set(if (parentId.nonEmpty) FolderSelection(parentId) else NoSelection)
          Toast.success(successMessage)
          cancelEditing()
        }
        .recover { case error =>
          Toast.error(s"Delete folder failed: ${Option(error.getMessage).getOrElse("unknown error")}")
        }
        .foreach(_ => busy.set(false))
    }
  private def focusNameEditor(): Unit                                                  =
    js.timers.setTimeout(0) {
      Option(dom.document.getElementById(NameId).asInstanceOf[dom.HTMLInputElement]).foreach { input =>
        input.focus()
        input.select()
      }
    }
}
private[ui] object SessionFolderPanel {
  private val RootPath = "/"
  sealed trait TreeSelection
  case object NoSelection                        extends TreeSelection
  final case class FolderSelection(id: String)   extends TreeSelection
  final case class SessionSelection(id: String)  extends TreeSelection
  sealed trait DropTarget
  case object RootDropTarget                     extends DropTarget
  final case class FolderDropTarget(id: String)  extends DropTarget
  final case class SessionDropTarget(id: String) extends DropTarget
  sealed trait TreeNode
  final case class FolderTreeRow(
      folder: SessionFolder,
      level: Int,
      isLast: Boolean,
      ancestorContinues: List[Boolean]
  ) extends TreeNode
  final case class SessionTreeRow(
      session: ChatSession,
      level: Int,
      isLast: Boolean,
      ancestorContinues: List[Boolean]
  ) extends TreeNode
  def path(folders: List[SessionFolder], folderId: String): List[SessionFolder] = {
    val byId                                                     = folders.map(folder => folder.id -> folder).toMap
    def loop(id: String, seen: Set[String]): List[SessionFolder] =
      byId.get(id) match {
        case Some(folder) if !seen.contains(folder.id) =>
          loop(folder.parentId, seen + folder.id) :+ folder
        case _                                         =>
          Nil
      }
    if (folderId.isEmpty) Nil else loop(folderId, Set.empty)
  }
  def pathPrefix(path: List[SessionFolder]): String =
    if (path.isEmpty) RootPath else path.map(_.name).mkString("/", "/", "/")
  def tree(
      folders: List[SessionFolder],
      sessions: List[ChatSession],
      collapsed: Set[String]
  ): List[TreeNode] = {
    val foldersByParent  =
      folders.groupBy(_.parentId).view.mapValues(_.sortBy(folder => (folder.name, folder.id))).toMap
    val sessionsByFolder =
      sessions.groupBy(_.folderId).view.mapValues(_.sortBy(session => (-session.updatedAt, session.id))).toMap
    val seen             = scala.collection.mutable.Set.empty[String]
    def loop(
        parentId: String,
        level: Int,
        ancestorContinues: List[Boolean]
    ): List[TreeNode] = {
      val childFolders  =
        foldersByParent.getOrElse(parentId, Nil).filterNot(folder => seen.contains(folder.id))
      val childSessions = sessionsByFolder.getOrElse(parentId, Nil)
      val childCount    = childFolders.size + childSessions.size
      val folderNodes   = childFolders.zipWithIndex.flatMap { case (folder, index) =>
        seen += folder.id
        val isLast = index == childCount - 1
        FolderTreeRow(folder, level, isLast, ancestorContinues) ::
          (if (collapsed.contains(folder.id)) Nil
           else loop(folder.id, level + 1, ancestorContinues :+ !isLast))
      }
      val sessionNodes  = childSessions.zipWithIndex.map { case (session, index) =>
        val isLast = childFolders.size + index == childCount - 1
        SessionTreeRow(session, level, isLast, ancestorContinues)
      }
      folderNodes ::: sessionNodes
    }
    loop("", 0, Nil)
  }
  def canDelete(folders: List[SessionFolder], sessions: List[ChatSession], folderId: String): Boolean =
    folderId.nonEmpty &&
      folders.exists(_.id == folderId) &&
      !folders.exists(_.parentId == folderId) &&
      !sessions.exists(_.folderId == folderId)
}
