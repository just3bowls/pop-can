package com.just3bowls.ui.panel.pages

import com.just3bowls.api.provider.RoutePromptMode
import com.just3bowls.model.provider.ParserProviderInfo

import munit.FunSuite

class ProviderRoutePolicySpec extends FunSuite {
  private def provider(id: String, status: String = "active"): ParserProviderInfo =
    ParserProviderInfo(id = id, name = id, status = status, parserRule = None)

  test("normalizes provider routes without allowing duplicate providers") {
    val routes = ProviderRoutePolicy.normalize(List(
      ProviderRouteDraft(" one ", 0, " FILE "),
      ProviderRouteDraft("two", 2),
      ProviderRouteDraft("one", 3, RoutePromptMode.File)
    ))

    assertEquals(
      routes,
      List(
        ProviderRouteDraft("one", 1, RoutePromptMode.File),
        ProviderRouteDraft("two", 2, RoutePromptMode.Inline)
      )
    )
  }

  test("nextAvailable excludes occupied and inactive providers") {
    val routes = List(ProviderRouteDraft("one"))
    assertEquals(
      ProviderRoutePolicy.nextAvailable(
        routes,
        List(provider("one"), provider("two"), provider("draft", "draft"), provider("three"))
      ),
      Some("two")
    )
  }

  test("commit rejects duplicate providers and normalizes prompt mode") {
    val routes = List(ProviderRouteDraft("one"), ProviderRouteDraft("two"))

    assert(ProviderRoutePolicy.commit(routes, 0, ProviderRouteDraft("two")).isLeft)
    assertEquals(
      ProviderRoutePolicy.commit(routes, 0, ProviderRouteDraft("one", 0, "FILE")),
      Right(List(ProviderRouteDraft("one", 1, RoutePromptMode.File), ProviderRouteDraft("two")))
    )
  }

  test("add limit is optional and reorder/remove keep normalized order") {
    val routes = List(ProviderRouteDraft("one"), ProviderRouteDraft("two"), ProviderRouteDraft("three"))

    assert(ProviderRoutePolicy.canAdd(routes, None))
    assert(!ProviderRoutePolicy.canAdd(routes, Some(3)))
    assertEquals(ProviderRoutePolicy.remove(routes, 1).map(_.provider), List("one", "three"))
    assertEquals(ProviderRoutePolicy.reorder(routes, 2, 0).map(_.provider), List("three", "one", "two"))
  }
}
