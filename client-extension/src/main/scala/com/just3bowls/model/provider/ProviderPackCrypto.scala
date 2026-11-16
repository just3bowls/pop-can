package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge
import com.just3bowls.facade.WebCryptoGlobal
import com.just3bowls.facade.TextDecoderGlobal
import com.just3bowls.facade.TextEncoderGlobal

import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

object ProviderPackCrypto {
  private def bytesFromBase64(value: String): Uint8Array = {
    val binary = JsBridge.atob(value)
    val bytes  = new Uint8Array(binary.length)
    var index  = 0
    while (index < binary.length) {
      bytes(index) = binary.charAt(index).toInt.toByte
      index += 1
    }
    bytes
  }

  private def bytesFromBase64Url(value: String): Uint8Array = {
    val normalized = value.replace('-', '+').replace('_', '/')
    val padded     = normalized + ("=" * ((4 - normalized.length % 4) % 4))
    bytesFromBase64(padded)
  }

  private def bytesToHex(bytes: Uint8Array): String = {
    val out = new StringBuilder(bytes.length * 2)
    var i   = 0
    while (i < bytes.length) {
      out.append(f"${bytes(i) & 0xff}%02x")
      i += 1
    }
    out.result()
  }

  private def digestSha256(bytes: Uint8Array): Future[Uint8Array] =
    WebCryptoGlobal.subtle
      .digest("SHA-256", bytes)
      .toFuture
      .map(buffer => new Uint8Array(buffer))

  def sha256Hex(bytes: Uint8Array): Future[String] = digestSha256(bytes).map(bytesToHex)

  def utf8Bytes(value: String): Uint8Array = new TextEncoderGlobal().encode(value)

  def utf8Text(bytes: Uint8Array): String = new TextDecoderGlobal().decode(bytes)

  def verifyEnvelope(envelopeText: String, publicKeyBase64: String): Future[VerifiedProviderEnvelope] =
    Future {
      val raw         = JsBridge.jsonParse(envelopeText)
      val format      = JsBridge.intField(raw, "formatVersion").getOrElse(0)
      val keyId       = JsBridge.stringField(raw, "keyId").getOrElse("")
      val algorithm   = JsBridge.stringField(raw, "algorithm").getOrElse("")
      val payload64   = JsBridge.stringField(raw, "payload").getOrElse("")
      val signature64 = JsBridge.stringField(raw, "signature").getOrElse("")
      if (format != 1) throw new RuntimeException(s"Unsupported provider envelope format: $format")
      if (algorithm != "ECDSA-P256-SHA256")
        throw new RuntimeException(s"Unsupported provider signature algorithm: $algorithm")
      if (keyId.length != 16 || payload64.isEmpty || signature64.isEmpty)
        throw new RuntimeException("Provider envelope is missing keyId, payload, or signature")

      val publicKeyBytes = bytesFromBase64(publicKeyBase64.trim)
      val payloadBytes   = bytesFromBase64Url(payload64)
      val signatureBytes = bytesFromBase64Url(signature64)
      (raw, publicKeyBytes, payloadBytes, signatureBytes, keyId)
    }.flatMap { case (_, publicKeyBytes, payloadBytes, signatureBytes, keyId) =>
      val importAlgorithm = JsBridge.obj("name" -> ("ECDSA"), "namedCurve" -> ("P-256"))
      val verifyAlgorithm = JsBridge.obj("name" -> ("ECDSA"), "hash" -> ("SHA-256"))
      for {
        computedKeyId <- sha256Hex(publicKeyBytes).map(_.take(16))
        _             <-
          if (computedKeyId == keyId) Future.successful(())
          else Future.failed(new RuntimeException(s"Provider pack key mismatch: $keyId != $computedKeyId"))
        key           <- WebCryptoGlobal.subtle
          .importKey("spki", publicKeyBytes, importAlgorithm, false, js.Array("verify"))
          .toFuture
        verified      <- WebCryptoGlobal.subtle
          .verify(verifyAlgorithm, key, signatureBytes, payloadBytes)
          .toFuture
        _             <- if (verified) Future.successful(())
        else Future.failed(new RuntimeException("Provider pack signature verification failed"))
      } yield VerifiedProviderEnvelope(
        envelopeText = envelopeText,
        payloadText = utf8Text(payloadBytes),
        payload = JsBridge.jsonParse(utf8Text(payloadBytes)),
        keyId = keyId
      )
    }
}
