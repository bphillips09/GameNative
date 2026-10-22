#!/usr/bin/env python3
"""Package Xodus Nexus runtime components as a GameNative per-container overlay.

Input: the `components` directory produced by Xodus Nexus's
scripts/build-shared-runtime.mjs (x86_64 PE DLLs built inside the Xodus Wine
tree, plus licenses and source-manifest.json).

Output: a .tzst archive with
  system32/<dll>          DLLs to copy into the container's drive_c/windows/system32
  licenses/<file>         upstream licenses (LGPL Wine/xgameruntime, nghttp2 MIT)
  overlay.json            names, SHA-256 and the WINEDLLOVERRIDES each DLL needs

The DLLs are Wine *builtin* modules. Wine ignores a builtin-marked file in
system32 and loads its own copy instead, so the marker is cleared: the overlay
DLLs then load as native modules, per container, via WINEDLLOVERRIDES, without
touching the shared Wine install other games use.

Delay imports are checked, and repaired when needed. Built in the Xodus Wine 11
tree, a delay-import descriptor can start its tables after the module's import
slots, so slots sit at negative indexes. Wine 11's LdrResolveDelayLoadedAPI
takes the index as INT_PTR, but the Wine 9/10 ntdll in GameNative's Proton
builds keeps it in a DWORD, so a negative index wraps and the first call
faults (winhttp.dll crashed on its first TLS connection this way). Every
delay-load thunk is matched to its slot and import name through the DLL's COFF
symbols; descriptors are rebased to their first slot only when that mapping is
exact, and an image whose delay imports would not resolve with a 32-bit index
is rejected.

--repack rewrites an existing overlay archive through the same checks, for
fixing a committed overlay without rebuilding Wine.
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile

BUILTIN_SIGNATURE = b"Wine builtin DLL"
DOS_HEADER_SIZE = 0x40

# name -> override. xgameruntime does not exist in stock Wine; the rest replace
# Wine builtins with the Xodus-patched versions (HTTP/2 WinHTTP, Windows.Data.Json,
# app-state registration). nghttp2 is a plain dependency of that WinHTTP.
# wintypes is Wine 11's, for Windows.Foundation.Collections.PropertySet, which
# Windows.Data.Json needs and the Wine 9/10 in GameNative's Proton builds lacks.
COMPONENTS = {
    "xgameruntime.dll": "n",
    "winhttp.dll": "n,b",
    "windows.web.dll": "n,b",
    "twinapi.appcore.dll": "n,b",
    "wintypes.dll": "n,b",
    "libnghttp2.dll": None,
}
# Built by tools/build-xodus-runtime.sh rather than the upstream recipe, so their
# checksums come from components/gamenative-components.json.
GAMENATIVE_COMPONENTS = {"wintypes.dll"}
LICENSES = ["Wine-LICENSE", "xgameruntime-LICENSE", "nghttp2-LICENSE"]


def is_pe32_plus_x64(data: bytes) -> bool:
    if len(data) < 0x100 or data[:2] != b"MZ":
        return False
    pe = int.from_bytes(data[0x3C:0x40], "little")
    if pe + 26 > len(data) or data[pe:pe + 4] != b"PE\0\0":
        return False
    machine = int.from_bytes(data[pe + 4:pe + 6], "little")
    magic = int.from_bytes(data[pe + 24:pe + 26], "little")
    return machine == 0x8664 and magic == 0x20B


def strip_builtin_marker(data: bytes) -> tuple[bytes, bool]:
    if data[DOS_HEADER_SIZE:DOS_HEADER_SIZE + len(BUILTIN_SIGNATURE)] != BUILTIN_SIGNATURE:
        return data, False
    out = bytearray(data)
    out[DOS_HEADER_SIZE:DOS_HEADER_SIZE + 32] = bytes(32)
    return bytes(out), True


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class PeImage:
    """Just enough of a PE32+ image to follow Wine's delay-load thunks."""

    def __init__(self, data: bytes):
        self.data = data
        pe = struct.unpack_from("<I", data, 0x3C)[0]
        nsec, = struct.unpack_from("<H", data, pe + 6)
        symtab, nsym = struct.unpack_from("<II", data, pe + 12)
        optsz, = struct.unpack_from("<H", data, pe + 20)
        opt = pe + 24
        self.image_base, = struct.unpack_from("<Q", data, opt + 24)
        self.size_of_image, = struct.unpack_from("<I", data, opt + 56)
        self.sections = []
        for i in range(nsec):
            name, vsize, va, rsize, raw = struct.unpack_from("<8sIIII", data, opt + optsz + i * 40)
            self.sections.append((name.rstrip(b"\0").decode("ascii", "replace"), va, vsize, rsize, raw))
        self.symbols = self._read_symbols(symtab, nsym)

    def offset(self, rva: int) -> int | None:
        for _, va, vsize, rsize, raw in self.sections:
            if va <= rva < va + min(vsize, rsize):
                return rva - va + raw
        return None

    def section_of(self, rva: int) -> str | None:
        for name, va, vsize, rsize, _ in self.sections:
            if va <= rva < va + max(vsize, rsize):
                return name
        return None

    def u32(self, rva: int) -> int | None:
        off = self.offset(rva)
        return None if off is None or off + 4 > len(self.data) else struct.unpack_from("<I", self.data, off)[0]

    def u64(self, rva: int) -> int | None:
        off = self.offset(rva)
        return None if off is None or off + 8 > len(self.data) else struct.unpack_from("<Q", self.data, off)[0]

    def cstring(self, rva: int) -> str | None:
        off = self.offset(rva)
        if off is None:
            return None
        end = self.data.find(b"\0", off, off + 512)
        return None if end < 0 else self.data[off:end].decode("ascii", "replace")

    def _read_symbols(self, symtab: int, nsym: int) -> dict[str, int]:
        """COFF symbol name -> RVA, for symbols defined in a section."""
        symbols: dict[str, int] = {}
        if not symtab or not nsym or symtab + nsym * 18 + 4 > len(self.data):
            return symbols
        strtab = symtab + nsym * 18
        i = 0
        while i < nsym:
            raw = self.data[symtab + i * 18:symtab + i * 18 + 18]
            value, section, _, _, aux = struct.unpack_from("<IhHBB", raw, 8)
            if raw[:4] == b"\0\0\0\0":
                start = strtab + struct.unpack_from("<I", raw, 4)[0]
                name = self.data[start:self.data.find(b"\0", start)].decode("ascii", "replace")
            else:
                name = raw[:8].rstrip(b"\0").decode("ascii", "replace")
            if 0 < section <= len(self.sections):
                symbols[name] = self.sections[section - 1][1] + value
            i += 1 + aux
        return symbols


