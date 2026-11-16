package com.just3bowls.facade

import scala.scalajs.js

type JsObject = js.Dynamic & js.Object

/**
 * Centralized Scala.js dynamic boundary. Business layers may pass js.Any values through it, but may not manipulate them
 * dynamically.
 */
object JsBridge {
  def obj(fields: (String, js.Any)*): JsObject =
    js.Dynamic.literal(fields*).asInstanceOf[JsObject]

  def array(values: js.Any*): js.Array[js.Any] =
    js.Array(values*)

  def any(value: js.Any): js.Any = value

  def globalThis: JsObject = js.Dynamic.global.globalThis.asInstanceOf[JsObject]
  def document: JsObject   = js.Dynamic.global.document.asInstanceOf[JsObject]
  def window: JsObject     = js.Dynamic.global.window.asInstanceOf[JsObject]
  def location: JsObject   = js.Dynamic.global.location.asInstanceOf[JsObject]
  def navigator: JsObject  = js.Dynamic.global.navigator.asInstanceOf[JsObject]
  def chrome: JsObject     = js.Dynamic.global.chrome.asInstanceOf[JsObject]
  def crypto: JsObject     = js.Dynamic.global.crypto.asInstanceOf[JsObject]

  def stringConstructor: JsObject           = js.Dynamic.global.String.asInstanceOf[JsObject]
  def objectConstructor: JsObject           = js.Dynamic.global.Object.asInstanceOf[JsObject]
  def urlConstructor: JsObject              = js.Dynamic.global.URL.asInstanceOf[JsObject]
  def blobConstructor: JsObject             = js.Dynamic.global.Blob.asInstanceOf[JsObject]
  def errorConstructor: JsObject            = js.Dynamic.global.Error.asInstanceOf[JsObject]
  def mutationObserverConstructor: JsObject = js.Dynamic.global.MutationObserver.asInstanceOf[JsObject]
  def xpathResult: JsObject                 = js.Dynamic.global.XPathResult.asInstanceOf[JsObject]

  def getComputedStyle(element: js.Any): JsObject =
    js.Dynamic.global.getComputedStyle(element.asInstanceOf[js.Any]).asInstanceOf[JsObject]
  def btoa(value: String): String                 =
    js.Dynamic.global.btoa(value).asInstanceOf[String]
  def atob(value: String): String                 =
    js.Dynamic.global.atob(value).asInstanceOf[String]
  def encodeURIComponent(value: String): String   =
    js.Dynamic.global.encodeURIComponent(value).asInstanceOf[String]
  def decodeURIComponent(value: String): String   =
    js.Dynamic.global.decodeURIComponent(value).asInstanceOf[String]

  def providerPackPublicKey: String = js.Dynamic.global.__popCanProviderPackPublicKey.asInstanceOf[String]
  def webChatScript: js.Any         = js.Dynamic.global.__popCanWebChatScript.asInstanceOf[js.Any]
  def locationScript: js.Any        = js.Dynamic.global.__popCanLocationScript.asInstanceOf[js.Any]
  def extractSources: js.Any        = js.Dynamic.global.__thExtractSources.asInstanceOf[js.Any]

  def field(value: js.Any, name: String): js.Any =
    if (js.isUndefined(value) || value == null) js.undefined
    else value.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[js.Any]

  def stringField(value: js.Any, name: String): Option[String] =
    val raw = field(value, name)
    if (js.typeOf(raw) == "string") Some(raw.asInstanceOf[String]) else None

  def booleanField(value: js.Any, name: String): Option[Boolean] =
    val raw = field(value, name)
    if (js.typeOf(raw) == "boolean") Some(raw.asInstanceOf[Boolean]) else None

  def intField(value: js.Any, name: String): Option[Int] =
    doubleField(value, name).map(_.toInt)

  def doubleField(value: js.Any, name: String): Option[Double] =
    val raw = field(value, name)
    if (js.typeOf(raw) == "number") Some(raw.asInstanceOf[Double]) else None

  def arrayField(value: js.Any, name: String): js.Array[js.Any] =
    field(value, name) match {
      case array: js.Array[?] => array.asInstanceOf[js.Array[js.Any]]
      case _                  => js.Array()
    }

