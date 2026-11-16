package com.just3bowls.model.chat

import com.just3bowls.api.chat.AgentModePolicy
import com.just3bowls.facade.{ChromeAPI, JsBridge}
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

/** Agent settings owned by one chat session. */
final case class SessionSettings(
    agentMode: String = AgentModePolicy.React,
    browserTools: Boolean = true
)

object SessionSettings {
  val Default: SessionSettings = SessionSettings()

  def normalize(settings: SessionSettings): SessionSettings =
    settings.copy(agentMode = AgentModePolicy.normalize(settings.agentMode))
}

object BrowserToolsPolicy {
  def effectiveForRun(sessionEnabled: Boolean, referencedTabCount: Int): Boolean =
    sessionEnabled || referencedTabCount > 0

  def isOneTurnOverride(sessionEnabled: Boolean, referencedTabCount: Int): Boolean =
    !sessionEnabled && referencedTabCount > 0
}

/**
 * Session settings use one storage document per session, so changing different sessions never requires reading or
 * rewriting a shared map.
 *
 * The legacy global browser-tools value and agent-mode map are read only while migrating an older installation.
 */
object SessionSettingsStore {
  val KeyPrefix                   = "thSessionSettings:v1:"
  val CurrentSchemaVersion        = 1
  val LegacyBrowserToolsKey       = "popCanChatBrowserTools"
  val LegacyAgentModesKey         = "thSessionAgentModes"
  val MigrationMarkerKey          = "thSessionSettingsMigration:v1"
  private val LegacyDisabledValue = "off"

  def key(sessionId: String): String            = s"$KeyPrefix$sessionId"
  def isSessionKey(storageKey: String): Boolean =
    storageKey.startsWith(KeyPrefix)

  def sessionIdFromKey(storageKey: String): Option[String] =
    Option(storageKey)
      .filter(isSessionKey)
      .map(_.stripPrefix(KeyPrefix))
      .filter(_.nonEmpty)

  def parseDocument(value: js.Any): Option[SessionSettings] =
    if (value == null || js.isUndefined(value)) None
    else
      try {
        val raw = JsBridge.asObject(value)
        JsBridge
          .intField(raw, "schemaVersion")
          .filter(_ == CurrentSchemaVersion)
          .map(_ =>
            SessionSettings
              .normalize(
                SessionSettings(
                  agentMode = JsBridge.stringField(raw, "agentMode").getOrElse(AgentModePolicy.React),
                  browserTools = JsBridge.booleanField(raw, "browserTools").getOrElse(true)
                )
              )
          )
      } catch {
        case _: Throwable => None
      }

  def toDocument(settings: SessionSettings): js.Any = {
    val normalized = SessionSettings.normalize(settings)
    JsBridge.obj(
      "schemaVersion" -> (CurrentSchemaVersion),
      "agentMode"     -> (normalized.agentMode),
      "browserTools"  -> (normalized.browserTools)
    )
  }

  def get(sessionId: String): Future[Option[SessionSettings]] =
    if (sessionId.trim.isEmpty) Future.successful(None)
    else read(sessionId).recover { case _ => None }

  /**
   * Missing session settings are materialized from the legacy values once, then behave like any other session document.
   */
  def load(sessionId: String): Future[SessionSettings] =
    if (sessionId.trim.isEmpty) Future.successful(SessionSettings.Default)
    else
      read(sessionId).flatMap {
        case Some(settings) => Future.successful(settings)
        case None           =>
          legacyDefaults(sessionId).flatMap { settings =>
            set(sessionId, settings).map(_ => settings).recover { case _ => settings }
          }
      }.recover { case _ => SessionSettings.Default }

  def ensure(sessionId: String, browserTools: Boolean, agentMode: String = AgentModePolicy.React): Future[Unit] =
    if (sessionId.trim.isEmpty) Future.successful(())
    else
      read(sessionId).flatMap {
        case Some(_) => Future.successful(())
        case None    => set(sessionId, SessionSettings(agentMode, browserTools))
      }

  def reset(sessionId: String, browserTools: Boolean, agentMode: String = AgentModePolicy.React): Future[Unit] =
    if (sessionId.trim.isEmpty) Future.successful(())
    else set(sessionId, SessionSettings(agentMode, browserTools))

  def saveBrowserTools(sessionId: String, enabled: Boolean): Future[Unit] =
    update(sessionId)(settings => settings.copy(browserTools = enabled))

  def saveAgentMode(sessionId: String, mode: String): Future[Unit] =
    update(sessionId)(settings => settings.copy(agentMode = AgentModePolicy.normalize(mode)))

