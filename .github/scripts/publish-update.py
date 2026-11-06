"""Build the in-app update manifest published with every STREAMR build.

The app reads `update.json` from the rolling `latest` GitHub release and installs a newer build over
itself. This script only writes version metadata and SHA-256 checksums of the APKs that CI already
built; it reads no secret and stores no URL other than the public release asset path.
"""
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ASSETS = (("arm32", "AK-TV-arm32.apk"), ("arm64", "AK-TV-arm64.apk"))


def parse_version(gradle_text):
    """Read versionCode/versionName from the module build file (the single source of truth)."""
    code = re.search(r"versionCode\s*=\s*(\d{1,9})", gradle_text)
    name = re.search(r'versionName\s*=\s*"([0-9A-Za-z._+-]{1,40})"', gradle_text)
    if not code or not name:
        raise SystemExit("app/build.gradle.kts does not declare versionCode/versionName")
    return int(code.group(1)), name.group(1)


def asset_entry(path, url):
    data = Path(path).read_bytes()
    if not data:
        raise SystemExit(f"{path} is empty")
    return {"url": url, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}


def build_manifest(version_code, version_name, run, repo, commit, apk_paths, now=None):
    base = f"https://github.com/{repo}/releases/latest/download"
    apks = {}
    for abi, filename in ASSETS:
        path = apk_paths.get(abi)
        if path is None:
            raise SystemExit(f"missing {filename}")
        apks[abi] = asset_entry(path, f"{base}/{filename}")
    return {
        "versionCode": version_code,
        "versionName": version_name,
        "build": str(run),
        "commit": (commit or "")[:40],
        "published": (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "notes": f"STREAMR {version_name} (code {version_code}) · CI build {run}",
        "apks": apks,
    }


def notes_markdown(manifest):
    lines = [
        f"# STREAMR {manifest['versionName']}",
        "",
        f"Version code **{manifest['versionCode']}**, CI build {manifest['build']}.",
        "",
        "Install either APK, or use **Update** in the app: it downloads the matching asset and",
        "installs it over the current build without a manual reinstall.",
        "",
        "| ABI | File | Size | SHA-256 |",
        "| --- | --- | --- | --- |",
    ]
    for abi, _ in ASSETS:
        item = manifest["apks"][abi]
        lines.append(f"| {abi} | `{item['url'].rsplit('/', 1)[-1]}` | {item['size']} B | `{item['sha256']}` |")
    lines.append("")
    return "\n".join(lines)


def main(argv):
    out = Path(argv[1] if len(argv) > 1 else "out")
    run = argv[2] if len(argv) > 2 else "0"
    repo = argv[3] if len(argv) > 3 else "creatorAkshanshsahu/v.4.9"
    commit = argv[4] if len(argv) > 4 else ""
    code, name = parse_version(Path("app/build.gradle.kts").read_text())
    manifest = build_manifest(code, name, run, repo, commit,
                              {abi: out / filename for abi, filename in ASSETS})
    (out / "update.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (out / "update-notes.md").write_text(notes_markdown(manifest))
    print(f"update.json: STREAMR {name} (code {code}) · arm32/arm64 assets published")


if __name__ == "__main__":
    main(sys.argv)
