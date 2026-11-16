package com.just3bowls.model.chat

class ChatStoreSessionTreeSpec extends munit.FunSuite {
  private def session(id: String, folderId: String): ChatSession =
    ChatSession(id, id, "default", "deepseek", 1.0, 1.0, folderId = folderId)

  private def folder(id: String, parentId: String): SessionFolder =
    SessionFolder(id, id, parentId, 1.0, 1.0)

  test("moves sessions with a missing folder back to the root") {
    val tree = ChatStore.repairSessionTree(
      sessions = List(session("session-1", "missing-folder")),
      folders = Nil
    )

    assertEquals(tree.sessions.map(_.folderId), List(""))
    assertEquals(tree.repairedSessionFolders, 1)
    assertEquals(tree.changedSessions.map(_.id), List("session-1"))
  }

  test("keeps sessions whose folder still exists") {
    val tree = ChatStore.repairSessionTree(
      sessions = List(session("session-1", "folder-1")),
      folders = List(folder("folder-1", ""))
    )

    assertEquals(tree.sessions.map(_.folderId), List("folder-1"))
    assertEquals(tree.repairedSessionFolders, 0)
    assertEquals(tree.changedSessions, Nil)
  }

  test("promotes folders with a missing parent to the root") {
    val tree = ChatStore.repairSessionTree(
      sessions = Nil,
      folders = List(folder("folder-1", "missing-parent"))
    )

    assertEquals(tree.folders.map(_.parentId), List(""))
    assertEquals(tree.repairedFolderParents, 1)
    assertEquals(tree.changedFolders.map(_.id), List("folder-1"))
  }

  test("promotes folder cycles to the root so they remain reachable") {
    val tree = ChatStore.repairSessionTree(
      sessions = Nil,
      folders = List(
        folder("folder-a", "folder-b"),
        folder("folder-b", "folder-a")
      )
    )

    assert(tree.folders.forall(_.parentId.isEmpty))
    assertEquals(tree.repairedFolderParents, 2)
  }
}
