# TG protocol fixtures

These are synthetic contract fixtures, not captures of live tokens or DRM keys.

- `player-config.json`: primary source plus independent TG-provided native sources. The third source is unencrypted HLS; the second shares the same explicit DRM object but must not inherit the primary cookie.
- `player-config.seroval.json`: the same object serialized with Seroval 1.6.9 `toJSON`.
- `player-config.cross.json`: Seroval 1.6.9 `toCrossJSON`, including a shared-object reference (`t: 4`).

All URLs use reserved example domains. The key bytes and token values are test data only. The tests build binary TanStack frames around the JSON fixture to exercise the actual framed protocol rather than pretending binary frames are text lines.
