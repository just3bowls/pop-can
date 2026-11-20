package com.just3bowls.model.provider

import munit.FunSuite

class ProviderPresetStateSpec extends FunSuite {
  test("treats unsigned imports as intentional local overrides") {
    assertEquals(
      ProviderPresetState.classify(
        localHash = "local-v24",
        presetHash = Some("pack-v22"),
        meta = Some(PresetMeta(owned = false, version = 24, hash = "local-v24")),
        installedVersion = 22
      ),
      ProviderPresetState.LocalOverride
    )
  }

  test("keeps modified for signed-pack providers with real local drift") {
    assertEquals(
      ProviderPresetState.classify(
        localHash = "edited",
        presetHash = Some("pack-v22"),
        meta = Some(PresetMeta(owned = true, version = 22, hash = "pack-v22")),
        installedVersion = 22
      ),
      ProviderPresetState.Modified
    )
  }

  test("classifies synced, newer imported, and local-only providers") {
    assertEquals(
      ProviderPresetState.classify("same", Some("same"), None, 22),
      ProviderPresetState.Builtin
    )
    assertEquals(
      ProviderPresetState.classify(
        "local-v24",
        Some("pack-v22"),
        Some(PresetMeta(owned = true, version = 24, hash = "local-v24")),
        22
      ),
      ProviderPresetState.Imported
    )
    assertEquals(
      ProviderPresetState.classify("local", None, None, 22),
      ProviderPresetState.LocalOnly
    )
  }
}
