"""Split the official single-file OpenCV distribution into a small loader and lazy wasm payload."""
import base64,hashlib,re,shutil
from pathlib import Path
from build import QA,SITE
OFFICIAL="https://docs.opencv.org/4.13.0/opencv.js"
SHA="63366510248adf3a7eddf3e793dd825404efb7df3749f4d6f8557c7fa4ca8aa0"
def package():
 raw=(QA/"cache/opencv-4.13.js").read_bytes()
 assert hashlib.sha256(raw).hexdigest()==SHA
 source=raw.decode("utf8")
 pattern=r'var wasmBinaryFile="data:application/octet-stream;base64,([A-Za-z0-9+/=]+)"'
 match=re.search(pattern,source);assert match
 wasm=base64.b64decode(match[1],validate=True);assert wasm[:4]==b"\0asm"
 loader=source[:match.start()]+'var wasmBinaryFile="opencv-4.13.wasm"'+source[match.end():]
 (SITE/"opencv-4.13.wasm").write_bytes(wasm)
 (SITE/"opencv-4.13.js").write_text("/* OpenCV 4.13.0: binary extracted from the embedded data URL. */\n"+loader,"utf8")
 print(len(loader),len(wasm))
if __name__=="__main__":package()
