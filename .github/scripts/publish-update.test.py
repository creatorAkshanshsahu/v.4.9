#!/usr/bin/env python3
"""Deterministic checks for the in-app update manifest (no network, no real APK needed)."""
import importlib.util
import json
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("publish_update", Path(__file__).with_name("publish-update.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

GRADLE = '''    defaultConfig { applicationId = "com.akshansh.aktv"; minSdk = 28; targetSdk = 33; versionCode = 63; versionName = "4.9.46"'''


def test_version_is_read_from_the_module_build_file():
    assert module.parse_version(GRADLE) == (63, "4.9.46")


def test_manifest_points_at_the_rolling_release_assets():
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        paths = {}
        for abi, filename in module.ASSETS:
            paths[abi] = out / filename
            paths[abi].write_bytes(b"apk-" + abi.encode())
        manifest = module.build_manifest(63, "4.9.46", "1234", "owner/repo", "abc123",
                                         paths, now=module.datetime(2026, 11, 4, tzinfo=module.timezone.utc))
        assert manifest["versionCode"] == 63
        assert manifest["versionName"] == "4.9.46"
        assert manifest["published"] == "2026-11-04T00:00:00Z"
        assert set(manifest["apks"]) == {"arm32", "arm64"}
        for abi, filename in module.ASSETS:
            item = manifest["apks"][abi]
            assert item["url"] == f"https://github.com/owner/repo/releases/latest/download/{filename}"
            assert len(item["sha256"]) == 64
            assert item["size"] == len(b"apk-" + abi.encode())
        assert "STREAMR 4.9.46" in module.notes_markdown(manifest)


def test_missing_apk_fails_instead_of_publishing_a_partial_manifest():
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        (out / "AK-TV-arm64.apk").write_bytes(b"apk")
        try:
            module.build_manifest(63, "4.9.46", "1", "owner/repo", "", {"arm64": out / "AK-TV-arm64.apk"})
        except SystemExit as error:
            assert "AK-TV-arm32.apk" in str(error)
        else:
            raise AssertionError("a missing APK must fail the publish step")


def test_manifest_is_json_serialisable():
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        paths = {}
        for abi, filename in module.ASSETS:
            paths[abi] = out / filename
            paths[abi].write_bytes(b"apk")
        manifest = module.build_manifest(63, "4.9.46", "1", "owner/repo", "", paths)
        assert json.loads(json.dumps(manifest))["apks"]["arm64"]["size"] == 3


if __name__ == "__main__":
    for name, function in sorted(globals().items()):
        if name.startswith("test_") and callable(function):
            function()
    print("publish-update.test.py: all checks passed")
