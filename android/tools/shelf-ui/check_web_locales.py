"""Check the phone's web copy before packaging. No third-party dependencies."""
import json
import re
from html.parser import HTMLParser
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1] / 'app/src/main/assets/shelf-phone'
HAN = re.compile(r"[\u3400-\u9fff]")
SLOTS = re.compile(r"\{(\w+)\}")
QUOTED = re.compile(r"""(["'\x60])((?:\\.|(?!\1).)*?)\1""")
PHRASE = re.compile(r"""AssemblyText(?:\?\.|\.)phrase\((['"])([^'"]+)\1\)""")

# These older shared modules also run in standalone desktop fixtures. Their
# Chinese model identifiers, compatibility fallback strings and Chinese speech
# are kept deliberately. Cap each reviewed occurrence so adding the same raw
# label at a second UI sink still fails. New bilingual copy uses the catalogue.
LEGACY = ('app.js','guide-cue.js','narration-dispatcher.js','work-controls.js')
TOKENS = re.compile(r"""//[^\n]*|/\*[\s\S]*?\*/|'(?:\\[\s\S]|[^'\\])*'|"(?:\\[\s\S]|[^"\\])*"|\x60(?:\\[\s\S]|[^\x60\\])*\x60""")
ENGLISH_SINK = re.compile(
    r"""(?:\.(?:textContent|innerText)\s*=\s*|\b(?:button|speak)\s*\(\s*|\belement\(\s*['"][^'"]+['"]\s*,\s*|\bsetAttribute\(\s*['"](?:aria-label|alt|title)['"]\s*,\s*|\btext\s*:\s*)(['"])([A-Za-z][A-Za-z0-9 ,.'!?°-]*)\1""")

def javascript_for_audit(name,src):
    return PHRASE.sub('translated',src)

def legacy_strings(src):
    for match in TOKENS.finditer(src):
        if match.group()[0] in ('"', "'", '\x60') and HAN.search(match.group()):
            yield match.group()[1:-1],src.count('\n',0,match.start())+1

def check_shared_copy(name,src,limits):
    hits={}
    for phrase,line in legacy_strings(javascript_for_audit(name,src)):
        hits[phrase]=hits.get(phrase,0)+1
        if hits[phrase]>limits.get(phrase,0):
            raise ValueError(f'{name}:{line} new raw Chinese; use a paired assembly message: {phrase}')

def catalogue(name):
    src = (ROOT / 'text-resources.js').read_text(encoding='utf8')
    decoder = json.JSONDecoder(object_pairs_hook=no_duplicates)
    return decoder.raw_decode(src.split(' const '+name+'=', 1)[1])[0]

def no_duplicates(items):
    result = dict(items)
    if len(result) != len(items):
        raise ValueError('Duplicate web translation key')
    return result

def check():
    messages, phrases = catalogue('messages'), catalogue('phrases')
    for key, pair in messages.items():
        if len(pair)!=2 or not all(isinstance(s,str) and s.strip() for s in pair):
            raise ValueError(f'Missing translation: {key}')
        zh, en = pair
        if HAN.search(en) or sorted(SLOTS.findall(zh))!=sorted(SLOTS.findall(en)):
            raise ValueError(f'English or placeholders incomplete: {key}')
    for source, en in phrases.items():
        if not en.strip() or HAN.search(en):
            raise ValueError(f'Missing English: {source}')

    # These values identify physical parts and native events. Their spelling is
    # part of the saved-project and detector contracts in both languages.
    protocol = {'install:左侧板','install:右侧板','install:背板','侧板','左侧板-','背板','install:层',
                '板','层','板-1','主摄','广角','广角·近','正在看方向','A/B面','正反面'}
    for filename in ('mobile.js','scene-feedback.js','renderer.js','row-lesson.js'):
        for number, line in enumerate((ROOT/filename).read_text('utf8').splitlines(),1):
            if line.lstrip().startswith('//'):
                continue
            for _, phrase in PHRASE.findall(line):
                if phrase not in phrases:
                    raise ValueError(f'{filename}:{number} missing phrase: {phrase}')
            for _, value in QUOTED.findall(PHRASE.sub('translated',line)):
                if HAN.search(value) and value not in protocol:
                    raise ValueError(f'{filename}:{number} unlocalised UI or speech: {value}')
    resources=(ROOT/'text-resources.js').read_text('utf8')
    narration=(ROOT/'narration-dispatcher.js').read_text('utf8')
    for state in set(re.findall(r'\b(install_\w+):',narration)):
        if not re.search(r'\b'+state+r':', resources):
            raise ValueError(f'Missing English guidance or speech for {state}')
    limits=json.loads((HERE/'web-locale-legacy.json').read_text('utf8'))
    for name in LEGACY:
        src=(ROOT/name).read_text('utf8')
        for _, phrase in PHRASE.findall(src):
            if phrase not in phrases:
                raise ValueError(f'{name} missing phrase: {phrase}')
        check_shared_copy(name,src,limits[name])
    for name in (*LEGACY,'mobile.js','row-lesson.js','scene-feedback.js','renderer.js'):
        src=javascript_for_audit(name,(ROOT/name).read_text('utf8'))
        for match in ENGLISH_SINK.finditer(src):
            number=src.count('\n',0,match.start())+1
            raise ValueError(f'{name}:{number} untranslated English UI or speech: {match[2]}')
    class StaticCopy(HTMLParser):
        static = False
        tag = None
        def check_text(self, value):
            if value and HAN.search(value) and value.strip() not in phrases:
                raise ValueError(f'index.html:{self.getpos()[0]} missing phrase: {value.strip()}')
        def handle_data(self,value):
            self.check_text(re.sub(r' · [0-9.]+$','',value) if self.tag=='title' else value)
            if HAN.search(value) and self.tag!='title' and not self.static:
                raise ValueError(f'index.html:{self.getpos()[0]} Chinese label needs data-static-copy')
        def handle_starttag(self,tag,attrs):
            self.static = 'data-static-copy' in dict(attrs)
            self.tag = tag
            for key,value in attrs:
                if key in ('aria-label','title','alt'):
                    self.check_text(value)
    StaticCopy().feed((ROOT/'index.html').read_text('utf8'))
    print(f'Web locales: {len(messages)} paired messages, {len(phrases)} checked phrases.')
if __name__=='__main__':
    check()

