from pathlib import Path
import os, subprocess, json, hashlib, shutil
QA=Path(__file__).resolve().parent
SITE=QA.parents[1]/"vision"
PROJECT=QA.parents[1]/"android"
KOTLIN=PROJECT/"app/src/main/java/com/example/arassemblydemo"
ASSETS=PROJECT/"app/src/main/assets/shelf-phone"
NAMES="BoardFaceRules MeasuredBoardProjection BoardPlacementCue InstallTagGeometry JoinedFaceProjection RecentFrameSlots FrameSlotGrid BackPanelGuidance FinalAssemblyGuidance FinalGuideEnglish BoardLocalGuidance FinderHighlight".split()
CACHE=QA/"cache"
BUILD=CACHE/"build"
STDLIB=CACHE/"kotlin-stdlib-js-2.2.10.klib"
SHASUM="8068c17b94576ee46e3053aef4c220f1693afbd7773a58a1dcf23f53367216d8"
def sha(path):
 return hashlib.sha256(path.read_bytes()).hexdigest()
def compile_core():
 assert sha(STDLIB)==SHASUM
 BUILD.mkdir(parents=True,exist_ok=True)
 deps=Path.home()/".gradle/caches/modules-2/files-2.1"
 artifacts={"kotlin-compiler-embeddable":"2.2.10","kotlin-stdlib":"2.2.10","kotlin-script-runtime":"2.2.10","kotlin-daemon-embeddable":"2.2.10","kotlin-reflect":"1.6.10","kotlinx-coroutines-core-jvm":"1.8.0"}
 jars=[]
 for art,ver in artifacts.items():
  group="org.jetbrains.kotlinx" if art.startswith("kotlinx-") else "org.jetbrains.kotlin"
  jars.append(next((deps/group/art/ver).glob("*/*.jar")))
 java=Path(os.environ.get("JAVA_HOME", ""))/"bin"/("java.exe" if os.name=="nt" else "java")
 command=[str(java) if java.exists() else "java","-Xmx1600m","-cp",os.pathsep.join(map(str,jars)),"org.jetbrains.kotlin.cli.js.K2JSCompiler","-libraries",str(STDLIB),"-ir-output-name","shelfcore"]
 paths=[KOTLIN/(name+".kt") for name in NAMES]+[QA/"Bridge.kt",QA/"Json.kt"]
 subprocess.run(command+["-ir-output-dir",str(BUILD/"klib"),"-Xir-produce-klib-file"]+list(map(str,paths)),check=True)
 subprocess.run(command+["-ir-output-dir",str(BUILD/"js"),"-Xir-produce-js","-Xir-dce","-Xinclude="+str(BUILD/"klib/shelfcore.klib")],check=True)
 return (BUILD/"js/shelfcore.js").read_text("utf8")

def geometry_data():
 read=lambda name: json.loads((ASSETS/name).read_text("utf-8-sig"))
 fields=lambda obj,keys:{k:obj[k] for k in keys.split()}
 master=read("measured-board-planes.json")
 measured=fields(master,"schema assembly sourceSha256")
 measured["registration"]=[fields(v,"id part slot") for v in master["registration"]]
 measured["faces"]=[]
 for item in master["faces"]:
  face=fields(item,"part face axisIndices normalAxis outline holes bounds cadOrigin")
  face["markers"]=[fields(m,"slot id corners") for m in item["markers"]]
  measured["faces"].append(face)
 registry=read("board-faces.json")
 assert registry["assembly"]==measured["assembly"]
 rings=read("finder-cad-rings.json")
 assert rings["measuredSource"]==measured["sourceSha256"]
 assert {(f["part"],f["face"]) for f in rings["faces"]}=={(f["part"],f["face"]) for f in measured["faces"]}
 return {"sites":[fields(v,"id part slot axis candidateCenter") for v in registry["sites"]],
  "measured":measured,"finderCad":rings,"tasks":[fields(t,"id kind group context stationary members") for t in read("workflow.json")["tasks"]]}
def package(core):
 english=(ASSETS/"text-resources.js").read_text("utf8")
 line=next(line for line in english.splitlines() if "const english=" in line)
 english=english.replace(line," const english=true;")
 # Pass a private target when loading Android's English guide-text table.
 english=english.replace("})(globalThis);","})(display);")
 assert "})(display);" in english
 english=english.replace("if(typeof module!=='undefined'&&module.exports)module.exports=api;","")
 config=json.dumps(geometry_data(),ensure_ascii=False,separators=(",",":"))
 output=SITE/"vision.js"; SITE.mkdir(exist_ok=True,parents=True)
 output.write_text("/* Generated from the R91 geometry sources; see qa/vision/build.py. */\n"+core+
    "\n;(function(){const display={};\n"+english+"\nconst labels=display.AssemblyText;\nconst config="+config+
    ";\n"+(QA/"facade.js").read_text("utf-8-sig")+"\n})();\n","utf8")
 record={"kotlin":"2.2.10","library_sha256":SHASUM,"kotlin_source_sha256":{n+".kt":sha(KOTLIN/(n+".kt")) for n in NAMES},
  "display_source_sha256":sha(ASSETS/"text-resources.js"),"input_files":{n:sha(ASSETS/n) for n in ["board-faces.json","measured-board-planes.json","finder-cad-rings.json","workflow.json"]},
  "output_sha256":sha(output),"bytes":output.stat().st_size}
 (QA/"build-record.json").write_text(json.dumps(record,indent=2)+"\n","utf8")
 print("Bundled",record["bytes"],record["output_sha256"])
if __name__=="__main__":
 import sys
 package((BUILD/"js/shelfcore.js").read_text("utf8") if "--package-only" in sys.argv else compile_core())
