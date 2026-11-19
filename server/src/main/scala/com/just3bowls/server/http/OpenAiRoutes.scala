package com.just3bowls.server.http

import com.just3bowls.server.native.BrowserWorkerBridge

import zio.http._

private[server] object OpenAiRoutes {

  def routes(bridge: BrowserWorkerBridge): Routes[Any, Response] =
    Routes(
      Method.GET / "v1" / "models"                -> handler { (request: Request) =>
        ThinHttpProxy.models(bridge, request)
      },
      Method.POST / "v1" / "chat" / "completions" -> handler { (request: Request) =>
        ThinHttpProxy.chat(bridge, request)
      }
    ) @@ Middleware.cors
}
