"""Release preflight for mobile UI, speech and installation cues. Standard library only."""
from pathlib import Path
from html.parser import HTMLParser
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "tools/shelf-ui"
NATIVE = ROOT / "app/src/main/java/com/example/arassemblydemo"
ASSETS = ROOT / "app/src/main/assets/shelf-phone"
HAN = re.compile(r"[\u3400-\u9fff]")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def check_installation_cues():
    catalog = (NATIVE / "FinalGuideEnglish.kt").read_text(encoding="utf8")
    fixed = dict(re.findall(r'^\s*"([^"]+)" to "([^"]+)",?$', catalog, re.M))
    if any(HAN.search(value) or not value.strip() for value in fixed.values()):
        raise ValueError("Installation cue has incomplete English")
    # Stable model identifiers, plus the three templates covered by the dynamic-cue unit test.
    internal = {"右侧板", "左侧板", "侧板", "背板", "层", "层${it}板", "左侧板-", "右侧板-", "背板-"}
    dynamic = {"把缺口转向", "缺口朝", "左边", "右边", "画面上方", "画面下方", "提起层板，对准第${row+1}排再放下。"}
    for name in ("FinalAssemblyGuidance.kt", "BackPanelGuidance.kt"):
        for i, line in enumerate((NATIVE / name).read_text(encoding="utf8").splitlines(), 1):
            for value in LITERAL.findall(line):
                if HAN.search(value) and value not in internal | dynamic and value not in fixed:
                    raise ValueError(f"{name}:{i}: add English for installation cue: {value}")


def check_local_photo_advice():
    # Source-language labels also identify registered parts and measured positions.
    # Limit exceptions to the pre-existing input/formatting expressions, by count.
    from collections import Counter
    allowed = {
        "AiReviewPolicy": {"层": 2},
        "LayerPhotoReview": {"install:层${it}板": 1, "install:层5板": 1, "先调整这块板": 1},
        "AiFrameOrientation": {
            **dict.fromkeys(("左手边这块", "右手边这块", "上面这块", "下面这块"), 2),
            "把一半平放着转正": 1, "把${label}平放着转正": 1, "install:左侧板": 1,
        },
    }
    pair = re.compile(r'copy\.say\("([^"\n]*)",\s*"([^"\n]*)"\)')
    for name, exceptions in allowed.items():
        source = (NATIVE / (name + ".kt")).read_text("utf8")
        if name == "AiFrameOrientation":
            source = source[source.index("fun apply("):source.index("private fun faceSignature")]
        for zh, en in pair.findall(source):
            if not zh.strip() or not en.strip() or HAN.search(en):
                raise ValueError(f"{name}: invalid English photo guidance for {zh}")
        literals = Counter(s for s in LITERAL.findall(pair.sub("", source)) if HAN.search(s))
        extra = literals - Counter(exceptions)
        if extra:
            raise ValueError(f"{name}: add English display and speech for {list(extra)}")


def check_virtual_assembly():
    """The rehearsal shares CAD with live guidance, but owns its language and transient state."""
    workshop = ASSETS.parent / "virtual-assembly"
    source = "\n".join(path.read_text("utf8") for path in sorted(workshop.glob("*.js")))
    literal = r"""(?:'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*")"""
    pair = re.compile(rf"\btr\(\s*({literal})\s*,\s*({literal})\s*\)")
    for call in re.finditer(r"\btr\(", source):
        match = pair.match(source, call.start())
        if not match or not all(s[1:-1].strip() for s in match.groups()) or HAN.search(match[1]) or not HAN.search(match[2]):
            line = source.count("\n", 0, call.start()) + 1
            raise ValueError(f"workshop.js:{line}: provide English and Chinese for the rehearsal label")
    raw = pair.sub("pairedText", source)
    # Direct setters and the rehearsal's element helper are both visible/UI copy sinks.
    sink = rf"""(?:\.(?:textContent|innerText|ariaLabel|title)\s*=\s*|\belement\(\s*{literal}\s*,\s*{literal}\s*,\s*|\bsection\(\s*)({literal})"""
    for match in re.finditer(sink, raw):
        if any(char.isalpha() for char in match[1]):
            raise ValueError(f"workshop.js: unpaired label {match[1]}")

    class FirstFrame(HTMLParser):
        hidden_body = False

        def handle_starttag(self, tag, attrs):
            if tag == "body":
                self.hidden_body = "hidden" in dict(attrs)
            for name, value in attrs:
                if name in ("title", "aria-label", "alt", "placeholder") and value:
                    raise ValueError("Virtual rehearsal HTML label must be set in the selected language")

        def handle_data(self, value):
            if value.strip():
                raise ValueError("Virtual rehearsal HTML contains fixed text before language selection")

    html = FirstFrame()
    html.feed((workshop / "index.html").read_text("utf8"))
    if not html.hidden_body:
        raise ValueError("Hide the virtual rehearsal until its selected-language first frame")
    activity = (NATIVE / "VirtualAssemblyActivity.kt").read_text("utf8")
    if "addJavascriptInterface" in activity or re.search(r"\b(?:AssemblyNative|localStorage|indexedDB)\b", source):
        raise ValueError("Virtual rehearsal must stay isolated from native actions and saved assembly progress")


if __name__ == "__main__":
    for script in (ROOT / "tools/check_android_locales.py", WEB / "check_web_locales.py"):
        subprocess.run([sys.executable, "-X", "utf8", str(script)], cwd=ROOT, check=True)
    check_installation_cues()
    check_local_photo_advice()
    check_virtual_assembly()
    print("Production bilingual resources, slot guidance and packaged assets verified.")