def delay_import_tables(image: PeImage) -> list[dict]:
    """Wine delay-import descriptors with the import slot and name of every thunk.

    Wine's winebuild emits, per delay-loaded module, a descriptor
    (__wine_import_<dll>_desc), a helper (__wine_delay_load_<dll>: lea rcx, desc;
    call __delayLoadHelper2) and one thunk per function (lea rax, __imp_<fn>;
    jmp helper). The thunks, not the descriptor, are the ground truth for where
    each slot lives; the COFF symbols name each slot.
    """
    by_rva = {rva: name for name, rva in image.symbols.items()}
    text = next((s for s in image.sections if s[0] == ".text"), None)
    helpers = {}
    for name, rva in image.symbols.items():
        if not name.startswith("__wine_delay_load_"):
            continue
        dll = name[len("__wine_delay_load_"):]
        off = image.offset(rva)
        code = image.data[off:off + 64] if off is not None else b""
        lea = code.find(b"\x48\x8d\x0d")
        if lea < 0:
            raise ValueError(f"{name}: no descriptor reference")
        desc = rva + lea + 7 + struct.unpack_from("<i", code, lea + 3)[0]
        if image.symbols.get(f"__wine_import_{dll}_desc", desc) != desc:
            raise ValueError(f"{name}: references {desc:#x}, not __wine_import_{dll}_desc")
        helpers[rva] = {"dll": dll, "desc": desc, "thunks": []}
    if not helpers or text is None:
        return []
    _, va, vsize, rsize, raw = text
    code = image.data[raw:raw + min(vsize, rsize)]
    pos = code.find(b"\x48\x8d\x05")
    while pos >= 0:
        if pos + 12 <= len(code) and code[pos + 7] == 0xE9:
            slot = va + pos + 7 + struct.unpack_from("<i", code, pos + 3)[0]
            target = va + pos + 12 + struct.unpack_from("<i", code, pos + 8)[0]
            if target in helpers:
                symbol = by_rva.get(slot, "")
                if not symbol.startswith("__imp_"):
                    raise ValueError(f"delay thunk at {va + pos:#x}: slot {slot:#x} has no __imp_ symbol")
                if image.u64(slot) != image.image_base + va + pos:
                    raise ValueError(f"{symbol}: slot does not start at its thunk")
                helpers[target]["thunks"].append((slot, symbol[len("__imp_"):]))
        pos = code.find(b"\x48\x8d\x05", pos + 1)
    tables = []
    for helper in helpers.values():
        desc = helper["desc"]
        fields = [image.u32(desc + 4 * i) for i in range(8)]
        if None in fields:
            raise ValueError(f"{helper['dll']}: descriptor outside the image")
        helper["fields"] = fields
        tables.append(helper)
    return tables


