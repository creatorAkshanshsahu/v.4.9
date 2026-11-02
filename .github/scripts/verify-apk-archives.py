#!/usr/bin/env python3
"""Validate the installable APK ZIPs and publish size/SHA-256 metadata before artifact upload."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import zipfile

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("out")
EXPECTED = {
    "AK-TV-arm32.apk": ("armeabi-v7a", "arm64-v8a"),
    "AK-TV-arm64.apk": ("arm64-v8a", "armeabi-v7a"),
}


def validate(path: Path, expected_abi: str, forbidden_abi: str) -> dict[str, object]:
    if not path.is_file() or path.stat().st_size < 1_000_000:
        raise SystemExit(f"Invalid or unexpectedly small APK: {path.name}")
    try:
        with zipfile.ZipFile(path) as archive:
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise SystemExit(f"APK ZIP CRC failure: {path.name}:{bad_entry}")
            names = archive.namelist()
    except (zipfile.BadZipFile, OSError) as error:
        raise SystemExit(f"APK is not a readable ZIP: {path.name}: {error}") from error
    if "AndroidManifest.xml" not in names or not any(name.startswith("classes") and name.endswith(".dex") for name in names):
        raise SystemExit(f"APK is missing its manifest or dex code: {path.name}")
    libs = sorted({part for name in names if name.startswith("lib/") for part in name.split("/")[1:2]})
    if expected_abi not in libs or forbidden_abi in libs:
        raise SystemExit(f"Unexpected native ABI set for {path.name}: {libs}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"file": path.name, "bytes": path.stat().st_size, "sha256": digest.hexdigest(), "nativeAbis": libs}


records = [validate(OUT / name, *abis) for name, abis in EXPECTED.items()]
(OUT / "SHA256SUMS").write_text("".join(f"{record['sha256']}  {record['file']}\n" for record in records))
(OUT / "apk-validation.json").write_text(json.dumps({"valid": True, "artifacts": records}, indent=2) + "\n")
print("Validated APK ZIP CRCs, manifests, dex, target ABIs, sizes and SHA-256.")
for record in records:
    print(f"{record['file']}: {record['bytes']} bytes, {record['nativeAbis']}, sha256={record['sha256']}")
