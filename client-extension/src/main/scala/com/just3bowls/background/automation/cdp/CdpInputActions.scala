package com.just3bowls.background.automation.cdp

import com.just3bowls.facade.JsBridge
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

/** Typed input replacement, background interaction checks, and composer cleanup. */
final private[background] class CdpInputActions(
    evaluate: String => Future[js.Any],
    cdp: (String, js.UndefOr[js.Object]) => Future[js.Object],
    delay: Int => Future[Unit]
) {
  private val HiddenInteractionTimeoutMs = 5000
  private val HiddenRetryIntervalMs      = 250

  /**
   * 往 `[data-th-role="input"]` 里写文本，按元素类型选机制： input/textarea 走原生 setter；contenteditable 走可信 `Input.insertText` 替换选区。
   */
  def fill(prompt: String): Future[String] =
    evaluate(CdpFlowExpressions.inputKind).map(_.toString).flatMap {
      case "text"            =>
        evaluate(CdpFlowExpressions.fillInput(prompt)).map(_.toString).flatMap { raw =>
          val parsed = js.JSON.parse(raw)
          if (JsBridge.undefinedBoolean(parsed.ok).getOrElse(false)) verify(prompt)
          else
            Future.failed(new Exception(
              s"Could not fill the input (${JsBridge.undefinedString(parsed.reason).getOrElse("unknown")}): " +
                s"expected=${js.JSON.stringify(prompt)}"
            ))
        }
      case "contenteditable" =>
        evaluate(CdpFlowExpressions.selectAllInInput)
          .flatMap(_ => cdp("Input.insertText", JsBridge.obj("text" -> (prompt))))
          .flatMap(_ => verify(prompt))
      case other             =>
        Future.failed(new Exception(s"Could not fill the input ($other): expected=${js.JSON.stringify(prompt)}"))
    }

  /** Slate 在 hidden 时会忽略程序化输入；后台模式依赖 CDP 的焦点/生命周期模拟。 */
  def ensureInteractive(tabId: Int): Future[Unit] = {
    val deadline                               = js.Date.now() + HiddenInteractionTimeoutMs
    def pageState(): Future[(String, Boolean)] =
      evaluate(
        """
          |(function(){
          |  return JSON.stringify({
          |    visibility: document.visibilityState || 'unknown',
          |    hasFocus: !!document.hasFocus()
          |  });
          |})()
          |""".stripMargin
      )
        .map { raw =>
          val parsed = js.JSON.parse(raw.toString)
          (
            JsBridge.undefinedString(parsed.visibility).getOrElse("unknown"),
            JsBridge.undefinedBoolean(parsed.hasFocus).getOrElse(false)
          )
        }
        .recover { case _ => ("unknown", false) }
    def loop(): Future[Unit]                   =
      pageState().flatMap { case (visibility, hasFocus) =>
        if (visibility == "visible" && hasFocus) Future.successful(())
        else if (js.Date.now() >= deadline)
          Future.failed(new Exception(
            s"Provider tab could not be made interactive in the background " +
              s"(tab=$tabId visibility=$visibility hasFocus=$hasFocus): CDP focus/lifecycle emulation did not take effect"
          ))
        else delay(HiddenRetryIntervalMs).flatMap(_ => loop())
      }
    loop()
  }

  /** 失败收口时只清掉确实属于本轮的那段文字。 */
  def clear(prompt: String): Future[Unit] =
    evaluate(CdpFlowExpressions.clearComposer(prompt)).map(_ => ()).recover { case _ => () }

  /** 读回校验：编辑器可能异步渲染，最多读 3 次再判失败。 */
  private def verify(prompt: String): Future[String] = {
    def attempt(remaining: Int): Future[String] =
      delay(250).flatMap { _ =>
        evaluate(CdpFlowExpressions.inputState(prompt)).map(_.toString).flatMap { raw =>
          val parsed = js.JSON.parse(raw)
          val ok     = JsBridge.undefinedBoolean(parsed.ok).getOrElse(false)
          val value  = JsBridge.undefinedString(parsed.value).getOrElse("")
          val inside = JsBridge.undefinedBoolean(parsed.inside).getOrElse(false)
          if (ok) Future.successful(value)
          else if (remaining > 1) attempt(remaining - 1)
          else {
            val expected   = JsBridge.undefinedString(parsed.expected).getOrElse(prompt)
            val normalized = JsBridge.undefinedString(parsed.normalized).getOrElse("")
            val reason     =
              if (!inside) "input is not focused"
              else if (expected.nonEmpty && normalized.contains(expected + expected)) "text was doubled"
              else "text mismatch"
            val active     =
              JsBridge.undefinedString(parsed.activeTag).getOrElse("") +
                Option(JsBridge.undefinedString(parsed.activeRole).toOption.getOrElse(""))
                  .filter(_.nonEmpty)
                  .map(role => s"[role=$role]")
                  .getOrElse("")
            Future.failed(new Exception(
              s"Input text verification failed ($reason) expected=${js.JSON.stringify(expected)} " +
                s"actual=${js.JSON.stringify(value)} inside=$inside active=${js.JSON.stringify(active)}"
            ))
          }
        }
      }
    attempt(3)
  }
}
