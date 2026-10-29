"""Reads the log of a server run and says what the mod did, and what failed.

The output of a step that fails is not readable from the run page of GitHub, and
an annotation that is too long is dropped: the findings of this script are
therefore short, and what is found is named - the class and the line that threw,
not a page of log.

    python3 tools/logcheck.py /tmp/server.log

It prints one workflow command per finding and answers 1 when the log holds
something the run should not have.
"""

import argparse
import pathlib
import re
import sys

# A stack trace: the lines below the message that says what went wrong.
TRACE = re.compile(r"^\s*(at |Caused by:)")

# What the run should not hold: the mod that did not start, a mixin that did
# not apply, a method that is not there any more.
BROKEN = re.compile(
    r"FAWE-BIM failed to start|Mixin apply failed|MixinApplyError"
    r"|NoSuchFieldError|NoSuchMethodError|ClassNotFoundException"
)

# The lines of the mod itself.
OWN = re.compile(r"FAWE-BIM")

# How much of the log around a finding is worth reading.
CONTEXT = 12
MAX = 900


def command(title, message):
    """A workflow command, with what it says escaped the way it has to be."""
    message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return f"::error title={title}::{message}" if title else f"::notice title={title}::{message}"


def around(lines, index, before=CONTEXT, after=CONTEXT):
    """The lines around the one at the index, as one string."""
    start = max(0, index - before)
    end = min(len(lines), index + after + 1)
    return " | ".join(line.strip() for line in lines[start:end] if line.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", help="the log of the run")
    parser.add_argument("--quiet", action="store_true",
                        help="say nothing when the log holds nothing wrong")
    args = parser.parse_args()

    path = pathlib.Path(args.log)
    if not path.exists():
        print(command("the log is missing", f"{path} does not exist"))
        return 1

    text = path.read_text(errors="replace")
    lines = text.splitlines()

    # What the mod said about itself: it is the first thing to look at.
    own = [line.strip() for line in lines if OWN.search(line)]
    if own:
        print(command("", "the mod said: " + " | ".join(own[:12])[:MAX]))

    # The mod has to say that it is ready: a server that came up without it
    # has a mod that did not finish its start.
    ready = [line for line in lines if "FAWE-BIM ready" in line]
    if not ready:
        print(command("the mod never said it was ready",
                      "the log holds: " + (" | ".join(own[:12]) if own else "no line of the mod at all")[:MAX]))
        return 1

    broken = [(index, line) for index, line in enumerate(lines) if BROKEN.search(line)]
    traces = [(index, line) for index, line in enumerate(lines) if TRACE.match(line)]

    if broken:
        index, line = broken[0]
        print(command("the mod did not start cleanly",
                      f"line {index + 1}: {line.strip()} || " + around(lines, index)[:MAX]))
        return 1

    if traces:
        index, line = traces[0]
        print(command("the server logged an exception",
                      f"line {index + 1}: {line.strip()} || " + around(lines, index)[:MAX]))
        return 1

    if not args.quiet:
        print(command("", "the log holds no failure"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