def import_name_at(image: PeImage, int_rva: int, index: int) -> str | None:
    entry = image.u64(int_rva + 8 * index)
    if entry is None or entry >> 63 or not 0 < entry < image.size_of_image:
        return None
    return image.cstring(entry + 2)


def resolves(image: PeImage, iat: int, int_rva: int, thunks: list) -> bool:
    """Whether ntdll, given these table starts, finds each thunk's own import name.

    Indexes must be non-negative: Wine 9/10 ntdll holds the slot index in a DWORD.
    """
    for slot, name in thunks:
        if (slot - iat) % 8 or slot < iat:
            return False
        if import_name_at(image, int_rva, (slot - iat) // 8) != name:
            return False
    return True


def repair_delay_imports(name: str, data: bytes) -> tuple[bytes, list[str]]:
    """Re-points broken delay-import descriptors; raises if any cannot be verified."""
    image = PeImage(data)
    tables = delay_import_tables(image)
    if not tables:
        return data, []
    out = bytearray(data)
    repaired = []
    for table in tables:
        attributes, dll_name, _, iat, int_rva = table["fields"][:5]
        thunks = sorted(table["thunks"])
        if attributes != 1:
            raise SystemExit(f"{name}: {table['dll']} delay descriptor is not RVA-based")
        if not thunks or resolves(image, iat, int_rva, thunks):
            continue
        # The slots are right (the thunks use them); rebase both tables to the first slot.
        first_slot, first_name = thunks[0]
        section = image.section_of(int_rva)
        candidates = []
        for sname, va, vsize, rsize, raw in image.sections:
            if sname != section:
                continue
            for rva in range(va, va + min(vsize, rsize) - 7, 8):
                if import_name_at(image, rva, 0) == first_name:
                    candidates.append(rva)
        fixes = {rva for rva in candidates if resolves(image, first_slot, rva, thunks)}
        if len(fixes) != 1:
            raise SystemExit(f"{name}: {table['dll']} delay imports are broken and no unique repair exists")
        new_int = fixes.pop()
        desc_off = image.offset(table["desc"])
        struct.pack_into("<II", out, desc_off + 12, first_slot, new_int)
        repaired.append(f"{image.cstring(dll_name)}: IAT {iat:#x}->{first_slot:#x}, INT {int_rva:#x}->{new_int:#x}")
    check_delay_imports(name, bytes(out))
    return bytes(out), repaired


def check_delay_imports(name: str, data: bytes) -> None:
    """Rejects an image whose delay-load thunks do not resolve to their own imports."""
    image = PeImage(data)
    try:
        tables = delay_import_tables(image)
    except ValueError as e:
        raise SystemExit(f"{name}: {e}")
    if not tables and b"__delayLoadHelper2" in data and not image.symbols:
        raise SystemExit(f"{name}: delay imports present but no COFF symbols to verify them")
    for table in tables:
        _, _, _, iat, int_rva = table["fields"][:5]
        if not resolves(image, iat, int_rva, sorted(table["thunks"])):
            raise SystemExit(f"{name}: {table['dll']} delay imports do not resolve to their own names")


def dll_entry(name: str, data: bytes, override: str | None, stripped: bool) -> tuple[bytes, dict]:
    """Repairs and checks one overlay DLL; returns the bytes to ship and its overlay.json entry."""
    if not is_pe32_plus_x64(data):
        raise SystemExit(f"{name}: not an x86-64 PE32+ image")
    data, repaired = repair_delay_imports(name, data)
    entry = {"name": name, "sha256": sha256(data), "builtinMarkerCleared": stripped, "override": override}
    if repaired:
        entry["delayImportsRepaired"] = repaired
    return data, entry


def build(components: str, output: str) -> dict:
    manifest_path = os.path.join(components, "source-manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        source = json.load(f)
    built = {k: v for k, v in source.get("components", {}).items() if k not in GAMENATIVE_COMPONENTS}
    with open(os.path.join(components, "gamenative-components.json"), encoding="utf-8") as f:
        ours = json.load(f).get("components", {})
    built.update({k: v for k, v in ours.items() if k in GAMENATIVE_COMPONENTS})
    overlay = {"schema": 1, "sources": source.get("sources", {}), "patches": [p["file"] for p in source.get("patches", [])], "dlls": []}
    with tempfile.TemporaryDirectory() as stage:
        os.makedirs(os.path.join(stage, "system32"))
        os.makedirs(os.path.join(stage, "licenses"))
        for name, override in COMPONENTS.items():
            with open(os.path.join(components, name), "rb") as f:
                data = f.read()
            if built.get(name) != sha256(data):
                manifest = "gamenative-components.json" if name in GAMENATIVE_COMPONENTS else "source-manifest.json"
                raise SystemExit(f"{name}: checksum does not match {manifest}")
            if not is_pe32_plus_x64(data):
                raise SystemExit(f"{name}: not an x86-64 PE32+ image")
            data, stripped = strip_builtin_marker(data)
            data, entry = dll_entry(name, data, override, stripped)
            with open(os.path.join(stage, "system32", name), "wb") as f:
                f.write(data)
            overlay["dlls"].append(entry)
        for name in LICENSES:
            shutil.copyfile(os.path.join(components, name), os.path.join(stage, "licenses", name))
        write_archive(stage, overlay, output)
    return overlay


def repack(archive: str, output: str) -> dict:
    """Runs an existing overlay archive's DLLs through the current checks and repairs."""
    with tempfile.TemporaryDirectory() as stage:
        tar_bytes = subprocess.run(["zstd", "-dc", archive], check=True, capture_output=True).stdout
        wanted = {"overlay.json"} | {f"system32/{n}" for n in COMPONENTS} | {f"licenses/{n}" for n in LICENSES}
        files = {}
        with tarfile.open(fileobj=io.BytesIO(tar_bytes)) as tar:
            for member in tar.getmembers():
                if member.isfile() and member.name in wanted:
                    files[member.name] = tar.extractfile(member).read()
        missing = wanted - files.keys()
        if missing:
            raise SystemExit(f"{archive}: missing {sorted(missing)}")
        old = json.loads(files["overlay.json"])
        recorded = {d["name"]: d for d in old.get("dlls", [])}
        overlay = {k: v for k, v in old.items() if k != "dlls"}
        overlay["dlls"] = []
        os.makedirs(os.path.join(stage, "system32"))
        os.makedirs(os.path.join(stage, "licenses"))
        for name, override in COMPONENTS.items():
            data = files[f"system32/{name}"]
            prior = recorded.get(name)
            if prior is None or prior.get("sha256") != sha256(data):
                raise SystemExit(f"{name}: does not match the archive's overlay.json")
            data, entry = dll_entry(name, data, override, bool(prior.get("builtinMarkerCleared")))
            if "delayImportsRepaired" not in entry and prior.get("delayImportsRepaired"):
                entry["delayImportsRepaired"] = prior["delayImportsRepaired"]
            with open(os.path.join(stage, "system32", name), "wb") as f:
                f.write(data)
            overlay["dlls"].append(entry)
        for name in LICENSES:
            with open(os.path.join(stage, "licenses", name), "wb") as f:
                f.write(files[f"licenses/{name}"])
        write_archive(stage, overlay, output)
    return overlay


def write_archive(stage: str, overlay: dict, output: str) -> None:
    with open(os.path.join(stage, "overlay.json"), "w", encoding="utf-8") as f:
        json.dump(overlay, f, indent=2)
        f.write("\n")
    tar_path = output[:-5] if output.endswith(".tzst") else output + ".tar"
    with tarfile.open(tar_path, "w", format=tarfile.PAX_FORMAT) as tar:
        def normalized(path: str) -> tarfile.TarInfo:
            info = tar.gettarinfo(path, os.path.relpath(path, stage))
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            return info

        for root, _, files in sorted(os.walk(stage)):
            # Directory entries first: GameNative's extractor creates directories only from them.
            if root != stage:
                tar.addfile(normalized(root))
            for file in sorted(files):
                full = os.path.join(root, file)
                with open(full, "rb") as f:
                    tar.addfile(normalized(full), f)
    subprocess.run(["zstd", "-19", "-q", "-f", "--rm", tar_path, "-o", output], check=True)


def self_test() -> None:
    dos = bytearray(0x200)
    dos[:2] = b"MZ"
    dos[0x3C:0x40] = (0x80).to_bytes(4, "little")
    dos[0x40:0x40 + len(BUILTIN_SIGNATURE)] = BUILTIN_SIGNATURE
    dos[0x80:0x84] = b"PE\0\0"
    dos[0x84:0x86] = (0x8664).to_bytes(2, "little")
    dos[0x98:0x9A] = (0x20B).to_bytes(2, "little")
    assert is_pe32_plus_x64(bytes(dos))
    stripped, changed = strip_builtin_marker(bytes(dos))
    assert changed and stripped[0x40:0x60] == bytes(32) and stripped[0x80:0x84] == b"PE\0\0"
    assert strip_builtin_marker(stripped) == (stripped, False)
    dos[0x84:0x86] = (0x14C).to_bytes(2, "little")
    assert not is_pe32_plus_x64(bytes(dos))
    print("self-test ok")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--components")
    parser.add_argument("--repack", metavar="ARCHIVE", help="re-check and repair an existing overlay .tzst")
    parser.add_argument("--output")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.output or bool(args.components) == bool(args.repack):
        parser.error("--output and exactly one of --components or --repack are required")
    overlay = repack(args.repack, args.output) if args.repack else build(args.components, args.output)
    json.dump(overlay, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
