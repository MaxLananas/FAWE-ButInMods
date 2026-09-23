#!/usr/bin/env python3
"""Static screen for the Fabric adapter, for a machine without the game jars.

The engine in {@code core/} is a plain library and compiles anywhere, but the
adapter in {@code fabric/} needs Minecraft, Fabric Loader and Brigadier on the
classpath. On a machine that has no access to those jars, this screen still
catches the mistakes that matter: a call to a method the mod never declared, a
signature that changed, a syntax error. Everything ECJ reports about an unknown
Minecraft type is filtered out, so a report printed here is a real defect.

Usage:
    python3 scripts/fabric_screen.py --engine /tmp/out/core --ecj /tmp/tools/ecj.jar

The compiler is Eclipse's batch compiler (ECJ) because it only needs a JRE; both
paths are configurable because neither is vendored in this repository.
"""
import argparse
import pathlib
import re
import subprocess
import sys

# A report that mentions one of these is a consequence of the game not being on
# the classpath. Everything else is reported, including a call to a method our
# own code never declared.
NOISE = (
    "cannot be resolved", "missing type", "The import ",
    "cannot be dereferenced", "is ambiguous", "refers to the missing",
    "for the type Object", "supertype method",
)
FOREIGN = ("net.minecraft", "com.mojang", "org.slf4j", "net.fabricmc", "javax.annotation")

arguments = argparse.ArgumentParser()
arguments.add_argument("--engine", default="/tmp/out/core", help="compiled classes of the engine")
arguments.add_argument("--ecj", default="/tmp/tools/ecj.jar", help="path to the Eclipse batch compiler")
arguments.add_argument("--java", default="java", help="java executable")
arguments.add_argument("--out", default="/tmp/fabric-screen", help="scratch directory for the classes")
options = arguments.parse_args()

root = pathlib.Path(__file__).resolve().parent.parent
sources = sorted(str(path) for path in (root / "fabric/src/main/java").rglob("*.java"))
command = [options.java, "-jar", options.ecj, "-source", "21", "-target", "21", "-proc:none",
           "-d", options.out, "-cp", options.engine]
out = subprocess.run(command + sources, capture_output=True, text=True).stderr

records, current = [], None
for line in out.splitlines():
    if re.match(r"^\d+\. (ERROR|WARNING) in ", line):
        current = [line]
        records.append(current)
    elif current is not None and not line.startswith("\t\t"):
        current.append(line)

real = 0
for record in records:
    text = "\n".join(record).strip()
    if "ERROR" not in record[0]:
        continue
    body = "\n".join(record[1:])
    if any(word in body for word in NOISE) or any(word in body for word in FOREIGN):
        continue
    if re.search(r"The method (literal|argument)\(String", body):
        # The static imports of Minecraft's literal/argument helpers cannot
        # resolve without the game on the classpath.
        continue
    real += 1
    print(text)
print(f"real fabric errors: {real}")
sys.exit(1 if real else 0)
