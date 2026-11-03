"""Expose useful Gradle errors in GitHub annotations without hiding the build's exit status."""
import os
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def annotation(title, message):
    # Escape workflow-command data, including newlines from compiler/test output.
    message = message[:12000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={title}::{message}")


log = Path(os.environ["RUNNER_TEMP"]) / "aktv-gradle.log"
if log.exists():
    lines = log.read_text(errors="replace").splitlines()
    selected = set()
    markers = re.compile(r"^e: |error:|What went wrong|Execution failed|Could not | FAILED|tests completed", re.I)
    for i, line in enumerate(lines):
        if markers.search(line):
            selected.update(range(max(0, i - 1), min(len(lines), i + 9)))
    details = "\n".join(lines[i] for i in sorted(selected)) if selected else "\n".join(lines[-50:])
    annotation("Android build failure", details)

for report in Path("app/build/test-results").glob("*/TEST-*.xml"):
    try:
        for case in ET.parse(report).getroot().iter("testcase"):
            for failure in list(case.findall("failure")) + list(case.findall("error")):
                annotation("Unit test failure", f"{case.get('classname')}.{case.get('name')}\n{failure.get('message', '')}\n{failure.text or ''}")
    except (ET.ParseError, OSError):
        continue
