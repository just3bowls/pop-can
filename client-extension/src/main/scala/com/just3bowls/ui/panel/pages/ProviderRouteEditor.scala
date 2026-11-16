package com.just3bowls.ui.panel.pages

import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.model.provider.ParserProviderInfo
import com.just3bowls.ui.components.Toast

import com.raquo.laminar.api.L._
import org.scalajs.dom

import scala.scalajs.js

final private[ui] case class ProviderRouteDraft(
    provider: String,
    weight: Int = 1,
    promptMode: String = RoutePromptMode.Inline
)

private[ui] object ProviderRoutePolicy {
  def normalize(routes: List[ProviderRouteDraft]): List[ProviderRouteDraft] =
    routes
      .filter(route => route.provider.trim.nonEmpty)
      .map(route =>
        ProviderRouteDraft(
          provider = route.provider.trim,
          weight = math.max(1, route.weight),
          promptMode = RoutePromptMode.normalize(route.promptMode)
        )
      )
      .distinctBy(_.provider)

  def canAdd(routes: List[ProviderRouteDraft], maxProviders: Option[Int]): Boolean =
    maxProviders.forall(limit => routes.size < limit)

  def nextAvailable(routes: List[ProviderRouteDraft], providers: List[ParserProviderInfo]): Option[String] = {
    val occupied = routes.map(_.provider).toSet
    providers
      .filter(_.status == "active")
      .find(provider => !occupied.contains(provider.id))
      .map(_.id)
  }

  def commit(
      routes: List[ProviderRouteDraft],
      index: Int,
      candidate: ProviderRouteDraft
  ): Either[String, List[ProviderRouteDraft]] = {
    val normalized = normalize(routes)
    val provider   = candidate.provider.trim
    if (provider.isEmpty) Left("Select a provider")
    else if (index < 0 || index >= normalized.length) Left("Provider route no longer exists")
    else if (
      normalized.zipWithIndex.exists { case (route, routeIndex) =>
        routeIndex != index && route.provider == provider
      }
    )
      Left(s"$provider is already configured")
    else
      Right(
        normalized.updated(
          index,
          ProviderRouteDraft(
            provider = provider,
            weight = math.max(1, candidate.weight),
            promptMode = RoutePromptMode.normalize(candidate.promptMode)
          )
        )
      )
  }

  def remove(routes: List[ProviderRouteDraft], index: Int): List[ProviderRouteDraft] =
    normalize(routes).zipWithIndex.filterNot(_._2 == index).map(_._1)

  def reorder(routes: List[ProviderRouteDraft], from: Int, boundary: Int): List[ProviderRouteDraft] = {
    val normalized = normalize(routes)
    if (from < 0 || from >= normalized.length) normalized
    else {
      val bounded  = math.max(0, math.min(boundary, normalized.length))
      val without  = normalized.patch(from, Nil, 1)
      val insertAt = math.max(0, if (from < bounded) bounded - 1 else bounded)
      without.patch(insertAt, List(normalized(from)), 0)
    }
  }
}