  def undefinedString(value: js.Any): js.UndefOr[String] =
    if (js.typeOf(value) == "string") value.asInstanceOf[js.UndefOr[String]] else js.undefined

  def undefinedBoolean(value: js.Any): js.UndefOr[Boolean] =
    if (js.typeOf(value) == "boolean") value.asInstanceOf[js.UndefOr[Boolean]] else js.undefined

  def undefinedInt(value: js.Any): js.UndefOr[Int] =
    if (js.typeOf(value) == "number") value.asInstanceOf[js.UndefOr[Int]] else js.undefined

  def undefinedDouble(value: js.Any): js.UndefOr[Double] =
    if (js.typeOf(value) == "number") value.asInstanceOf[js.UndefOr[Double]] else js.undefined

  def undefinedAny(value: js.Any): js.UndefOr[js.Any] =
    if (value == null || js.isUndefined(value)) js.undefined else value.asInstanceOf[js.UndefOr[js.Any]]

  def undefinedArrayAny(value: js.Any): js.UndefOr[js.Array[js.Any]] =
    if (js.Array.isArray(value)) value.asInstanceOf[js.UndefOr[js.Array[js.Any]]] else js.undefined

  def undefinedStringArray(value: js.Any): js.UndefOr[js.Array[String]] =
    if (js.Array.isArray(value)) value.asInstanceOf[js.UndefOr[js.Array[String]]] else js.undefined

  def undefinedDictionary(value: js.Any): js.UndefOr[js.Dictionary[js.Any]] =
    if (value != null && !js.isUndefined(value) && js.typeOf(value) == "object")
      value.asInstanceOf[js.UndefOr[js.Dictionary[js.Any]]]
    else js.undefined

  def asAny(value: scala.Any): js.Any =
    value.asInstanceOf[js.Any]

  def asString(value: js.Any): String =
    if (value == null || js.isUndefined(value)) "" else value.toString

  def asInt(value: js.Any): Int =
    if (js.typeOf(value) == "number") value.asInstanceOf[Double].toInt else 0

  def asDouble(value: js.Any): Double =
    if (js.typeOf(value) == "number") value.asInstanceOf[Double] else 0.0

  def asBoolean(value: js.Any): Boolean =
    if (js.typeOf(value) == "boolean") value.asInstanceOf[Boolean] else false

  def asArray(value: js.Any): js.Array[js.Any] =
    if (js.Array.isArray(value)) value.asInstanceOf[js.Array[js.Any]] else js.Array()

  def asStringArray(value: js.Any): js.Array[String] =
    if (js.Array.isArray(value)) value.asInstanceOf[js.Array[String]] else js.Array()

  def asTypedArray[A <: js.Any](value: js.Any): js.Array[A] =
    if (js.Array.isArray(value)) value.asInstanceOf[js.Array[A]] else js.Array()

  def asDictionary(value: js.Any): js.Dictionary[js.Any] =
    if (value != null && !js.isUndefined(value) && js.typeOf(value) == "object")
      value.asInstanceOf[js.Dictionary[js.Any]]
    else js.Dictionary.empty[js.Any]

  def asPromiseAny(value: js.Any): js.Promise[js.Any] =
    value.asInstanceOf[js.Promise[js.Any]]

  def call(value: js.Any, name: String, args: js.Any*): js.Any =
    value.asInstanceOf[js.Dynamic].applyDynamic(name)(args*).asInstanceOf[js.Any]

  def update(value: js.Any, name: String, next: js.Any): Unit =
    value.asInstanceOf[js.Dynamic].updateDynamic(name)(next)

  def newInstance(constructor: js.Any, args: js.Any*): JsObject =
    js.Dynamic.newInstance(constructor.asInstanceOf[js.Dynamic])(args*).asInstanceOf[JsObject]

  def jsonParse(value: String): js.Any =
    js.JSON.parse(value).asInstanceOf[js.Any]

  def jsonStringify(value: js.Any): String =
    js.JSON.stringify(value).asInstanceOf[String]

  /** View a JavaScript value as an object without emitting a runtime property lookup. */
  def asObject(value: js.Any): JsObject =
    value.asInstanceOf[JsObject]
}
