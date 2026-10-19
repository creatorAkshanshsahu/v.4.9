"""Expose bounded native-layout test previews, not screenshots of decoded video or real user data."""
from pathlib import Path
import base64

for name in ('home-tv', 'home-phone', 'home-movies'):
    path=Path('app/build/reports/ui-preview')/(name+'.jpg')
    if not path.exists():
        print(f'::warning title=Native UI preview::{name} was not produced; no visual assertion is made.')
        continue
    encoded=base64.b64encode(path.read_bytes()).decode('ascii')
    if len(encoded)>144000:
        print(f'::warning title=Native UI preview::{name} exceeds the bounded preview transport size.')
        continue
    count=(len(encoded)+47999)//48000
    for index in range(count):
        print(f'::notice title=Native UI preview {name} {index+1} of {count}::{encoded[index*48000:(index+1)*48000]}')
