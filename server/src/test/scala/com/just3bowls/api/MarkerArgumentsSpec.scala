package com.just3bowls.api
import com.just3bowls.api.marker.*
import com.just3bowls.api.protocol.JsonEnvelope

import zio.json.ast.Json
import zio.test._

object MarkerArgumentsSpec extends ZIOSpecDefault {

  private def strings(fields: Option[Map[String, Json]], name: String): List[String] =
    fields
      .flatMap(_.get(name))
      .collect { case Json.Arr(values) => values.toList.collect { case Json.Str(value) => value } }
      .getOrElse(Nil)

  private def objects(fields: Option[Map[String, Json]], name: String): List[Map[String, Json]] =
    fields
      .flatMap(_.get(name))
      .collect { case Json.Arr(values) => values.toList.collect { case Json.Obj(values) => values.toMap } }
      .getOrElse(Nil)

  private val wenxinShape =
    """@@BEG:restatedQuestion:string@@研究计划@@END:restatedQuestion:string@@
      |@@BEG:outline:array@@
      |@@BEG:0:string@@第一节@@END:0:string@@
      |@@BEG:1:string@@第二节@@END:1:string@@
      |@@BEG:2:string@@第三节@@END:2:string@@
      |@@BEG:3:string@@第四节@@END:3:string@@
      |@@BEG:4:string@@第五节@@END:4:string@@
      |@@END:outline:array@@
      |@@BEG:subQuestions:array@@
      |@@BEG:0:object@@
      |@@BEG:question:string@@问题一@@END:question:string@@
      |@@END:0:object@@
      |@@BEG:1:object@@
      |@@BEG:question:string@@问题二@@END:question:string@@
      |@@END:1:object@@
      |@@BEG:2:object@@
      |@@BEG:question:string@@问题三@@END:question:string@@
      |@@END:2:object@@
      |@@END:subQuestions:array@@
      |@@BEG:assumptions:array@@
      |@@BEG:0:string@@假设一@@END:0:string@@
      |@@BEG:1:string@@假设二@@END:1:string@@
      |@@BEG:2:string@@假设三@@END:2:string@@
      |@@END:assumptions:array@@""".stripMargin

  def spec: Spec[Any, Any] = suite("MarkerArguments")(
    test("严格 Wenxin 形状：完整嵌套参数正确解析") {
      val parsed = MarkerArguments.parse(wenxinShape)
      val fields = parsed.toOption.flatMap(JsonEnvelope.fields)

      assertTrue(
        fields.flatMap(_.get("restatedQuestion")).contains(Json.Str("研究计划")),
        strings(fields, "outline") == List("第一节", "第二节", "第三节", "第四节", "第五节"),
        objects(fields, "subQuestions").flatMap(_.get("question").collect { case Json.Str(value) => value }) ==
          List("问题一", "问题二", "问题三"),
        strings(fields, "assumptions") == List("假设一", "假设二", "假设三"),
        fields.forall(!_.contains("0"))
      )
    },
    test("显式 END 类型仍正常") {
      val parsed = MarkerArguments.parse(
        "@@BEG:question:string@@hello@@END:question:string@@"
      )
      assertTrue(parsed.toOption.flatMap(JsonEnvelope.stringField(_, "question")).contains("hello"))
    },
    test("显式 END 类型不匹配时失败") {
      val parsed = MarkerArguments.parse(
        "@@BEG:question:string@@hello@@END:question:object@@"
      )
      assertTrue(parsed.left.exists(_.exists(_.contains("expected 'string'"))))
    },
    test("类型省略 END 和无名称数组项都严格失败") {
      assertTrue(
        MarkerArguments.parse("@@BEG:question:string@@hello@@END:question@@").isLeft,
        MarkerArguments.parse(
          "@@BEG:items:array@@\n@@BEG:@@hello@@END:0:string@@\n@@END:items:array@@"
        ).isLeft
      )
    },
    test("孤立 END、错误名称和未闭合块都失败") {
      assertTrue(
        MarkerArguments.parse("@@END:question@@").isLeft,
        MarkerArguments.parse("@@BEG:question:string@@hello@@END:other@@").isLeft,
        MarkerArguments.parse("@@BEG:question:string@@hello").isLeft
      )
    },
    test("一次解析聚合多个独立错误") {
      val parsed = MarkerArguments.parse(
        """@@BEG:a:string@@x@@END:a:number@@
          |@@BEG:b:string@@y@@END:b@@
          |@@END:c:string@@""".stripMargin
      )
      assertTrue(parsed.left.exists(_.size >= 2))
    },
    test("空参数是空对象") {
      assertTrue(MarkerArguments.parse("  ").contains("{}"))
    }
  )
}