  def clear(sessionId: String): Future[Unit] =
    if (sessionId.trim.isEmpty) Future.successful(())
    else ChromeAPI.storage.local.remove(js.Array(key(sessionId))).toFuture.map(_ => ())

  /**
   * Migrate every session present at upgrade time.
   *
   * Existing per-session documents always win. The marker and legacy keys are removed only after all missing documents
   * have been written successfully, so a failed migration remains retryable.
   */
  def migrate(sessionIds: List[String]): Future[Unit] = {
    val ids  = sessionIds.map(_.trim).filter(_.nonEmpty).distinct
    val keys = MigrationMarkerKey :: LegacyBrowserToolsKey :: LegacyAgentModesKey :: ids.map(key)
    ChromeAPI.storage.local
      .get(js.Array(keys*))
      .toFuture
      .flatMap { values =>
        val alreadyMigrated = values.get(MigrationMarkerKey).exists(JsBridge.asBoolean(_))
        val removeLegacy    =
          ChromeAPI.storage.local
            .remove(js.Array(LegacyBrowserToolsKey, LegacyAgentModesKey))
            .toFuture
            .map(_ => ())
        if (alreadyMigrated) {
          if (values.contains(LegacyBrowserToolsKey) || values.contains(LegacyAgentModesKey)) removeLegacy
          else Future.successful(())
        } else {
          val legacyBrowserTools = readLegacyBrowserTools(values.get(LegacyBrowserToolsKey))
          val legacyModes        = readLegacyModes(values.get(LegacyAgentModesKey))
          val missing            = ids.flatMap { sessionId =>
            if (values.get(key(sessionId)).flatMap(parseDocument).isDefined) None
            else
              Some(key(sessionId) -> toDocument(SessionSettings(
                agentMode = legacyModes.getOrElse(sessionId, AgentModePolicy.React),
                browserTools = legacyBrowserTools
              )))
          }
          val writes             = js.Dictionary[js.Any](missing*)
          val persist            =
            if (writes.isEmpty) Future.successful(())
            else ChromeAPI.storage.local.set(writes).toFuture.map(_ => ())
          persist
            .flatMap(_ => removeLegacy)
            .flatMap(_ =>
              ChromeAPI.storage.local.set(js.Dictionary[js.Any](MigrationMarkerKey -> true)).toFuture.map(_ => ())
            )
        }
      }
      .recover { case _ => () }
  }

  private def update(sessionId: String)(change: SessionSettings => SessionSettings): Future[Unit] =
    if (sessionId.trim.isEmpty) Future.successful(())
    else
      read(sessionId)
        .flatMap {
          case Some(settings) => Future.successful(settings)
          case None           => legacyDefaults(sessionId)
        }
        .flatMap(settings => set(sessionId, change(settings)))

  private def read(sessionId: String): Future[Option[SessionSettings]] =
    ChromeAPI.storage.local
      .get(key(sessionId))
      .toFuture
      .map(result => result.get(key(sessionId)).flatMap(parseDocument))

  private def set(sessionId: String, settings: SessionSettings): Future[Unit] =
    ChromeAPI.storage.local
      .set(js.Dictionary[js.Any](key(sessionId) -> toDocument(settings)))
      .toFuture
      .map(_ => ())

  private def legacyDefaults(sessionId: String): Future[SessionSettings] =
    ChromeAPI.storage.local
      .get(js.Array(LegacyBrowserToolsKey, LegacyAgentModesKey))
      .toFuture
      .map { values =>
        legacySettingsFor(sessionId, values.get(LegacyBrowserToolsKey), values.get(LegacyAgentModesKey))
      }
      .recover { case _ => SessionSettings.Default }

  private[chat] def legacySettingsFor(
      sessionId: String,
      browserToolsValue: Option[js.Any],
      agentModesValue: Option[js.Any]
  ): SessionSettings =
    SessionSettings(
      agentMode = readLegacyModes(agentModesValue).getOrElse(sessionId, AgentModePolicy.React),
      browserTools = readLegacyBrowserTools(browserToolsValue)
    )

  private def readLegacyBrowserTools(value: Option[js.Any]): Boolean =
    value
      .map(_.toString.trim.toLowerCase)
      .forall(_ != LegacyDisabledValue)

  private def readLegacyModes(value: Option[js.Any]): Map[String, String] =
    value
      .filter(raw => raw != null && !js.isUndefined(raw))
      .map(raw =>
        JsBridge.asDictionary(raw).toMap.map { case (sessionId, mode) =>
          sessionId -> AgentModePolicy.normalize(mode.toString)
        }
      )
      .getOrElse(Map.empty)
}