private[ui] object ProviderRouteEditor {
  final class State private[ProviderRouteEditor] {
    private[ProviderRouteEditor] val editingIndexVar = Var(Option.empty[Int])
    private[ProviderRouteEditor] val editProviderVar = Var("")
    private[ProviderRouteEditor] val editWeightVar   = Var("1")
    private[ProviderRouteEditor] val editPromptVar   = Var(RoutePromptMode.Inline)
  }

  def state(): State = new State()

  def render(
      state: State,
      routes: Signal[List[ProviderRouteDraft]],
      routesNow: () => List[ProviderRouteDraft],
      providers: Signal[List[ParserProviderInfo]],
      providersNow: () => List[ParserProviderInfo],
      maxProviders: Option[Int],
      scopeLabel: String,
      onSave: List[ProviderRouteDraft] => Unit
  ): HtmlElement = {
    val editingIndexVar  = state.editingIndexVar
    val editProviderVar  = state.editProviderVar
    val editWeightVar    = state.editWeightVar
    val editPromptVar    = state.editPromptVar
    val draggingIndexVar = Var(Option.empty[Int])
    val dropBoundaryVar  = Var(Option.empty[Int])

    def currentRoutes(): List[ProviderRouteDraft] =
      ProviderRoutePolicy.normalize(routesNow())

    def currentProviders(): List[ParserProviderInfo] =
      providersNow().filter(_.status == "active")

    def clearDrag(): Unit = {
      draggingIndexVar.set(None)
      dropBoundaryVar.set(None)
    }

    def beginEdit(index: Int, route: ProviderRouteDraft): Unit = {
      editingIndexVar.set(Some(index))
      editProviderVar.set(route.provider)
      editWeightVar.set(route.weight.toString)
      editPromptVar.set(RoutePromptMode.normalize(route.promptMode))
    }

    def cancelEdit(): Unit = editingIndexVar.set(None)

    def commitEdit(index: Int): Unit = {
      val candidate = ProviderRouteDraft(
        provider = editProviderVar.now(),
        weight = editWeightVar.now().trim.toIntOption.getOrElse(1),
        promptMode = editPromptVar.now()
      )
      ProviderRoutePolicy.commit(currentRoutes(), index, candidate) match {
        case Left(error)    => Toast.error(s"$error for $scopeLabel.")
        case Right(updated) =>
          if (updated != currentRoutes()) onSave(updated)
          editingIndexVar.set(None)
      }
    }

    def addRoute(): Unit = {
      val current = currentRoutes()
      if (!ProviderRoutePolicy.canAdd(current, maxProviders))
        Toast.info(
          s"$scopeLabel supports up to ${maxProviders.getOrElse(current.size)} providers. " +
            "Remove one before adding another."
        )
      else
        ProviderRoutePolicy.nextAvailable(current, currentProviders()).foreach { provider =>
          val route = ProviderRouteDraft(provider)
          val next  = current :+ route
          onSave(next)
          beginEdit(next.size - 1, route)
        }
    }

    def chip(route: ProviderRouteDraft, index: Int): HtmlElement =
      div(
        display  := "inline-flex",
        alignItems.center,
        position := "relative",
        child <-- editingIndexVar.signal.map {
          case Some(current) if current == index => inlineEditor(route, index)
          case _                                 => emptyNode
        },
        chipBody(route, index)
      )

    def chipBody(route: ProviderRouteDraft, index: Int): HtmlElement =
      div(
        display         := "inline-flex",
        alignItems.center,
        gap             := "4px",
        padding         := "3px 7px",
        border          := "1px solid #dbe3ee",
        borderRadius    := "5px",
        backgroundColor := "#ffffff",
        fontSize        := "11px",
        cursor.pointer,
        title           := "Click to edit this provider",
        opacity <-- draggingIndexVar.signal.map {
          case Some(from) if from == index => "0.45"
          case _                           => "1"
        },
        boxShadow <-- dropBoundaryVar.signal.map {
          case Some(at) if at == index     => "inset 2px 0 0 #2563eb"
          case Some(at) if at == index + 1 => "inset -2px 0 0 #2563eb"
          case _                           => "none"
        },
        span(
          "⋮⋮",
          cursor                   := "grab",
          styleProp("user-select") := "none",
          color                    := "#9ca3af",
          fontSize                 := "10px",
          lineHeight               := "1",
          flexShrink               := "0",
          draggable                := true,
          onDragStart --> { event =>
            event.stopPropagation()
            draggingIndexVar.set(Some(index))
            dropBoundaryVar.set(Some(index))
            event.dataTransfer.effectAllowed = "move".asInstanceOf[dom.DataTransferEffectAllowedKind]
            event.dataTransfer.setData("text/plain", index.toString)
          },
          onDragEnd --> { _ => clearDrag() },
          onClick --> { event => event.stopPropagation() }
        ),
        button(
          `type`                   := "button",
          padding                  := "0",
          border                   := "none",
          backgroundColor          := "transparent",
          color                    := "#111827",
          cursor.pointer,
          fontFamily               := "inherit",
          fontSize                 := "inherit",
          title                    := "Click to edit this provider",
          onClick --> { event =>
            event.stopPropagation()
            beginEdit(index, route)
          },
          onKeyDown --> { event =>
            if (event.key == "Enter") {
              event.preventDefault()
              beginEdit(index, route)
            }
          },
          providerLabel(route)
        ),
        span(
          "✕",
          fontSize                 := "10px",
          color                    := "#64748b",
          title                    := "Remove",
          onClick --> { event =>
            event.stopPropagation()
            onSave(ProviderRoutePolicy.remove(currentRoutes(), index))
            editingIndexVar.set(None)
          }
        ),
        onDragOver --> { event =>
          draggingIndexVar.now().foreach { _ =>
            event.preventDefault()
            val rect   = event.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect()
            val before = event.clientX < rect.left + rect.width / 2.0
            dropBoundaryVar.set(Some(if (before) index else index + 1))
            event.dataTransfer.dropEffect = "move".asInstanceOf[dom.DataTransferDropEffectKind]
          }
        },
        onDrop --> { event =>
          event.preventDefault()
          draggingIndexVar.now().foreach { from =>
            val boundary = dropBoundaryVar.now().getOrElse(index)
            clearDrag()
            val next     = ProviderRoutePolicy.reorder(currentRoutes(), from, boundary)
            if (next != currentRoutes()) onSave(next)
          }
        },
        onClick --> { _ => beginEdit(index, route) }
      )

    def inlineEditor(route: ProviderRouteDraft, index: Int): HtmlElement = {
      var outsideListener: js.UndefOr[js.Function1[dom.MouseEvent, Unit]]         = js.undefined
      div(
        display         := "inline-flex",
        alignItems.center,
        gap             := "4px",
        width           := "max-content",
        maxWidth        := "min(390px, calc(100vw - 32px))",
        padding         := "3px 7px",
        border          := "1px solid #93c5fd",
        borderRadius    := "5px",
        backgroundColor := "#ffffff",
        boxShadow       := "0 4px 12px rgba(15,23,42,0.12)",
        fontSize        := "11px",
        position        := "absolute",
        bottom          := "calc(100% + 4px)",
        left            := "0",
        zIndex          := 300,
        tabIndex        := 0,
        onMountCallback { ctx =>
          val editor = ctx.thisNode.ref
          outsideListener = { event =>
            val target = event.target.asInstanceOf[dom.Node]
            if (!editor.contains(target) && editingIndexVar.now().contains(index))
              commitEdit(index)
          }
          dom.document.addEventListener("mousedown", outsideListener.get)
        },
        onUnmountCallback { _ =>
          outsideListener.foreach(dom.document.removeEventListener("mousedown", _))
          outsideListener = js.undefined
        },
        onKeyDown --> { event =>
          if (event.key == "Escape") {
            event.preventDefault()
            cancelEdit()
          } else if (event.key == "Enter" && event.target.asInstanceOf[dom.HTMLElement].tagName != "SELECT") {
            event.preventDefault()
            commitEdit(index)
          }
        },
        select(
          width                     := "auto",
          minWidth                  := "48px",
          maxWidth                  := "150px",
          styleProp("field-sizing") := "content",
          padding                   := "0 14px 0 0",
          border                    := "none",
          backgroundColor           := "transparent",
          color                     := "#111827",
          outline                   := "none",
          cursor.pointer,
          fontSize                  := "11px",
          value <-- editProviderVar.signal,
          onChange.mapToValue --> editProviderVar.set,
          children <-- providers.combineWith(routes).map { case (all, currentRoutes) =>
            val active    = all.filter(_.status == "active")
            val occupied  = currentRoutes.zipWithIndex.collect {
              case (other, routeIndex) if routeIndex != index => other.provider
            }.toSet
            val available = active.filterNot(provider => occupied.contains(provider.id))
            val items     = available.map(provider =>
              option(value := provider.id, provider.name, selected := (provider.id == route.provider))
            )
            if (route.provider.nonEmpty && !available.exists(_.id == route.provider)) {
              val suffix = if (active.exists(_.id == route.provider)) "in use" else "missing"
              option(value := route.provider, s"${route.provider} ($suffix)", selected := true) :: items
            } else items
          }
        ),
        span("×", fontSize          := "10px", color := "#9ca3af", flexShrink := "0"),
        input(
          `type`                    := "number",
          minAttr                   := "1",
          width                     := "36px",
          padding                   := "1px 2px",
          boxSizing                 := "border-box",
          border                    := "none",
          borderRadius              := "3px",
          backgroundColor           := "#f8fafc",
          color                     := "#111827",
          fontSize                  := "11px",
          textAlign                 := "center",
          value <-- editWeightVar.signal,
          onInput.mapToValue --> editWeightVar.set
        ),
        span("·", fontSize          := "10px", color := "#9ca3af", flexShrink := "0"),
        select(
          width                     := "76px",
          padding                   := "0 12px 0 0",
          border                    := "none",
          backgroundColor           := "transparent",
          color                     := "#111827",
          outline                   := "none",
          cursor.pointer,
          fontSize                  := "11px",
          title                     := "inline injects the prompt directly; file uploads it as a text attachment.",
          value <-- editPromptVar.signal,
          onChange.mapToValue --> editPromptVar.set,
          option(value := RoutePromptMode.Inline, "inline"),
          option(value := RoutePromptMode.File, "file")
        )
      )
    }

    div(
      display.flex,
      alignItems.center,
      flexWrap.wrap,
      gap       := "6px",
      minHeight := "24px",
      children <-- routes.combineWith(providers).map { case (rawRoutes, allProviders) =>
        val current       = ProviderRoutePolicy.normalize(rawRoutes)
        val active        = allProviders.filter(_.status == "active")
        val atLimit       = !ProviderRoutePolicy.canAdd(current, maxProviders)
        val nextAvailable = ProviderRoutePolicy.nextAvailable(current, active)
        val chips         = current.zipWithIndex.map { case (route, index) => chip(route, index) }
        val empty         =
          if (current.isEmpty) List(span("(not set)", fontSize := "11px", color := "#9ca3af")) else Nil
        val unavailable   =
          if (active.isEmpty)
            List(span(
              "No active providers available (is the server running?)",
              fontSize := "11px",
              color    := "#9ca3af"
            ))
          else Nil
        chips ::: empty ::: unavailable ::: List(
          ConfigPanel.configButton(
            span("+ Add provider"),
            () => addRoute(),
            disabledSignal = Val(nextAvailable.isEmpty && !atLimit),
            spacing = "3px 7px"
          )
        )
      }
    )
  }

  private def providerLabel(route: ProviderRouteDraft): String =
    (if (route.weight > 1) s"${route.provider} ×${route.weight}" else route.provider) +
      s" · ${RoutePromptMode.normalize(route.promptMode)}"
}
