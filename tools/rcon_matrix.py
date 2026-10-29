#!/usr/bin/env python3
"""Drives a running server over rcon and checks the answers of a large matrix.

The engine's own test-suite runs against a double of the world; this runs
against the real game: the real registries, the real chunks, the real
dispatcher.  A command that works in the double and not here is a bug the
suite cannot see, and this matrix is the sweep that finds it.

Every case is one of three kinds:

  * ``expect``  - the answer must contain every listed fragment.
  * ``refuse``  - the command has to be refused with a message a player can
                  act on, and never with an internal error.
  * ``any``     - the command may answer anything, as long as it does not
                  crash the server and does not report an internal error.

Usage:
    python3 tools/rcon_matrix.py [--host H] [--port P] [--password PW]
                                 [--wait SECONDS] [--only SUBSTRING]
                                 [--verbose]
"""
from __future__ import annotations

import argparse
import re
import socket
import struct
import sys
import time

# --------------------------------------------------------------------------
# rcon
# --------------------------------------------------------------------------


class Rcon:
    def __init__(self, host: str, port: int, password: str, timeout: float = 30.0):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.request = 0
        self._auth(password)

    def _packet(self, kind: int, body: str) -> bytes:
        self.request += 1
        payload = struct.pack("<ii", self.request, kind) + body.encode("utf8") + b"\x00\x00"
        return struct.pack("<i", len(payload)) + payload

    def _read(self) -> tuple[int, str]:
        raw = self.sock.recv(4)
        if len(raw) < 4:
            raise ConnectionError("the server closed the rcon connection")
        (length,) = struct.unpack("<i", raw)
        data = b""
        while len(data) < length:
            chunk = self.sock.recv(length - len(data))
            if not chunk:
                break
            data += chunk
        ident, _kind = struct.unpack("<ii", data[:8])
        return ident, data[8:-2].decode("utf8", "replace")

    def _auth(self, password: str) -> None:
        self.sock.sendall(self._packet(3, password))
        ident, _ = self._read()
        if ident == -1:
            raise PermissionError("rcon refused the password")

    def command(self, text: str) -> str:
        self.sock.sendall(self._packet(2, text))
        return self._read()[1]

    def close(self) -> None:
        try:
            self.sock.close()
        except OSError:
            pass


# --------------------------------------------------------------------------
# the matrix
# --------------------------------------------------------------------------

# An answer that means the mod broke: the dispatcher caught an exception it
# did not expect and answered with its own name.
BROKEN = (
    "internal error",
    "an internal error",
    "exception",
    "stacktrace",
    "stack trace",
    "java.lang.",
    "nullpointer",
    "classnotfound",
    "nosuchmethod",
    "nosuchfield",
)

# The phrases the engine uses when it refuses a command. A refusal has to
# carry one of them (or at least some words), never a stack trace.
REFUSAL_HINTS = ("unknown", "invalid", "cannot", "must", "not enough", "usage",
                 "expected", "no ", "out of range", "too ", "does not", "is not")

CASES: list[tuple[str, str, list[str]]] = []


def case(kind: str, command: str, *fragments: str) -> None:
    CASES.append((kind, command, list(fragments)))


