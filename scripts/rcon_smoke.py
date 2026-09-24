#!/usr/bin/env python3
"""Drive a running Minecraft server over RCON and check what the mod answers.

The workflow boots the dedicated server with the mod and then runs this script:
it is the only check that the mod actually loads, that the mixin applies, that
the commands are registered with the game and that the configuration round-trips
in a live server. Every check prints what came back, so a failure says what the
server said instead of only that a comparison failed.
"""
import argparse
import os
import socket
import struct
import sys
import time

AUTH = 3
COMMAND = 2
AUTH_RESPONSE = 2
RESPONSE = 0

# (command, text that must appear in the answer). The server strips one leading
# slash from a line, so "fawebim path" is what a player typing /fawebim path
# sends and "//fawebim path" is what typing //fawebim sends.
CHECKS = [
    ("fawebim path", "config"),
    ("//fawebim path", "config"),
    ("fawebim settings", "Settings ("),
    ("fawebim settings max-blocks", "max-blocks-changed"),
    ("fawebim set max-blocks-changed.default 123456", "123456"),
    ("fawebim settings max-blocks-changed", "123456"),
    ("fawebim reset max-blocks-changed.default", "default"),
    ("fawebim save", "written"),
    ("fawebim reload", "reloaded"),
    ("//brushes", "brush"),
    ("//masks", "mask"),
    ("//patterns", "pattern"),
    ("//transforms", "rotate"),
    ("//version", "commands registered"),
    ("fawebim settings -s boolean", "Settings ("),
    ("fawebim nonsense", "Usage"),
    # A command that needs a player is still a command the server knows: the
    # answer must be about the selection, not about the command.
    ("//wand", "player"),
]


def annotate(title, message):
    """Reports a failure to the workflow, where it can be read."""
    if not os.environ.get("GITHUB_ACTIONS"):
        return
    text = message.replace("%", "%25").replace("\r", "")
    for index in range(0, max(1, len(text)), 3200):
        print("::error title=%s::%s" % (title, text[index:index + 3200].replace("\n", "%0A")))


class Rcon:
    def __init__(self, host, port, password, timeout=15.0):
        self.socket = socket.create_connection((host, port), timeout=timeout)
        self.socket.settimeout(timeout)
        self.request_id = 0
        sent = self.send(AUTH, password)
        answer = self.receive()
        # The server answers an authentication with the request id it was given,
        # and with -1 when the password is not the one in server.properties; its
        # packet type is the authentication response, not the command one.
        if answer is None or answer[0] != sent or answer[1] != AUTH_RESPONSE:
            raise RuntimeError("the server refused the rcon password (answer: %s)" % (answer,))

    def send(self, kind, body):
        self.request_id += 1
        payload = struct.pack("<ii", self.request_id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.socket.sendall(struct.pack("<i", len(payload)) + payload)
        return self.request_id

    def _read(self, size):
        data = b""
        while len(data) < size:
            chunk = self.socket.recv(size - len(data))
            if not chunk:
                raise RuntimeError("the connection closed")
            data += chunk
        return data

    def receive(self):
        try:
            length = struct.unpack("<i", self._read(4))[0]
        except (socket.timeout, RuntimeError):
            return None
        payload = self._read(length)
        request_id, kind = struct.unpack("<ii", payload[:8])
        return request_id, kind, payload[8:-2].decode("utf-8", errors="replace")

    def run(self, command):
        """Sends a command and reads its answer, including a follow-up packet.

        A vanilla server answers a command with one {@code RESPONSE_VALUE} packet
        and then an empty one as the end marker; it may also split a long answer
        in two, which is why anything type 0 is collected until the marker.
        """
        self.send(COMMAND, command)
        parts = []
        while True:
            answer = self.receive()
            if answer is None:
                break
            if answer[1] != RESPONSE:
                continue
            if not answer[2]:
                break
            parts.append(answer[2])
        return "\n".join(parts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="fawebim")
    parser.add_argument("--only", default=None, help="run one command and print the answer")
    parser.add_argument("--wait", type=float, default=0.0,
                        help="seconds to keep trying to reach the server")
    args = parser.parse_args()

    deadline = time.time() + args.wait
    while True:
        try:
            client = Rcon(args.host, args.port, args.password)
            break
        except OSError as error:
            if time.time() >= deadline:
                message = "cannot reach the rcon port %s:%s (%s)" % (args.host, args.port, error)
                print(message)
                annotate("rcon", message)
                return 1
            time.sleep(2)

    if args.only:
        print(client.run(args.only))
        return 0

    failures = []
    transcript = []
    for command, expected in CHECKS:
        answer = client.run(command)
        shown = answer.replace("\n", " / ")[:220]
        ok = expected.lower() in answer.lower()
        print("%s  /%s -> %s" % ("ok  " if ok else "FAIL", command, shown))
        transcript.append("%-45s %s" % ("/" + command, shown))
        if not ok:
            failures.append(command)
            annotate("command failed: /" + command,
                     "expected %r\nanswer: %s" % (expected, answer))
        time.sleep(0.15)
    annotate("rcon transcript", "\n".join(transcript))

    # The servers this mod runs on are the point of the smoke test: the world
    # edit itself is checked by the engine tests, what is checked here is that a
    # running server accepts the commands at all.
    print()
    if failures:
        print("failed: " + ", ".join(failures))
        return 1
    print("the server answered every command")
    return 0


if __name__ == "__main__":
    sys.exit(main())
