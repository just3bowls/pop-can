package com.just3bowls.model.provider

import com.just3bowls.facade.JsBridge

import munit.FunSuite
import org.scalajs.macrotaskexecutor.MacrotaskExecutor.Implicits.global

import scala.scalajs.js

class ProviderPackCryptoSpec extends FunSuite {
  private val publicKey =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENATYCql+sc79cZUuFp7mbeACS9uJlRZuyeKTkoRicSvnr6d5osBEScxwiBedfUsdwRwgD86GkctjWOBCfjaaEQ=="
  private val envelope  =
    """{"formatVersion":1,"keyId":"2df0b2b77312a826","algorithm":"ECDSA-P256-SHA256","payload":"ewogICJraW5kIjogInByb3ZpZGVyLWluZGV4IiwKICAicGFja1NjaGVtYVZlcnNpb24iOiAxLAogICJwcm92aWRlclNjaGVtYVZlcnNpb24iOiAyLAogICJidW5kbGVWZXJzaW9uIjogMjEsCiAgIm1pbkV4dGVuc2lvblZlcnNpb24iOiAiMC4xLjAiLAogICJnZW5lcmF0ZWRBdCI6ICIyMDI2LTExLTE0VDAwOjAwOjAwLjAwMFoiLAogICJzaGEyNTYiOiAiMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZiIsCiAgInBhY2tVcmwiOiAicHJvdmlkZXItcGFjay12MjEucG9wY2FuLmpzb24iLAogICJwcm92aWRlcnMiOiBbCiAgICB7CiAgICAgICJpZCI6ICJ0ZXN0IiwKICAgICAgIm5hbWUiOiAiVGVzdCIKICAgIH0KICBdCn0K","signature":"S6Q-Sw1bRyY1fN9xCRiyR9SwwkfIAgH2HYz4pJJvIvCDvzFPJ0QIeDhwbwXfP6ResloIec0nFiOlHt-H5qR1Aw"}"""

  test("verifies a signed provider index envelope") {
    ProviderPackCrypto.verifyEnvelope(envelope, publicKey).map { verified =>
      assertEquals(verified.keyId, "2df0b2b77312a826")
      assertEquals(JsBridge.stringField(verified.payload, "kind"), Some("provider-index"))
      assertEquals(JsBridge.intField(verified.payload, "bundleVersion"), Some(21))
    }
  }

  test("rejects a tampered provider signature") {
    val tampered = envelope.replace("S6Q-Sw1b", "A6Q-Sw1b")
    ProviderPackCrypto
      .verifyEnvelope(tampered, publicKey)
      .map(_ => fail("tampered signature must fail"))
      .recover { case _ => () }
  }

  test("parses provider index metadata and rejects external pack URLs") {
    val valid = JsBridge.obj(
      "kind"                  -> ("provider-index"),
      "packSchemaVersion"     -> (1),
      "providerSchemaVersion" -> (1),
      "bundleVersion"         -> (21),
      "minExtensionVersion"   -> ("0.1.0"),
      "generatedAt"           -> ("2026-11-14T00:00:00.000Z"),
      "sha256"                -> ("0123456789abcdef" * 4),
      "packUrl"               -> ("provider-pack-v21.popcan.json"),
      "providers"             -> (js.Array(JsBridge.obj("id" -> ("test"), "name" -> ("Test"))))
    )
    assert(ProviderPackIndex.parse(valid).isRight)

    val external = JsBridge.obj(
      "kind"                  -> ("provider-index"),
      "packSchemaVersion"     -> (1),
      "providerSchemaVersion" -> (1),
      "bundleVersion"         -> (21),
      "minExtensionVersion"   -> ("0.1.0"),
      "generatedAt"           -> ("2026-11-14T00:00:00.000Z"),
      "sha256"                -> ("0123456789abcdef" * 4),
      "packUrl"               -> ("https://evil.example/provider-pack-v21.popcan.json"),
      "providers"             -> (js.Array())
    )
    assert(ProviderPackIndex.parse(external).isLeft)
  }

  test("enforces the minimum extension version") {
    assert(ProviderPackVersions.satisfies("0.1.0", "0.1.0"))
    assert(ProviderPackVersions.satisfies("0.2.0", "0.1.9"))
    assert(!ProviderPackVersions.satisfies("0.1.0", "0.2.0"))
  }

  test("rejects rollback and same-version replacement") {
    assert(ProviderPackInstallPolicy.validate(21, "old", 22, "new").isRight)
    assert(ProviderPackInstallPolicy.validate(21, "same", 21, "same").isRight)
    assert(ProviderPackInstallPolicy.validate(22, "new", 21, "old").isLeft)
    assert(ProviderPackInstallPolicy.validate(21, "old", 21, "different").isLeft)
  }

  test("recognizes a previously processed bootstrap sidecar") {
    val hash   = "0123456789abcdef" * 4
    assert(ProviderPackBootstrapPolicy.validSha256(hash))
    val marker = ProviderPackBootstrapPolicy.marker("1.1.0", hash)
    assert(ProviderPackBootstrapPolicy.alreadySeen(marker, "1.1.0", hash))
    assert(!ProviderPackBootstrapPolicy.alreadySeen(marker, "1.2.0", hash))
    assert(!ProviderPackBootstrapPolicy.alreadySeen("", "1.1.0", hash))
    assert(!ProviderPackBootstrapPolicy.validSha256("not-a-sha"))
  }
}
