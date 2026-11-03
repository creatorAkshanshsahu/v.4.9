"""Check the user-confirmed playback baseline without printing source credentials."""
import hashlib
import json
from pathlib import Path
baseline=json.loads(Path('docs/playback-baseline-4.9.18.json').read_text())
bad=[path for path,expected in baseline['sha256'].items() if hashlib.sha256(Path(path).read_bytes()).hexdigest()!=expected]
if bad:
    raise SystemExit('Unexpected playback-contract change: '+', '.join(bad))
print(f"Verified {len(baseline['sha256'])} network/DRM/VOD policy files against the working 4.9.18 baseline.")
