package com.just3bowls.api.chat

/** A user-selected browser tab exposed to one chat run under a stable short reference such as T1. */
final case class BrowserTabRef(
    ref: String,
    tabId: Int,
    title: String,
    url: String,
    windowId: Int = 0,
    index: Int = 0,
    pinned: Boolean = false,
    groupId: Int = -1,
    groupTitle: String = "",
    groupColor: String = ""
)