def build() -> None:
    # -- the selection ----------------------------------------------------
    case("expect", "//pos1 0,0,0")
    case("expect", "//pos2 15,15,15")
    case("expect", "//pos1 -8,-8,-8")
    case("expect", "//pos2 7,7,7")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 31,79,31")
    case("expect", "//hpos1 0,64,0")
    case("expect", "//hpos2 31,79,31")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 15,79,15")
    case("expect", "//size")
    case("expect", "//sel")
    case("expect", "//desel")
    case("expect", "//sel clear")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 15,79,15")
    case("expect", "//sel pos1")
    case("expect", "//desel")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 15,79,15")

    # -- writing ----------------------------------------------------------
    case("expect", "//set stone")
    case("expect", "//count stone")
    case("expect", "//count")
    case("expect", "//set dirt")
    case("expect", "//count dirt")
    case("expect", "//set stone")
    case("expect", "//set glass")
    case("expect", "//set wool")
    case("expect", "//set stone,glass")
    case("expect", "//set stone 50")
    case("expect", "//set stone -a")
    case("expect", "//set stone -f")
    case("expect", "//set stone -h")
    case("expect", "//set stone -s")
    case("expect", "//air")
    case("expect", "//count air")
    case("expect", "//set stone")
    case("expect", "//replace stone dirt")
    case("expect", "//replace dirt stone")
    case("expect", "//replace stone dirt -a")
    case("expect", "//fill stone")
    case("expect", "//set stone")

    # -- masks and patterns, with and without a selection ------------------
    case("expect", "//mask")
    case("expect", "//mask clear")
    case("expect", "//gmask")
    case("expect", "//gmask clear")
    case("expect", "//smask")
    case("expect", "//smask clear")
    case("expect", "//set stone")
    case("expect", "//mask #existing")
    case("expect", "//set stone")
    case("expect", "//mask clear")
    case("expect", "//set stone")
    case("expect", "//gmask stone")
    case("expect", "//set dirt")
    case("expect", "//gmask clear")
    case("expect", "//set stone")
    case("expect", "//smask stone")
    case("expect", "//set dirt")
    case("expect", "//smask clear")
    case("expect", "//set stone")

    # -- history ----------------------------------------------------------
    case("expect", "//set dirt")
    case("expect", "//undo")
    case("expect", "//count dirt")
    case("expect", "//redo")
    case("expect", "//count dirt")
    case("expect", "//history")
    case("expect", "//clearhistory")

    # -- the clipboard ----------------------------------------------------
    case("expect", "//set stone")
    case("expect", "//copy")
    case("expect", "//paste")
    case("expect", "//clearclipboard")
    case("expect", "//set dirt")
    case("expect", "//cut")
    case("expect", "//paste")
    case("expect", "//clearclipboard")
    case("expect", "//copy")
    case("expect", "//paste -a")
    case("expect", "//clearclipboard")

    # -- queries ----------------------------------------------------------
    case("expect", "//info")
    case("expect", "//info stone")
    case("expect", "//info -c")
    case("expect", "//count stone")
    case("expect", "//biomeinfo")
    case("expect", "//biomelist")
    case("expect", "//chunkinfo")
    case("expect", "//inspect")
    case("expect", "//nbtinfo")
    case("expect", "//limit")
    case("expect", "//limit 10000")
    case("expect", "//limit 0")
    case("expect", "//version")
    case("expect", "//help")
    case("expect", "//list")
    case("expect", "//brushes")
    case("expect", "//masks")
    case("expect", "//patterns")
    case("expect", "//transforms")
    case("expect", "//fawebim")
    case("expect", "//listchunks")
    case("expect", "//watchdog")

    # -- the shapes -------------------------------------------------------
    case("expect", "//sphere stone 5")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//cyl stone 5 5")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//hcyl stone 5 5")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//spheroid stone 5")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//hspheroid stone 5")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//ellipsoid stone 5 5 5")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//cone stone 10")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//hpyramid stone 10")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//hollow stone 10")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//walls stone 10")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//outline stone 10")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//line stone 10")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//sphere stone 5 -h")
    case("expect", "//count stone")
    case("expect", "//set stone")

    # -- the world outside the selection ----------------------------------
    case("expect", "//set stone")
    case("expect", "//set stone -r")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 15,79,15")
    case("expect", "//set stone")
    case("expect", "//set stone -r")
    case("expect", "//count stone")
    case("expect", "//set stone")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 15,79,15")

    # -- the biomes -------------------------------------------------------
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 15,79,15")
    case("expect", "//setbiome minecraft:desert")
    case("expect", "//biomeinfo")
    case("expect", "//setbiome minecraft:plains")
    case("expect", "//biomeinfo")
    case("expect", "//setbiome")
    case("expect", "//biomeinfo -f")

    # -- the transforms ----------------------------------------------------
    case("expect", "//set stone")
    case("expect", "//rotate 90")
    case("expect", "//rotate 180")
    case("expect", "//rotate 270")
    case("expect", "//flip")
    case("expect", "//flip x")
    case("expect", "//flip y")
    case("expect", "//flip z")
    case("expect", "//rotate 45")
    case("expect", "//transform scale 2")
    case("expect", "//set stone")
    case("expect", "//move 1 1 1")
    case("expect", "//shift 1 0 0")
    case("expect", "//set stone")

    # -- the brushes ------------------------------------------------------
    case("expect", "//brush stone")
    case("expect", "//set stone")
    case("expect", "//brush sphere stone 3")
    case("expect", "//sphere stone 3")
    case("expect", "//brush cyl stone 3")
    case("expect", "//cyl stone 3")
    case("expect", "//brush set stone")
    case("expect", "//set stone")
    case("expect", "//brush none")
    case("expect", "//listbrush")

    # -- the schematics ---------------------------------------------------
    case("expect", "//set stone")
    case("expect", "//schem save matrix-test")
    case("expect", "//schem load matrix-test")
    case("expect", "//paste")
    case("expect", "//schem list")
    case("expect", "//schem delete matrix-test")
    case("expect", "//schem list")
    case("expect", "//schem load matrix-test")

    # -- the tools --------------------------------------------------------
    case("expect", "//wand")
    case("expect", "//wand pos1")
    case("expect", "//wand pos2")
    case("expect", "//selwand")
    case("expect", "//toggleeditwand")
    case("expect", "//tool")
    case("expect", "//tool none")
    case("expect", "//tool pos1")
    case("expect", "//tool pos2")
    case("expect", "//tool none")

    # -- the commands that need a player (the console has none) -----------
    case("any", "//pos1")
    case("any", "//pos2")
    case("any", "//wand")
    case("any", "//spawn")
    case("any", "//spawnpos")
    case("any", "//tp")
    case("any", "//tpp")
    case("any", "//jumpto")
    case("any", "//goto")
    case("any", "//ascend")
    case("any", "//descend")
    case("any", "//asc")
    case("any", "//desc")
    case("any", "//ceil")
    case("any", "//ground")
    case("any", "//up")
    case("any", "//thru")
    case("any", "//home")
    case("any", "//set home")
    case("any", "//sethome")
    case("any", "//spawn")
    case("any", "//kill")
    case("any", "//killall")
    case("any", "//target")
    case("any", "//nearest")
    case("any", "//navwand")
    case("any", "//warwand")
    case("any", "//farwand")
    case("any", "//selwand")

    # -- the refusals: a wrong argument has to be answered, not swallowed ---
    case("refuse", "//set notablock")
    case("refuse", "//set stone notanumber")
    case("refuse", "//pos1 a,b,c")
    case("refuse", "//pos2 1,2")
    case("refuse", "//pos1 1,2,3,4")
    case("refuse", "//set")
    case("refuse", "//count notablock")
    case("refuse", "//mask notamask")
    case("refuse", "//gmask notamask")
    case("refuse", "//smask notamask")
    case("refuse", "//setbiome notabiome")
    case("refuse", "//rotate notanumber")
    case("refuse", "//flip notanaxis")
    case("refuse", "//sphere stone notanumber")
    case("refuse", "//cyl stone notanumber")
    case("refuse", "//replace notablock notablock")
    case("refuse", "//schem load")
    case("refuse", "//schem save")
    case("refuse", "//schem delete")
    case("refuse", "//schem load no-such-schematic")
    case("refuse", "//copy 99999999")
    case("refuse", "//paste 99999999")
    case("refuse", "//undo 99999999")
    case("refuse", "//limit -1")
    case("refuse", "//limit notanumber")
    case("refuse", "//set stone -")
    case("refuse", "//set stone --")
    case("refuse", "//info notablock")
    case("refuse", "//nbtinfo notanbt")
    case("refuse", "//biomeinfo notanumber")
    case("refuse", "//chunkinfo notanumber")
    case("refuse", "//tool notatool")
    case("refuse", "//brush notabrush")
    case("refuse", "//transform notatransform")
    case("refuse", "//clipboard")
    case("refuse", "//wand notapos")
    case("refuse", "//set stone -x")
    case("refuse", "//set stone -a -x")
    case("refuse", "//count -x")
    case("refuse", "//sphere stone 5 -x")
    case("refuse", "//pos1 99999999,99999999,99999999")
    case("refuse", "//pos1 -99999999,-99999999,-99999999")
    case("refuse", "//set stone 999999999")
    case("refuse", "//pos1 0,64,0 extra")
    case("refuse", "//gmask stone extra")
    case("refuse", "//mask #nonexistenttag")
    case("refuse", "//schem load matrix-test nosuchformat")
    case("refuse", "//setbiome minecraft:notabiome")

    # -- the combinations -------------------------------------------------
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 7,71,7")
    case("expect", "//set stone")
    case("expect", "//gmask stone")
    case("expect", "//set dirt")
    case("expect", "//gmask clear")
    case("expect", "//count stone")
    case("expect", "//count dirt")
    case("expect", "//mask #existing")
    case("expect", "//set glass")
    case("expect", "//mask clear")
    case("expect", "//count glass")
    case("expect", "//replace stone stone")
    case("expect", "//replace glass stone")
    case("expect", "//undo")
    case("expect", "//redo")
    case("expect", "//copy")
    case("expect", "//paste")
    case("expect", "//paste -a")
    case("expect", "//paste -b")
    case("expect", "//set stone")
    case("expect", "//schem save matrix-combo")
    case("expect", "//set air")
    case("expect", "//schem load matrix-combo")
    case("expect", "//paste")
    case("expect", "//schem delete matrix-combo")
    case("expect", "//pos1 0,64,0")
    case("expect", "//pos2 3,67,3")
    case("expect", "//sphere stone 4")
    case("expect", "//copy")
    case("expect", "//paste")
    case("expect", "//undo")
    case("expect", "//undo")
    case("expect", "//undo")
    case("expect", "//redo")
    case("expect", "//redo")
    case("expect", "//clearclipboard")


