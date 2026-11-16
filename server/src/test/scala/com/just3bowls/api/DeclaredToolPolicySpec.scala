package com.just3bowls.api

import com.just3bowls.api.marker.DeclaredToolPolicy
import zio.test._

object DeclaredToolPolicySpec extends ZIOSpecDefault {
  def spec: Spec[Any, Any] = suite("DeclaredToolPolicy")(
    test("rejects every tool call when no tools are declared") {
      val violations = DeclaredToolPolicy.validate(
        content = "",
        toolCalls = List("search" -> """{"queries":["ai news"]}"""),
        declaredTools = Set.empty
      )

      assertTrue(
        violations.exists(_.code == DeclaredToolPolicy.UndeclaredTool),
        violations.exists(_.message.contains("No tools are declared"))
      )
    },
    test("accepts declared browser tools") {
      val violations = DeclaredToolPolicy.validate(
        content = "",
        toolCalls = List("browser_search" -> """{"query":"ai news"}"""),
        declaredTools = Set("browser_search", "browser_extract")
      )

      assertTrue(violations.isEmpty)
    },
    test("accepts arbitrary caller-declared tools") {
      val violations = DeclaredToolPolicy.validate(
        content = "",
        toolCalls = List("lookup_order" -> """{"id":"42"}"""),
        declaredTools = Set("lookup_order")
      )

      assertTrue(violations.isEmpty)
    },
    test("rejects any undeclared tool in a mixed call set") {
      val violations = DeclaredToolPolicy.validate(
        content = "",
        toolCalls = List(
          "browser_search" -> """{"query":"ai news"}""",
          "search"         -> """{"queries":["ai news"]}"""
        ),
        declaredTools = Set("browser_search")
      )

      assertTrue(
        violations.count(_.code == DeclaredToolPolicy.UndeclaredTool) == 1,
        violations.head.message.contains("search")
      )
    },
    test("rejects an empty final answer without tool calls") {
      val violations = DeclaredToolPolicy.validate(
        content = "  ",
        toolCalls = Nil,
        declaredTools = Set.empty
      )

      assertTrue(violations.exists(_.code == DeclaredToolPolicy.EmptyAnswer))
    },
    test("repair directive names the exact allowed tools") {
      val directive = com.just3bowls.api.marker.ProtocolRepair.directive(
        List("The model called tool \"search\", but it is not declared."),
        Set("browser_search", "browser_extract")
      )

      assertTrue(
        directive.contains("browser_extract"),
        directive.contains("browser_search"),
        directive.contains("Do not call anything else")
      )
    },
    test("tools-off repair directive forbids tool calls") {
      val directive = com.just3bowls.api.marker.ProtocolRepair.directive(
        List("The model called tool \"search\", but it is not declared."),
        Set.empty
      )

      assertTrue(
        directive.contains("declares no tools"),
        directive.contains("Do not emit tool_calls"),
        directive.contains("run internally")
      )
    }
  )
}
