import contextlib
import io
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from check_native_page_alignment import ElfError, inspect_apk, load_segment_alignments, main


def elf64(*alignments: int) -> bytes:
    header_size = 64
    entry_size = 56
    data = bytearray(header_size + entry_size * len(alignments))
    data[:4] = b"\x7fELF"
    data[4] = 2
    data[5] = 1
    struct.pack_into("<Q", data, 32, header_size)
    struct.pack_into("<HH", data, 54, entry_size, len(alignments))
    for index, alignment in enumerate(alignments):
        struct.pack_into(
            "<IIQQQQQQ",
            data,
            header_size + index * entry_size,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            alignment,
        )
    return bytes(data)


class NativePageAlignmentTest(unittest.TestCase):
    def test_reads_every_load_segment_alignment(self) -> None:
        self.assertEqual([0x4000, 0x10000], load_segment_alignments(elf64(0x4000, 0x10000)))

    def test_rejects_malformed_or_loadless_elf(self) -> None:
        with self.assertRaisesRegex(ElfError, "ELF magic"):
            load_segment_alignments(b"not an ELF")
        with self.assertRaisesRegex(ElfError, "no LOAD"):
            load_segment_alignments(elf64())

    def test_inspects_page_size_abis_and_ignores_legacy_32_bit_abis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk_path, "w") as apk:
                apk.writestr("lib/arm64-v8a/good.so", elf64(0x4000))
                apk.writestr("lib/arm64-v8a/bad.so", elf64(0x1000))
                apk.writestr("lib/armeabi-v7a/legacy.so", elf64(0x1000))
                apk.writestr("classes.dex", b"ignored")

            results = inspect_apk(apk_path)

        self.assertEqual(["lib/arm64-v8a/bad.so", "lib/arm64-v8a/good.so"], [r.library for r in results])
        self.assertEqual([False, True], [r.compatible for r in results])

    def test_cli_fails_an_incompatible_apk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk_path = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk_path, "w") as apk:
                apk.writestr("lib/x86_64/bad.so", elf64(0x1000))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, main([str(apk_path)]))


if __name__ == "__main__":
    unittest.main()
