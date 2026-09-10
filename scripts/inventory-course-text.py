"""Count local courseware text without indexing or calling model providers."""
import argparse
import concurrent.futures
import json
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path
from xml.etree import ElementTree

from pypdf import PdfReader


def extract(path):
    if path.suffix.lower() == '.pptx':
        with zipfile.ZipFile(path) as archive:
            slides = sorted((n for n in archive.namelist()
                             if re.fullmatch(r'ppt/slides/slide\d+\.xml', n)),
                            key=lambda n: int(re.search(r'slide(\d+)\.xml', n).group(1)))
            return ['\n'.join(t.text or '' for t in ElementTree.fromstring(archive.read(n)).iter(
                        '{http://schemas.openxmlformats.org/drawingml/2006/main}t')) for n in slides]
    return [page.extract_text() or '' for page in PdfReader(path).pages]


def inspect(path, root, output):
    relative = path.relative_to(root)
    row = dict(course=relative.parts[0], path=str(path), relative=str(relative),
               format=path.suffix.lower(), bytes=path.stat().st_size)
    try:
        pages = extract(path)
        counts = [len(re.sub(r'\s', '', p)) for p in pages]
        text = '\n'.join(pages)
        dest = output / 'text' / relative.with_suffix(relative.suffix + '.txt')
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text('\n\n'.join(f'--- 第 {i + 1} 页 ---\n{p}' for i, p in enumerate(pages)), encoding='utf-8')
        row.update(status='ok', pages=len(pages), characters=sum(counts),
                   hanCharacters=len(re.findall(r'[\u3400-\u9fff]', text)),
                   englishWords=len(re.findall(r'[A-Za-z]+', text)),
                   sparsePages=sum(c < 20 for c in counts), textPath=str(dest))
    except Exception as error:
        row.update(status='failed', error=f'{type(error).__name__}: {error}')
    return row


def link(label, path):
    return f'[{label}](<{Path(path).resolve().as_posix()}>)'


def mark_encoded_text(row):
    if row['status'] != 'ok' or row['format'] != '.pdf':
        return
    text = Path(row['textPath']).read_text(encoding='utf-8')
    encoded = sum(len(m.group()) for m in re.finditer(r'/(?:i?\d+|cid:\d+)', text))
    if encoded > row['characters'] * .2:
        row.update(status='unreadable', error='PDF输出主要为字形编码，不计入可读文字量；需要OCR或其他来源')


def course_set(row):
    parts = Path(row['relative']).parts
    course, name = row['course'], Path(row['path']).name
    if course == '数据结构':
        return ('辛运帷目录版', '主课件', '独立教师目录') if '辛运帷' in parts else ('根目录版（教师未确认）', '主课件', '用户确认与辛运帷为两套；包含3份复习专题')
    if course == '计算机组成原理':
        if '网课' in parts:
            return ('网课版', '主课件', '独立来源目录；含配套习题课')
        return ('李涛目录／英文Chapter版', '主课件', 'Chapter_01–06；与中文编号版分开') if name.startswith('Chapter_') else ('李涛目录／中文第1–6章版', '主课件', '中文译本课件；不与英文版相加')
    if course == '数据库系统':
        return ('英文Week周次版', '主课件', 'Week1–16连续命名，首页为同一教学团队') if name.lower().startswith('week') else ('数字编号版（教师未确认）', '待处理', '1–17另成一组；15份乱码、1份未提取到文本')
    if course == '人工智能导论':
        return ('南开教学团队PPTX版', '主课件', '课程概述列出联合主讲；按完整课程而非逐教师拆开') if row['format']=='.pptx' else ('外部英文补充PDF', '补充', 'RNN与Attention两讲；不同来源，单独列出')
    if course == '算法设计和分析':
        if '苏明' in parts:
            return ('苏明／Ch章节版', '主课件', '独立教师目录中的Ch编号讲义') if re.fullmatch(r'Ch\d+\.pdf', name) else ('苏明／习题与论文补充', '补充', 'Exercise1及两篇论文，不计入章节版')
        return ('根目录Chap版（教师未确认）', '主课件', '与苏明目录分开；保留该目录的配套专题')
    if course == '编译原理':
        return ('第1–10章PPTX版', '主课件', '章节目录是同一套的组成部分，不拆成10套') if row['format']=='.pptx' else ('根目录10.pdf', '待处理', '来源未确认且存在字形编码问题')
    return ('2024版', '主课件', '连续章节且统一2024文件名') if course == '操作系统' else ('当前章节PPTX版（教师未确认）', '主课件', '目前未发现独立的第二套；同章主题拆分保留')


