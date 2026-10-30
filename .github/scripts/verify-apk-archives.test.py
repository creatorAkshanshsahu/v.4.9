#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

SCRIPT = Path(__file__).with_name("verify-apk-archives.py")


def apk(path: Path, abi: str) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("classes.dex", b"dex")
        archive.writestr(f"lib/{abi}/libexample.so", b"native")
        archive.writestr("assets/payload.bin", os.urandom(1_050_000))


with tempfile.TemporaryDirectory(prefix="aktv-apk-check-") as temp:
    out = Path(temp)
    apk(out / "AK-TV-arm32.apk", "armeabi-v7a")
    apk(out / "AK-TV-arm64.apk", "arm64-v8a")
    result = subprocess.run([sys.executable, str(SCRIPT), str(out)], capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (out / "SHA256SUMS").is_file()
    assert '"valid": true' in (out / "apk-validation.json").read_text()

    wrong = Path(temp) / "wrong"
    wrong.mkdir()
    apk(wrong / "AK-TV-arm32.apk", "arm64-v8a")
    apk(wrong / "AK-TV-arm64.apk", "arm64-v8a")
    result = subprocess.run([sys.executable, str(SCRIPT), str(wrong)], capture_output=True, text=True)
    assert result.returncode != 0 and "Unexpected native ABI" in result.stderr

    corrupt = Path(temp) / "corrupt"
    corrupt.mkdir()
    (corrupt / "AK-TV-arm32.apk").write_bytes(b"not an apk zip")
    apk(corrupt / "AK-TV-arm64.apk", "arm64-v8a")
    result = subprocess.run([sys.executable, str(SCRIPT), str(corrupt)], capture_output=True, text=True)
    assert result.returncode != 0 and "Invalid or unexpectedly small APK" in result.stderr

print("APK archive validator tests passed (valid variants, ABI mismatch, corrupt payload).")