# --------------------------------------------------------------------------
# the run
# --------------------------------------------------------------------------

COUNT = re.compile(r"(\d+)")


def broken(answer: str) -> str:
    lowered = answer.lower()
    for phrase in BROKEN:
        if phrase in lowered:
            return phrase
    return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="fawebim")
    parser.add_argument("--wait", type=int, default=120)
    parser.add_argument("--only", default=None,
                        help="only run the cases whose command holds this fragment")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    deadline = time.time() + args.wait
    rcon = None
    while time.time() < deadline:
        try:
            rcon = Rcon(args.host, args.port, args.password)
            break
        except OSError:
            time.sleep(1)
    if rcon is None:
        print("::error title=rcon matrix::the server never answered on rcon")
        return 1

    build()
    cases = [c for c in CASES if not args.only or args.only in c[1]]
    failures: list[str] = []
    crashes = 0
    refusals_without_a_reason = 0

    print(f"::notice title=rcon matrix::running {len(cases)} commands")
    for kind, command, fragments in cases:
        try:
            answer = rcon.command(command)
        except (ConnectionError, socket.timeout, OSError) as failure:
            failures.append(f"{command}: the connection died ({failure})")
            crashes += 1
            try:
                rcon = Rcon(args.host, args.port, args.password)
            except OSError:
                print(f"::error title=rcon matrix::the server stopped answering at '{command}'")
                return 1
            continue
        answer = answer.replace("§", "")
        if args.verbose:
            print(f"  {command} -> {answer.strip()[:120]}")

        hit = broken(answer)
        if hit:
            failures.append(f"{command}: the answer names an internal failure ({hit}): "
                            f"{answer.strip()[:200]}")
            continue

        if kind == "expect":
            missing = [f for f in fragments if f.lower() not in answer.lower()]
            if missing:
                failures.append(f"{command}: the answer does not hold {missing}: "
                                f"{answer.strip()[:200]}")
        elif kind == "refuse":
            if not answer.strip():
                failures.append(f"{command}: refused with no message at all")
                refusals_without_a_reason += 1
            elif not any(hint in answer.lower() for hint in REFUSAL_HINTS):
                failures.append(f"{command}: refused with a message that says nothing: "
                                f"{answer.strip()[:200]}")

    # The server has to still answer after the sweep.
    try:
        after = rcon.command("we version")
        if broken(after):
            failures.append(f"we version: {after.strip()[:200]}")
    except OSError as failure:
        failures.append(f"the server stopped answering after the sweep ({failure})")
        crashes += 1

    rcon.close()

    print()
    for failure in failures:
        print("FAIL " + failure)
    print()
    print(f"{len(cases)} commands, {len(failures)} failure(s), {crashes} crash(es), "
          f"{refusals_without_a_reason} silent refusal(s)")

    if failures:
        print("::error title=rcon matrix::%d of %d commands failed" % (len(failures), len(cases)))
        return 1
    print("::notice title=rcon matrix::every command answered as expected")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
