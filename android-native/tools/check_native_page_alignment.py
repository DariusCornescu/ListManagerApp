"""Fail when an APK contains native libraries without 16 KB ELF alignment."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path


PAGE_SIZE_16_KB = 0x4000
ELF_MAGIC = b"\x7fELF"
LOAD_SEGMENT = 1
PAGE_SIZE_ABIS = ("arm64-v8a", "x86_64")


class ElfError(ValueError):
    """Raised when a native library has a malformed or unsupported ELF header."""


@dataclass(frozen=True)
class AlignmentResult:
    library: str
    minimum_load_alignment: int

    @property
    def compatible(self) -> bool:
        return self.minimum_load_alignment >= PAGE_SIZE_16_KB


def load_segment_alignments(data: bytes) -> list[int]:
    if len(data) < 16 or data[:4] != ELF_MAGIC:
        raise ElfError("missing ELF magic")

    elf_class = data[4]
    byte_order = data[5]
    if byte_order == 1:
        endian = "<"
    elif byte_order == 2:
        endian = ">"
    else:
        raise ElfError("unsupported ELF byte order")

    if elf_class == 2:
        header_offset = 32
        program_header_shape = "IIQQQQQQ"
        entry_metadata_offset = 54
    elif elf_class == 1:
        header_offset = 28
        program_header_shape = "IIIIIIII"
        entry_metadata_offset = 42
    else:
        raise ElfError("unsupported ELF class")

    try:
        program_header_offset = struct.unpack_from(
            f"{endian}{'Q' if elf_class == 2 else 'I'}", data, header_offset
        )[0]
        entry_size, entry_count = struct.unpack_from(
            f"{endian}HH", data, entry_metadata_offset
        )
    except struct.error as exc:
        raise ElfError("truncated ELF header") from exc

    expected_size = struct.calcsize(f"{endian}{program_header_shape}")
    if entry_size < expected_size:
        raise ElfError("invalid program-header entry size")

    alignments: list[int] = []
    for index in range(entry_count):
        offset = program_header_offset + index * entry_size
        try:
            values = struct.unpack_from(f"{endian}{program_header_shape}", data, offset)
        except struct.error as exc:
            raise ElfError("truncated program-header table") from exc
        if values[0] == LOAD_SEGMENT:
            alignments.append(values[-1])

    if not alignments:
        raise ElfError("ELF contains no LOAD segments")
    return alignments


def inspect_apk(apk_path: Path) -> list[AlignmentResult]:
    try:
        with zipfile.ZipFile(apk_path) as apk:
            libraries = sorted(
                name
                for name in apk.namelist()
                if name.endswith(".so")
                and any(name.startswith(f"lib/{abi}/") for abi in PAGE_SIZE_ABIS)
            )
            if not libraries:
                return []
            return [
                AlignmentResult(name, min(load_segment_alignments(apk.read(name))))
                for name in libraries
            ]
    except zipfile.BadZipFile as exc:
        raise ElfError("APK is not a valid ZIP archive") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK to inspect")
    args = parser.parse_args(argv)

    if not args.apk.is_file():
        parser.error(f"APK does not exist: {args.apk}")

    try:
        results = inspect_apk(args.apk)
    except ElfError as exc:
        print(f"Native alignment check failed: {exc}", file=sys.stderr)
        return 2

    failures = [result for result in results if not result.compatible]
    for result in results:
        status = "PASS" if result.compatible else "FAIL"
        print(
            f"{status} {result.library}: "
            f"minimum LOAD alignment 0x{result.minimum_load_alignment:x}"
        )

    if failures:
        print(
            f"{len(failures)} of {len(results)} native libraries are not 16 KB compatible",
            file=sys.stderr,
        )
        return 1
    print(f"All {len(results)} native libraries are 16 KB compatible")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
