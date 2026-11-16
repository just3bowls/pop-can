package com.just3bowls.background.automation.browser

import com.just3bowls.facade.{AxNode, ExtractCandidates, DebuggerSession}
import com.just3bowls.model.diagnostics.PopCanLog
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.concurrent.Future
import scala.scalajs.js

/** Reads page text through metadata, readability, AX-region scoring, then full-text fallback. */
final private[background] class BrowserExtractor(
    evaluate: (DebuggerSession, String) => Future[js.Any],
    fullAxTree: DebuggerSession => Future[List[AxNode]],
    collectRawNodes: (List[AxNode], Int) => List[AxFilter.RawNode]
) {
  private val MaxRawNodes            = 10000
  private val ExtractMinChars        = 800
  private val ExtractRegionMinChars  = 400
  private val ExtractRegionsChars    = 20000
  private val ExtractMaxLinkDensity  = 0.35
  private val ExtractListPageRecords = 5

  def extract(target: DebuggerSession, urlHint: String, titleHint: String, goal: String): Future[String] =
    for {
      _          <- ensureLibraries(target)
      raw        <- evaluate(target, BrowserPageScripts.ExtractPipeline)
      candidates <- Future.successful(raw.asInstanceOf[ExtractCandidates])
      text       <- choose(target, urlHint, titleHint, goal, candidates)
    } yield text

  private def libraryProbe(target: DebuggerSession): Future[String] =
    evaluate(target, BrowserPageScripts.LibraryProbe)
      .map(value => if (js.isUndefined(value) || value == null) "" else value.toString)
      .recover { case _ => "" }

  /**
   * 注入 readability / turndown 的源码，并用运行时 `typeof window.<global>` 验证结果。
   *
   * UMD 文件的 `module.exports` 被 `typeof module === "object"` 包着，页面里根本不会执行，按源码字符串判会误杀。
   */
  private def ensureLibraries(target: DebuggerSession): Future[Unit] =
    libraryProbe(target).flatMap { present =>
      if (present.contains("r") && present.contains("t")) Future.successful(())
      else
        ExtractSources.sources match {
          case None                          => Future.successful(())
          case Some((readability, turndown)) =>
            val injectReadability =
              if (present.contains("r")) Future.successful(())
              else evaluate(target, readability).map(_ => ()).recover { case _ => () }
            val injectTurndown    =
              if (present.contains("t")) Future.successful(())
              else evaluate(target, turndown).map(_ => ()).recover { case _ => () }
            injectReadability.flatMap(_ => injectTurndown).flatMap(_ => libraryProbe(target)).map { after =>
              if (!after.contains("r"))
                PopCanLog.warn(
                  "tool",
                  "extract libraries unavailable after injection (window.Readability missing); " +
                    "falling back to regions/full text"
                )
            }
        }
    }

  private def choose(
      target: DebuggerSession,
      urlHint: String,
      titleHint: String,
      goal: String,
      candidates: ExtractCandidates
  ): Future[String] = {
    val url                                            = candidates.url.toOption.filter(_.nonEmpty).getOrElse(urlHint)
    val title                                          = candidates.readabilityTitle.toOption.filter(_.nonEmpty)
      .orElse(candidates.title.toOption.filter(_.nonEmpty))
      .getOrElse(titleHint)
    val metadata                                       = candidates.metadata.toOption.getOrElse("")
    val readability                                    = candidates.readability.toOption.getOrElse("")
    val linkDensity                                    = candidates.readabilityLinkDensity.toOption.getOrElse(0.0)
    val full                                           = candidates.full.toOption.getOrElse("")
    def render(usedMode: String, text: String): String =
      s"URL: $url\nTitle: $title\n[extract: mode=$usedMode chars=${text.length}]\n\n$text"
    if (metadata.length >= ExtractMinChars) Future.successful(render("metadata", metadata))
    else
      for {
        regionResult <- regions(target, goal)
        resultList   <- detectResultList(target, goal)
      } yield {
        val (regionCount, regionsText) = regionResult
        val articleLike                = readability.length >= ExtractMinChars && linkDensity <= ExtractMaxLinkDensity
        val mode                       =
          if (resultList.isDefined) "results"
          else if (articleLike) "readability"
          else if (regionsText.length >= ExtractRegionMinChars) "regions"
          else "full"
        val regionText                 = mode match {
          case "readability" => readability
          case "regions"     => regionsText
          case _             => full
        }
        PopCanLog.debug(
          "tool",
          s"extract mode=$mode metadata=${metadata.length} readability=${readability.length} " +
            s"linkDensity=${"%.2f".format(linkDensity)} regions=$regionCount/${regionsText.length} " +
            s"resultList=${resultList.isDefined} full=${full.length}"
        )
        resultList.getOrElse(render(mode, regionText))
      }
  }

  private def regions(target: DebuggerSession, goal: String): Future[(Int, String)] =
    fullAxTree(target)
      .map(nodes => AxFilter.regionSelection(collectRawNodes(nodes, MaxRawNodes), goal, ExtractRegionsChars))
      .recover { case _ => (0, "") }

  private def detectResultList(target: DebuggerSession, query: String): Future[Option[String]] =
    evaluate(target, SearchResults.detectJs(query, None))
      .map { raw =>
        val detected = SearchResults.parse(raw)
        val scored   = detected.lists
          .map(candidate => candidate -> SearchResults.score(candidate, query))
          .sortBy(-_._2)
        scored.headOption
          .filter { case (candidate, _) =>
            candidate.recordCount >= ExtractListPageRecords && SearchResults.valid(candidate)
          }
          .map { case (candidate, score) =>
            SearchResults.render(detected, candidate, score, math.max(0, scored.size - 1))
          }
      }
      .recover { case _ => None }
}
