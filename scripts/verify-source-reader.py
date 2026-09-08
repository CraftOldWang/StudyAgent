"""Read-only production API acceptance for source provenance and ownership."""
import argparse
import hashlib
import json
from pathlib import Path
import requests

parser = argparse.ArgumentParser()
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
parser.add_argument('--plan-id', required=True)
parser.add_argument('--other-knowledge-base-id', required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
ledger = root / '.eval/model-calls.jsonl'
def attempts():
    return sum(json.loads(line)['status'] == 'STARTED' for line in ledger.read_text(encoding='utf-8').splitlines())
def get(path, user='1', **params):
    response = requests.get(args.base_url + path, params=params, headers={'X-User-Id': user}, timeout=30)
    return response.status_code, response.json()

before = attempts()
_, envelope = get('/api/learning/plans/' + args.plan_id)
assert envelope['code'] == 0, envelope
plan = envelope['data']
chunk_id = plan['result']['tasks'][0]['sourceChunkIds'][0]
kb = str(plan['knowledgeBaseId'])
assert kb != args.other_knowledge_base_id
status, envelope = get(f'/api/knowledge-bases/{kb}/source', chunkId=chunk_id)
assert status == 200 and envelope['code'] == 0, envelope
source = envelope['data']
assert source['content'] and source['documentTitle'] and source['sourceLocation']
checks = []
for label, selected_kb, user in [('wrong_knowledge_base', args.other_knowledge_base_id, '1'), ('wrong_user', kb, '2')]:
    status, rejected = get(f'/api/knowledge-bases/{selected_kb}/source', user=user, chunkId=chunk_id)
    assert rejected['code'] != 0 and not rejected.get('data'), rejected
    checks.append({'scenario': label, 'httpStatus': status, 'businessCode': rejected['code'], 'rejected': True})
after = attempts()
assert after == before
report = {'planId': args.plan_id, 'knowledgeBaseId': kb, 'chunkId': chunk_id,
          'documentId': source['documentId'], 'documentTitle': source['documentTitle'], 'sourceLocation': source['sourceLocation'],
          'contentCharacters': len(source['content']), 'contentSha256': hashlib.sha256(source['content'].encode()).hexdigest(),
          'ownershipChecks': checks, 'modelAttemptsBefore': before, 'modelAttemptsAfter': after}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('PASS: persisted source and ownership boundaries; no model calls')
