"""Aggregate one Codex session without copying prompts, credentials or raw tool logs."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import statistics
import subprocess


def instant(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--session-dir', type=Path, required=True)
    parser.add_argument('--thread-id', required=True)
    parser.add_argument('--goal-start', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    paths = sorted(args.session_dir.glob('*' + args.thread_id + '*.jsonl'))
    start = datetime.fromtimestamp(args.goal_start, timezone.utc)
    cutoff = None
    for path in paths:
        for line in path.open(encoding='utf-8'):
            row = json.loads(line); p = row.get('payload', {})
            if row.get('type') == 'event_msg' and p.get('type') == 'user_message':
                if '我们不需要考虑窄屏幕' in p.get('message', ''):
                    cutoff = instant(row['timestamp']); break
            if row.get('type') == 'response_item' and p.get('role') == 'user':
                text = ''.join(c.get('text', '') for c in p.get('content', []) if isinstance(c, dict))
                if '我们不需要考虑窄屏幕' in text:
                    cutoff = instant(row['timestamp']); break
        if cutoff: break
    assert cutoff, 'Need an explicit pause-message boundary; never count this audit itself'
    usage = {}; kinds = Counter(); models = Counter(); tools = Counter(); nested = Counter()
    files_read = Counter(); patterns = Counter(); commands = Counter(); output_chars = Counter()
    compactions = []; hourly = defaultdict(Counter); assistant_chars = Counter(); first_record = None
    pending_tools = {}; intervals = []; durations = defaultdict(list)
    for path in paths:
        for line in path.open(encoding='utf-8'):
            row = json.loads(line); when = instant(row['timestamp'])
            if when < start or when >= cutoff: continue
            p = row.get('payload', {}); typ = row.get('type'); kinds[typ] += 1
            if typ == 'token_usage_record':
                rid = p['response_id']
                if rid not in usage:
                    u = p['usage']; usage[rid] = u
                    h = hourly[when.strftime('%Y-%m-%dT%H:00Z')]; h['calls'] += 1
                    for k, v in u.items(): h[k] += v
                    first_record = first_record or when
            elif typ == 'turn_context': models[(p.get('model'), p.get('effort'))] += 1
            elif typ == 'compacted':
                compactions.append({'at': row['timestamp'], 'replacementHistoryChars': len(json.dumps(p.get('replacement_history', []),ensure_ascii=False))})
            elif typ == 'response_item':
                item_type = p.get('type', '')
                if item_type in {'function_call', 'custom_tool_call'}:
                    name = p.get('name', 'unknown'); tools[name] += 1
                    pending_tools[p.get('call_id')] = (when, name)
                    a = p.get('arguments', p.get('input', '')); a = a if isinstance(a, str) else json.dumps(a)
                    nested.update(re.findall(r'tools\.([\w]+)\s*\(', a))
                    for label, regex in {
                        'getContentCalls': r'Get-Content', 'rgCalls': r'\brg(?:\.exe)?\s',
                        'mavenCalls': r'\bmvn(?:w|\.cmd)?\s', 'npmTestCalls': r'(?:npm|npm\.cmd).{0,20}\btest\b',
                        'dockerComposeCalls': r'docker.{0,15}compose',
                        'gitCommitCalls': r'git.{0,8}commit', 'gitPushCalls': r'git.{0,80}\bpush\b',
                        'hashMentions': r'(?i)sha256|fingerprint', 'freezeMentions': r'(?i)frozen|freeze',
                    }.items():
                        if re.search(regex, a): patterns[label] += 1
                    for f in re.findall(r"Get-Content(?:\s+-\w+\s+\S+)*\s+['\"]?([^\s'\";|]+)", a):
                        if any(x in f.lower() for x in ['apikey', 'secret', '.env']): continue
                        files_read[f.replace('\\', '/')] += 1
                    commands[a] += 1
                elif item_type in {'function_call_output', 'custom_tool_call_output'}:
                    if p.get('call_id') in pending_tools:
                        began, name = pending_tools.pop(p['call_id']); seconds = (when-began).total_seconds()
                        if seconds >= 0:
                            durations[name].append(seconds); intervals.append((began, when))
                    value = p.get('output', '')
                    output_chars[item_type] += len(value if isinstance(value, str) else json.dumps(value))
                elif item_type == 'message' and p.get('role') == 'assistant':
                    content = p.get('content', []); text = ''.join(c.get('text', '') for c in content if isinstance(c, dict))
                    assistant_chars[p.get('channel', 'unknown')] += len(text)
    totals = Counter()
    for u in usage.values(): totals.update(u)
    inputs = [u['input_tokens'] for u in usage.values()]
    merged = []
    for lo, hi in sorted(intervals):
        if merged and lo <= merged[-1][1]: merged[-1] = (merged[-1][0], max(hi, merged[-1][1]))
        else: merged.append((lo, hi))
    tool_seconds = sum((hi-lo).total_seconds() for lo,hi in merged)
    counts = Counter(); largest = []; tracked = subprocess.check_output(['git','ls-files','-z']).decode().split('\0')
    for name in tracked:
        f = Path(name)
        if not f.is_file() or f.suffix not in {'.java','.tsx','.ts','.py','.md'}: continue
        n = len(f.read_text(encoding='utf-8-sig').splitlines())
        group = 'other'
        if name.startswith('src/main/java/'): group = 'productionJava'
        elif name.startswith('src/test/'): group = 'backendTests'
        elif name.startswith('frontend/src/'): group = 'frontendTests' if '.test.' in name else 'frontendSource'
        elif name.startswith('scripts/'): group = 'scripts'
        elif name.startswith('docs/') or '/' not in name: group = 'documentation'
        counts[group+'Files'] += 1; counts[group+'Lines'] += n
        if group == 'productionJava': largest.append({'file':name,'lines':n})
    gitlog = subprocess.check_output(['git','log','--since='+start.isoformat(),'--until='+cutoff.isoformat(),
        '--format=COMMIT\t%h\t%aI\t%s','--numstat'],encoding='utf-8')
    commits = []
    for line in gitlog.splitlines():
        if line.startswith('COMMIT\t'):
            _, sha, at, subject = line.split('\t',3); commits.append({'sha':sha,'at':at,'subject':subject,'files':0,'added':0,'deleted':0})
        elif re.match(r'\d+\t\d+\t',line) and commits:
            added, deleted, _ = line.split('\t',2); commits[-1]['files'] += 1
            commits[-1]['added'] += int(added); commits[-1]['deleted'] += int(deleted)
    result = {'scope':{'threadId':args.thread_id,'start':start.isoformat(),'pause':cutoff.isoformat(),
        'wallHours':(cutoff-start).total_seconds()/3600,'files':[{'name':p.name,'bytes':p.stat().st_size} for p in paths]},
        'codex':{'responseRecords':len(usage),'totals':dict(totals),'uncachedInputTokens':totals['input_tokens']-totals['cached_input_tokens'],
            'uncachedInputPlusOutput':totals['input_tokens']-totals['cached_input_tokens']+totals['output_tokens'],
            'inputMedian':statistics.median(inputs),'inputMaximum':max(inputs),
            'cachedInputFraction':totals['cached_input_tokens']/totals['input_tokens'],
            'modelContexts':[{'model':k[0],'effort':k[1],'count':v} for k,v in models.items()]},
        'activity':{'recordKinds':dict(kinds),'toolCalls':dict(tools),'nestedToolInvocations':dict(nested),
            'commandCallPatterns':dict(patterns),'repeatedExactToolInputs':sum(n-1 for n in commands.values() if n>1),
            'toolOutputChars':dict(output_chars),'assistantTextChars':dict(assistant_chars),
            'toolAwaitWallSecondsUnion':tool_seconds,
            'toolAwaitByName':{k:{'paired':len(v),'sumSeconds':sum(v),'medianSeconds':statistics.median(v),'maxSeconds':max(v)} for k,v in durations.items()},
            'mostRepeatedGetContentPaths':[{'path':p,'calls':n} for p,n in files_read.most_common(15)],
            'compactions':compactions},'hourlyUsage':{k:dict(v) for k,v in sorted(hourly.items())},
        'repository':{'trackedSize':dict(counts),'largestJavaFiles':sorted(largest,key=lambda x:x['lines'],reverse=True)[:10],
            'commits':commits},'limits':['Raw input includes repeatedly supplied cached context; it is not unique new text or a monetary bill.',
            'One response_id counted once; reasoning tokens are already within output, cached input already within input.',
            'Command pattern counts are per tool input, may batch several commands and overlap categories; not exact process counts.',
            'Wall time includes user pauses and external waits; it is not exclusive coding or model compute time.',
            'Repository size is the current tracked snapshot, not a judgment that all code is necessary.']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'scope':result['scope'],'codex':result['codex'],'patterns':dict(patterns),
        'compactions':len(compactions),'tools':sum(tools.values()),'repositorySize':dict(counts),'commits':len(commits)},ensure_ascii=False))


if __name__ == '__main__': main()
