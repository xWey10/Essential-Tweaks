#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Read-only DEX declaration inventory. Requires Python 3.11+, no third-party packages.

This checks symbols, not method semantics or compatibility. Decompile the APK
and review method bodies before changing the OEM profile.
"""
import argparse
import hashlib
import json
import struct
import sys
import zipfile
from pathlib import Path

DEFAULT_PREFIXES = (
    "com.android.systemui.globalactions.GlobalActionsDialogLite",
    "com.android.systemui.settings.UserTracker",
    "com.android.systemui.statusbar.phone.SystemUIDialog",
    "com.nothing.systemui.NTDependencyEx",
    "com.nothing.keyguard.KeyguardSecurityContainerControllerEx",
    "com.nothing.systemui.keyguard.KeyguardViewMediatorEx",
)


class Dex:
    def __init__(self, data):
        self.data = data
        if data[:4] != b"dex\n" or data[4:7] not in (b"035", b"037", b"038", b"039", b"040"):
            raise ValueError("Unsupported DEX version; use JADX for this file")
        if self.u32(40) != 0x12345678:
            raise ValueError("Unsupported DEX endianness")
        size, offset = self.table(56)
        self.strings = []
        for i in range(size):
            address = self.u32(offset + i * 4)
            _, address = self.uleb(address)
            end = data.index(0, address)
            self.strings.append(data[address:end].decode("utf-8", errors="replace"))
        size, offset = self.table(64)
        self.types = [self.strings[self.u32(offset + i * 4)] for i in range(size)]
        size, offset = self.table(72)
        self.protos = []
        for i in range(size):
            base = offset + i * 12
            parameters = self.u32(base + 8)
            args = [] if not parameters else [
                self.types[self.u16(parameters + 4 + j * 2)] for j in range(self.u32(parameters))
            ]
            self.protos.append((self.types[self.u32(base + 4)], args))
        size, offset = self.table(80)
        self.fields = []
        for i in range(size):
            base = offset + i * 8
            self.fields.append({"name": self.strings[self.u32(base + 4)],
                                "type": self.types[self.u16(base + 2)]})
        size, offset = self.table(88)
        self.methods = []
        for i in range(size):
            base = offset + i * 8
            result, args = self.protos[self.u16(base + 2)]
            self.methods.append({"name": self.strings[self.u32(base + 4)],
                                 "return_type": result, "parameters": args})

    def u16(self, offset):
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def table(self, offset):
        return self.u32(offset), self.u32(offset + 4)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7F) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid DEX ULEB128")

    def declarations(self, prefixes):
        size, offset = self.table(96)
        for i in range(size):
            base = offset + i * 32
            descriptor = self.types[self.u32(base)]
            name = descriptor[1:-1].replace("/", ".")
            if not any(name.startswith(prefix) for prefix in prefixes):
                continue
            parent = self.u32(base + 8)
            interfaces_offset = self.u32(base + 12)
            interfaces = [] if not interfaces_offset else [
                self.types[self.u16(interfaces_offset + 4 + j * 2)]
                for j in range(self.u32(interfaces_offset))
            ]
            record = {"class": name, "descriptor": descriptor,
                      "superclass": None if parent == 0xFFFFFFFF else self.types[parent],
                      "interfaces": interfaces,
                      "fields": [], "methods": []}
            address = self.u32(base + 24)
            if address:
                counts = []
                for _ in range(4):
                    count, address = self.uleb(address)
                    counts.append(count)
                for group, count in enumerate(counts):
                    index = 0
                    for _ in range(count):
                        delta, address = self.uleb(address)
                        flags, address = self.uleb(address)
                        index += delta
                        if group < 2:
                            record["fields"].append(dict(self.fields[index], access_flags=hex(flags)))
                        else:
                            code, address = self.uleb(address)
                            record["methods"].append(dict(self.methods[index], access_flags=hex(flags), code_offset=code))
            yield record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--prefix", action="append", help="Class prefix; repeat to override defaults")
    args = parser.parse_args()
    with args.apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    classes = []
    with zipfile.ZipFile(args.apk) as archive:
        dex_files = sorted(name for name in archive.namelist() if name.startswith("classes") and name.endswith(".dex"))
        for name in dex_files:
            for record in Dex(archive.read(name)).declarations(args.prefix or DEFAULT_PREFIXES):
                classes.append(dict(record, dex_file=name))
    json.dump({"apk": args.apk.name, "sha256": digest, "dex_files": dex_files,
               "symbols_only": True, "classes": classes}, sys.stdout, ensure_ascii=False, indent=2)
    print()
    return 0 if classes else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError, struct.error, zipfile.BadZipFile) as error:
        print(f"Cannot inspect APK: {error}", file=sys.stderr)
        sys.exit(1)
