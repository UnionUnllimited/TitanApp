"""Titan patch for libXray, iOS only: Go's free memory given back once a minute, not every second.

libXray's memory/memory_ios.go (its first runXray) sets GOGC 10 and a 30 MiB soft limit and calls
debug.FreeOSMemory every second: a forced full GC and a scavenge each second, traffic or not, for as
long as the tunnel runs. Now every titanFreeEvery (titan_free.go, copied from libxray_free.go);
GOGC 10 and the first limit stay (the tunnel sets its own limit after each runXray). Every anchor
must be found: another libXray stops the build.
"""
import pathlib, shutil, sys

src = pathlib.Path(sys.argv[1])
here = pathlib.Path(__file__).parent
p = src / "memory/memory_ios.go"
code = p.read_text()
MARK = "// Titan patch: titan_free.go."
if MARK in code:
    print("libXray memory patch already applied")
    sys.exit(0)
for old, new in [
    ("func forceFree(interval time.Duration) {\n\tgo func() {\n\t\tfor {\n\t\t\ttime.Sleep(interval)\n"
     "\t\t\tdebug.FreeOSMemory()\n\t\t}\n\t}()\n}\n",
     "func forceFree(interval time.Duration) {\n\t" + MARK + "\n"
     "\tgo titanFreeLoop(interval, debug.FreeOSMemory, nil)\n}\n"),
    ("\t\tduration := time.Duration(interval) * time.Second\n\t\tforceFree(duration)\n",
     "\t\t// Titan patch: every titanFreeEvery, not every second (titan_free.go).\n"
     "\t\tforceFree(titanFreeEvery)\n"),
]:
    if code.count(old) != 1:
        sys.exit("memory_ios.go: anchor not found: " + old.strip().splitlines()[0])
    code = code.replace(old, new)
if (src / "memory/titan_free.go").exists():
    sys.exit("memory/titan_free.go exists already")
p.write_text(code)
shutil.copy(here / "libxray_free.go", src / "memory/titan_free.go")
print("libXray memory patch applied")
