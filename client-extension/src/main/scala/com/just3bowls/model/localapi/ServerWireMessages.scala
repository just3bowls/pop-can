package com.just3bowls.model.localapi

import com.just3bowls.facade.JsBridge
import scala.scalajs.js

/**
 * native messaging 出站消息（扩展 → host）的构造器。
 *
 * 与 WebSocket 时代最大的差别：`chrome.runtime.Port.postMessage` **自己负责 JSON 序列化**，所以要传**对象**而不是 预序列化好的字符串； 另外不再有 `hello` /
 * `ping` —— 连接是否活着看 `port.onDisconnect`，不需要应用层心跳。
 */
object ServerWireMessages:
  def clientResponseHead(
      requestId: String,
      status: Int,
      contentType: String
  ): js.Object        =
    JsBridge.obj(
      "type"        -> "client_response_head",
      "requestId"   -> requestId,
      "status"      -> status,
      "contentType" -> contentType
    )
  def clientResponseChunk(requestId: String, data: String): js.Object =
    JsBridge.obj("type" -> "client_response_chunk", "requestId" -> requestId, "data" -> data)

  /** 让 host 自己退出（Stop / 重连前清理旧 host）：不依赖"Chrome 会在 disconnect 时回收 host"这个假设 —— 实测它不回收。 */
  def shutdown: js.Object                             =
    JsBridge.obj("type" -> "shutdown")
  def clientResponseEnd(requestId: String): js.Object =
    JsBridge.obj("type" -> "client_response_end", "requestId" -> requestId)
