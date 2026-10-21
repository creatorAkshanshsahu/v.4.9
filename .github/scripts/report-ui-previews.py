"""Bounded native Android test previews, not decoded video or user data. One scene per step."""
from pathlib import Path
import base64
import sys
import xml.etree.ElementTree as ET

name = sys.argv[1]
assert name in ('home-tv', 'home-phone', 'home-movies', 'brand-mark', 'entrance', 'vod-player', 'live-player')
path=Path('app/build/reports/ui-preview')/(name+'.webp')
if not path.exists():
    print(f'::warning title=Native UI preview::{name} was not produced; no visual assertion is made.')
else:
    encoded=base64.b64encode(path.read_bytes()).decode('ascii')
    if len(encoded)>38000:
        print(f'::warning title=Native UI preview::{name} exceeds the bounded preview transport size ({len(encoded)} chars).')
    else:
        count=(len(encoded)+3799)//3800
        for index in range(count):
            print(f'::notice title=Native UI preview {name} {index+1} of {count}::{encoded[index*3800:(index+1)*3800]}')
