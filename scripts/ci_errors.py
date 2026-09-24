#!/usr/bin/env python3
"""Report a Gradle log of a Fabric compile as check annotations.

The log itself cannot be downloaded from this environment, so the lines that
matter are printed as annotations instead. Annotations are truncated in the
middle when they are long, so the report is split into chunks. Delete this
script with the workflow it serves.
"""
import pathlib
import sys

LIMIT = 3400
KEEP = ("error:", "symbol:", "location:", "required:", "found:", "reason:",
        "BUILD FAILED", "FAILURE:", "> Task ", "> Could not", "warning:",
        "error: ", "* What went wrong", "Errors", "Note:")


def main():
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/fabric.log")
    text = path.read_text(errors="replace") if path.exists() else ""
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if any(marker in line for marker in KEEP):
            lines.append(line.replace("/home/runner/work/FAWE-ButInMods/FAWE-ButInMods/", ""))
    if not lines:
        lines = ["the log kept no error and no warning"] + text.splitlines()[-10:]
    errors = sum(1 for line in lines if " error:" in line)
    report = "\n".join(lines)
    chunks = [report[i:i + LIMIT] for i in range(0, len(report), LIMIT)] or [""]
    total = len(chunks) + 1
    print("::error title=compile summary::%d error lines, %d characters" % (errors, len(report)))
    for index, chunk in enumerate(chunks, start=2):
        message = chunk.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
        print("::error title=compile %d/%d::%s" % (index, total, message))


if __name__ == "__main__":
    main()
