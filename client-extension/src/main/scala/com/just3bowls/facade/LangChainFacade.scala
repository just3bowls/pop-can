package com.just3bowls.facade
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, JSName}
import scala.annotation.nowarn

/** LangChain / LangGraph / zod 的 Scala.js 最小 facade。 */
@js.native
@nowarn
@JSImport("@langchain/core/messages", "AIMessage")
class AIMessage(fields: js.Any)     extends js.Object {
  val content: js.Any                           = js.native
  val tool_calls: js.UndefOr[js.Array[js.Any]]  = js.native
  val response_metadata: js.UndefOr[js.Dynamic] = js.native
}
@js.native
@nowarn
@JSImport("@langchain/core/messages", "HumanMessage")
class HumanMessage(fields: js.Any)  extends js.Object {
  val content: js.Any = js.native
}
@js.native
@nowarn
@JSImport("@langchain/core/messages", "ToolMessage")
class ToolMessage(fields: js.Any)   extends js.Object {
  val content: js.Any      = js.native
  val tool_call_id: String = js.native
}
@js.native
@nowarn
@JSImport("@langchain/core/messages", "SystemMessage")
class SystemMessage(fields: js.Any) extends js.Object {
  val content: js.Any = js.native
}

/** LangGraph 传给模型/由模型返回的消息（也可以是其他 BaseMessage 子类）。 */
@js.native
trait LangChainBaseMessage extends js.Object {
  val content: js.Any                          = js.native
  val tool_calls: js.UndefOr[js.Array[js.Any]] = js.native
  val tool_call_id: js.UndefOr[String]         = js.native
  val name: js.UndefOr[String]                 = js.native
  def _getType(): String                       = js.native
}
@js.native
@JSImport("zod", "z")
object Zod                 extends js.Object {
  @JSName("object")
  def obj(shape: js.Dictionary[js.Any]): ZodSchema = js.native
  def string(): ZodSchema                          = js.native
  def number(): ZodSchema                          = js.native
  def boolean(): ZodSchema                         = js.native

  /** 任意 JSON（数组/对象参数用）。 */
  def any(): ZodSchema = js.native
}

/** zod schema 的最小 facade：只用到 `optional()`。 */
@js.native
trait ZodSchema       extends js.Object {
  def optional(): ZodSchema = js.native
}
@js.native
@JSImport("@langchain/core/tools", JSImport.Namespace)
object LangChainTools extends js.Object {
  def tool(func: js.Function1[js.Any, js.Any], fields: js.Object): js.Object = js.native
}
@js.native
@JSImport("@langchain/core/runnables", "RunnableLambda")
object RunnableLambda extends js.Object {
  def from(func: js.Function1[js.Any, js.Any]): js.Object = js.native
}
@js.native
trait LangGraphApp    extends js.Object {
  def invoke(input: js.Object, config: js.UndefOr[js.Object] = js.undefined): js.Promise[js.Any] = js.native
}

/** LangChain v1 的通用 Agent harness；底层仍编译为 LangGraph。 */
@js.native
@JSImport("langchain", "createAgent")
def createLangChainAgent(params: js.Object): LangGraphApp = js.native

/** LangChain middleware factory，用于终止工具和 PopCan 事件桥接。 */
@js.native
@JSImport("langchain", "createMiddleware")
def createLangChainMiddleware(params: js.Object): js.Object = js.native

/** LangGraph native route-and-update command. */
@js.native
@nowarn
@JSImport("@langchain/langgraph", "Command")
class LangGraphCommand(params: js.Object) extends js.Object

/** LangGraph in-memory checkpointer for interrupt/resume. */
@js.native
@JSImport("@langchain/langgraph", "MemorySaver")
class LangGraphMemorySaver extends js.Object

/** LangGraph 的 StateGraph / Annotation / START / END 最小 facade。 */
@js.native
@JSImport("@langchain/langgraph", JSImport.Namespace)
object LangGraphNS              extends js.Object {
  def StateGraph: js.Any = js.native
  def Annotation: js.Any = js.native
  def START: js.Any      = js.native
  def END: js.Any        = js.native
}
@js.native
trait LangGraphAnnotationModule extends js.Object {
  def Root(fields: js.Dictionary[js.Any]): js.Object = js.native
}
@js.native
trait StateGraphBuilder         extends js.Object {
  def addNode(name: String, action: js.Function1[js.Any, js.Any]): StateGraphBuilder = js.native
  @JSName("addNode")
  def addNodeValue(name: String, node: js.Any): StateGraphBuilder                    = js.native
  @JSName("addNode")
  def addNodeWithOptions(
      name: String,
      node: js.Any,
      options: js.UndefOr[js.Object]
  ): StateGraphBuilder = js.native
  def addEdge(from: js.Any, to: js.Any): StateGraphBuilder                           = js.native
  def addConditionalEdges(
      from: js.Any,
      path: js.Function1[js.Any, js.Any],
      mapping: js.UndefOr[js.Any] = js.undefined
  ): StateGraphBuilder = js.native
  def compile(config: js.UndefOr[js.Object] = js.undefined): CompiledStateGraph      = js.native
}

/** @langchain/langgraph 的 StateGraph 是 ES class，必须 new 构造（不能当普通函数调用）。 */
@js.native
@nowarn
@JSImport("@langchain/langgraph", "StateGraph")
class StateGraph(state: js.Object) extends StateGraphBuilder
@js.native
trait CompiledStateGraph           extends js.Object {
  def invoke(input: js.Any, config: js.UndefOr[js.Object] = js.undefined): js.Promise[js.Any] = js.native
}

/** 直接 invoke 的 runnable（例如无工具阶段的模型调用）。 */
@js.native
trait LangChainRunnable extends js.Object {
  def invoke(input: js.Any, config: js.UndefOr[js.Object] = js.undefined): js.Promise[js.Any] = js.native
}