def write_sets(rows, output):
    groups = defaultdict(list)
    for row in rows:
        label, kind, basis = course_set(row)
        groups[(row['course'], label, kind, basis)].append(row)
    ranked = []
    for (course, label, kind, basis), items in groups.items():
        good = [r for r in items if r['status']=='ok' and r.get('characters',0)>0]
        ranked.append(dict(course=course, label=label, kind=kind, basis=basis, files=len(items),
                           readableFiles=len(good), characters=sum(r['characters'] for r in good),
                           hanCharacters=sum(r['hanCharacters'] for r in good),
                           pages=sum(r['pages'] for r in good), items=items))
    ranked.sort(key=lambda r:(r['kind']!='主课件', -r['characters']))
    lines=['# 按教学来源与课件版本分别统计', '',
           '本表替代按课程文件夹相加的选课排名。按独立课件套版比较；联合授课的一套课件不按教师人数拆开。同一老师目录中的中英文版本也分开，避免重复计入。没有教师信息时只标版本，分组依据见各清单。', '',
           '复用已提取文本，没有重新解析或调用embedding。字数为非空白字符，不是token；中英文的字符数不能直接当作相同学习体量。未OCR、未做逐段语义去重。', '',
           '|课程|独立课件套版|类型|文件数／可读|可读页数|非空白字符|其中汉字|',
           '|---|---|---|---:|---:|---:|---:|']
    for g in ranked:
        lines.append(f"|{g['course']}|{g['label']}|{g['kind']}|{g['files']}／{g['readableFiles']}|{g['pages']}|{g['characters']:,}|{g['hanCharacters']:,}|")
    for g in ranked:
        lines += ['', f"## {g['course']}：{g['label']}", '', '分组依据：'+g['basis']+'。', '',
                  '|该套原始文件|可提取字符|解析文本|','|---|---:|---|']
        for r in g['items']:
            available = r['status']=='ok' and r.get('characters',0)>0
            amount=f"{r['characters']:,}" if available else '无法提取可读文本'
            lines.append(f"|{link(Path(r['path']).name,r['path'])}|{amount}|{link('查看',r['textPath']) if available else '待处理'}|")
    (output/'sets.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    (output/'sets.json').write_text(json.dumps(ranked,ensure_ascii=False,indent=2),encoding='utf-8')


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path('D:/Download/BDNetdisk_DL'))
    parser.add_argument('--output', type=Path, default=Path('output/course-text-inventory'))
    parser.add_argument('--reuse-extracted', action='store_true', help='Regenerate counts/report from existing extracted files')
    args = parser.parse_args()
    root, output = args.root.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    files = sorted(p for course in root.iterdir() if course.is_dir()
                   for p in (course / '课件').rglob('*')
                   if p.is_file() and p.suffix.lower() in ('.pptx', '.pdf') and not p.name.startswith('~$'))
    if args.reuse_extracted:
        rows = json.loads((output/'inventory.json').read_text(encoding='utf-8'))['files']
    else:
        print(f'Parsing {len(files)} courseware files', flush=True)
        rows = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(inspect, p, root, output) for p in files]
            for future in concurrent.futures.as_completed(futures):
                rows.append(future.result())
                if len(rows) % 25 == 0 or len(rows) == len(files):
                    print(f'Parsed {len(rows)}/{len(files)}', flush=True)
    for row in rows:
        mark_encoded_text(row)
    rows.sort(key=lambda r: r['relative'])
    groups = defaultdict(list)
    for row in rows:
        groups[row['course']].append(row)
    summaries = []
    for course, items in groups.items():
        good = [r for r in items if r['status'] == 'ok']
        summary = dict(course=course, files=len(items), failed=len(items)-len(good),
                       pptx=sum(r['format']=='.pptx' for r in items),
                       pdf=sum(r['format']=='.pdf' for r in items))
        for key in ('pages', 'characters', 'hanCharacters', 'englishWords', 'sparsePages'):
            summary[key] = sum(r[key] for r in good)
        summary['pptxCharacters'] = sum(r['characters'] for r in good if r['format']=='.pptx')
        summary['pptxBytes'] = sum(r['bytes'] for r in items if r['format']=='.pptx')
        summaries.append(summary)
        lines = [f'# {course}：完整课件目录', '', link('打开原始课件目录', root/course/'课件'), '',
                 '|课件原件|格式|页数|非空白字符|汉字|少文本页|解析文本|', '|---|---|---:|---:|---:|---:|---|']
        for row in items:
            title = str(Path(row['path']).relative_to(root/course/'课件'))
            if row['status'] == 'ok':
                lines.append(f"|{link(title,row['path'])}|{row['format']}|{row['pages']}|{row['characters']:,}|{row['hanCharacters']:,}|{row['sparsePages']}|{link('查看',row['textPath'])}|")
            else:
                lines.append(f"|{link(title,row['path'])}|{row['format']}|解析异常||||{row['error']}|")
        (output/(course+'.md')).write_text('\n'.join(lines)+'\n',encoding='utf-8')
    summaries.sort(key=lambda r:r['characters'],reverse=True)
    report=['# 各课程完整课件文字量统计', '',
            '范围：各课程的“课件”目录及其子目录中的 PPTX/PDF。排除目录外教材、往年试卷和视频；课件目录内的复习、习题课及不同教师版本按原样计入，未去重。', '',
            '统计的是非空白字符数，不是 token 数或纯汉字数。PPTX 读取幻灯片文本，不含备注及图片 OCR；PDF 使用 pypdf 文本提取，无 OCR。少文本页指不足20个非空白字符，可能是图片页或封面。统计工具与后端 Tika 不完全相同，供选择课程；最终检索范围以实际入库文本为准。', '',
            '这些完整课程尚未全部入库，不要把此目录与此前每课6文件的实验范围混用。字形编码无法解码的PDF排除出可读页数和文字量，不以乱码充当课件内容；原文件仍列在清单。', '',
            '|课程|PPTX|PDF|可读页数|非空白字符|其中汉字|少文本页|异常文件|完整原件清单|',
            '|---|---:|---:|---:|---:|---:|---:|---:|---|']
    for r in summaries:
        report.append(f"|{r['course']}|{r['pptx']}|{r['pdf']}|{r['pages']:,}|{r['characters']:,}|{r['hanCharacters']:,}|{r['sparsePages']}|{r['failed']}|{link('打开',output/(r['course']+'.md'))}|")
    report += ['', '## 仅 PPTX 比较', '', '|课程|文件数|PPTX 文件总量 MiB|非空白字符|','|---|---:|---:|---:|']
    for r in sorted(summaries,key=lambda r:r['pptxCharacters'],reverse=True):
        if r['pptx']:
            report.append(f"|{r['course']}|{r['pptx']}|{r['pptxBytes']/1024/1024:.1f}|{r['pptxCharacters']:,}|")
    failures=[r for r in rows if r['status']!='ok']
    report += ['',f'解析异常或不可读：{len(failures)} 份。逐文件结果保存在同目录 inventory.json。']
    (output/'README.md').write_text('\n'.join(report)+'\n',encoding='utf-8')
    (output/'inventory.json').write_text(json.dumps(dict(root=str(root),summary=summaries,files=rows),ensure_ascii=False,indent=2),encoding='utf-8')
    write_sets(rows, output)
    print(json.dumps(summaries,ensure_ascii=False,indent=2),flush=True)
    print(f'Report: {output / "README.md"}',flush=True)


if __name__ == '__main__':
    main()
