"""Verify pinned, bundled OCR models and vendored SDK sources without network access."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'android/core/data/src/main/assets/ocr/paddle'
SDK = ROOT / 'android/core/data/src/main/kotlin'


def main():
    manifest = json.loads((ASSETS / 'provenance.json').read_text(encoding='utf-8'))
    for name, expected in manifest['models'].items():
        path = ASSETS / name
        if path.stat().st_size != expected['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != expected['sha256']:
            raise ValueError(f'Bundled OCR asset mismatch: {name}')
    recorded = manifest['sdk']['vendoredSha256']
    actual = {p.relative_to(SDK).as_posix() for p in (SDK / 'com/paddle/ocr').rglob('*.kt')}
    if actual != set(recorded):
        raise ValueError('Vendored OCR SDK file set changed')
    for name, expected in recorded.items():
        normalized = (SDK / name).read_text(encoding='utf-8').replace('\r\n', '\n').encode('utf-8')
        if hashlib.sha256(normalized).hexdigest() != expected:
            raise ValueError(f'Vendored OCR SDK mismatch: {name}')
    print(f'PaddleOCR integrity passed: {len(manifest["models"])} assets, {len(recorded)} SDK sources')


if __name__ == '__main__':
    main()
