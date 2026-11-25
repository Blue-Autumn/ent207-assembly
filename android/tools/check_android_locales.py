"""Check shipped Android copy, format arguments, and common native UI/speech sinks."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
SOURCE = ROOT / 'app/src/main/java/com/example/arassemblydemo'
UI_FILES = (
    'MainActivity', 'LocalAccountActivity', 'AssemblyGuideActivity', 'JointGuideActivity',
    'AiReviewActivity', 'AiSettingsActivity', 'AiPhotoCueView', 'AiReplySpeech',
    'AssemblyTargetScanActivity', 'AssemblyVoiceActivity', 'PortfolioTabBar', 'VirtualAssemblyActivity',
)
FORMAT = re.compile(r'%(?:([1-9]\d*)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdfoxXeEgGaAcCbBhHtT%n])')
# Match only Android presentation and speech calls. Intent keys, model IDs and event protocols
# may carry stable Chinese identifiers, so scanning every literal would reject valid data.
SINK = re.compile(
    r'(?:\b(?:contentDescription|text|hint)\s*=\s*|'
    r'\b(?:setTitle|setMessage|setText|setHint|setPositiveButton|setNegativeButton|label|quiet|word|'
    r'speak|speakText)\s*\(\s*)"([^"\n]*)"'
)


def strings(qualifier):
    result = {}
    for file in (RES / qualifier).glob('*.xml'):
        for node in ET.parse(file).getroot().findall('string'):
            if node.get('translatable') == 'false':
                continue
            key = node.attrib['name']
            if key in result:
                raise ValueError(f'{file}: duplicate resource {key}')
            result[key] = ''.join(node.itertext())
    return result


def format_args(text):
    tokens = FORMAT.findall(text)
    if '%' in FORMAT.sub('', text):
        raise ValueError(f'invalid Android format specifier in {text!r}')
    return Counter((pos or str(i+1), kind) for i,(pos,kind) in enumerate(tokens)
                   if kind not in ('%', 'n'))


def main():
    zh, en = strings('values'), strings('values-en')
    issues = []
    for key in sorted(zh.keys() | en.keys()):
        if key not in en or key not in zh:
            issues.append(f'{key}: missing {"English" if key not in en else "default"} resource')
        elif key != 'language_title' and re.search(r'[\u4e00-\u9fff]', en[key]):
            issues.append(f'{key}: Chinese text in English translation')
        elif not en[key].strip() or format_args(zh[key]) != format_args(en[key]):
            issues.append(f'{key}: empty translation or format argument mismatch')
    for screen in UI_FILES:
        file = SOURCE / (screen + '.kt')
        for line_number, line in enumerate(file.read_text('utf8').splitlines(), 1):
            for match in SINK.finditer(line):
                if re.search(r'[a-zA-Z\u4e00-\u9fff]', match[1]):
                    issues.append(f'{file.name}:{line_number}: user-facing literal; use a string resource')
    if issues:
        raise SystemExit('\n'.join(issues))
    print(f'Paired Android strings: {len(en)}. Native screen and speech literal checks passed.')


if __name__ == '__main__':
    main()
