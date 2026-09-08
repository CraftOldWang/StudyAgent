"""Check the design document mirror against the single runtime token owner."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
design = (root / 'DESIGN.md').read_text(encoding='utf-8').split('---')[1]
css = (root / 'frontend/src/styles.css').read_text(encoding='utf-8')
tokens = dict(re.findall(r'(--[\w-]+):\s*([^;]+);', css))
expected = {}
section = ''
role = ''
for line in design.splitlines():
    if line and not line.startswith(' '):
        section = line.split(':')[0]
    match = re.match(r'  ([\w-]+):\s*"([^"]+)"', line)
    if match and section in ('colors', 'rounded', 'spacing'):
        prefix = {'colors': 'color', 'rounded': 'radius', 'spacing': 'space'}[section]
        expected[f'--{prefix}-{match[1].lower()}'] = match[2]
    if section == 'typography':
        match = re.match(r'  (\w+):$', line)
        if match:
            role = match[1]
        match = re.match(r'    fontFamily: "([^"]+)"', line)
        if match:
            expected[f'--font-{role}'] = match[1]

def normalize(value):
    return re.sub(r'(?<!\d)0\.', '.', re.sub(r'[\s\'"]', '', value)).lower()

failures = [key for key, value in expected.items() if key not in tokens or normalize(tokens[key]) != normalize(value)]
assert len(expected) == 23, f'Unexpected token catalog: {len(expected)}'
print(json.dumps({'checked': len(expected), 'mismatches': failures}, ensure_ascii=False))
raise SystemExit(bool(failures))
