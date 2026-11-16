package com.just3bowls.model.chat

import com.just3bowls.facade.{LangGraphAnnotationModule, LangGraphNS, StateGraph, StateGraphBuilder}

import scala.scalajs.js

object LangGraph {
  private val annotationModule = LangGraphNS.Annotation.asInstanceOf[LangGraphAnnotationModule]
  private val annotationFn     = LangGraphNS.Annotation.asInstanceOf[js.Function1[js.Object, js.Any]]

  def channel(options: js.Object): js.Any = annotationFn(options)

  def root(fields: js.Dictionary[js.Any]): js.Object = annotationModule.Root(fields)

  def graph(state: js.Object): StateGraphBuilder = new StateGraph(state)

  def START: js.Any = LangGraphNS.START
  def END: js.Any   = LangGraphNS.END
}
