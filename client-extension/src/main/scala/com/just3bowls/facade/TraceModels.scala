package com.just3bowls.facade
import scala.scalajs.js

/** Trace：单条 handler 的命中统计。 */
@js.native
trait TraceHandlerStatDto extends js.Object {
  def id: js.UndefOr[String] = js.native
  def hits: js.UndefOr[Int]  = js.native
}

/** Trace：marker 侧的解析结果。 */
@js.native
trait TraceMarkersDto extends js.Object {
  def contentChars: js.UndefOr[Int]  = js.native
  def thinkingChars: js.UndefOr[Int] = js.native
  def toolCalls: js.UndefOr[Int]     = js.native
  def valid: js.UndefOr[Boolean]     = js.native
  def error: js.UndefOr[String]      = js.native
}

/**
 * storage 里的一条 trace 记录。
 *
 * 字段全部可选——原始侧（SW）、marker 侧（扩展页）与耗时采集各自只写自己那一半，按 `gen` 归并。 `timings` 是 phase → 毫秒 的字典（两侧各写各的键，后写者按 key 合并）。
 */
@js.native
trait TraceDto extends js.Object {
  def gen: js.UndefOr[String]                                   = js.native
  def at: js.UndefOr[Double]                                    = js.native
  def provider: js.UndefOr[String]                              = js.native
  def matchedUrl: js.UndefOr[String]                            = js.native
  def requestId: js.UndefOr[String]                             = js.native
  def chunks: js.UndefOr[Int]                                   = js.native
  def rawChars: js.UndefOr[Int]                                 = js.native
  def normalizedChars: js.UndefOr[Int]                          = js.native
  def durationMs: js.UndefOr[Double]                            = js.native
  def firstRaw: js.UndefOr[String]                              = js.native
  def lastRaw: js.UndefOr[String]                               = js.native
  def parserHandlers: js.UndefOr[js.Array[TraceHandlerStatDto]] = js.native
  def markers: js.UndefOr[TraceMarkersDto]                      = js.native
  def timings: js.UndefOr[js.Dictionary[Double]]                = js.native
  def error: js.UndefOr[String]                                 = js.native
}
