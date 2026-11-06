import json
import tempfile
import unittest
from pathlib import Path

from voice_corpus import CorpusError, PRIVACY_MODE, append_confirmed_case


def sample_corpus() -> dict:
    return {
        "schema_version": 1,
        "privacy_mode": PRIVACY_MODE,
        "products": [{"id": 1, "name": "Lapte Zuzu 1L", "aliases": "zuzu"}],
        "cases": [],
    }


class VoiceCorpusTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.corpus_path = Path(self.temp_dir.name) / "corpus.json"
        self.corpus_path.write_text(
            json.dumps(sample_corpus(), ensure_ascii=False), encoding="utf-8"
        )

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def append(self, **overrides):
        values = {
            "case_id": "real-s25-clean-001",
            "utterance": "două lapte Zuzu",
            "expected_product_id": 1,
            "locale": "ro-RO",
            "noise_condition": "clean",
            "confirmed_text_only": True,
        }
        values.update(overrides)
        return append_confirmed_case(self.corpus_path, **values)

    def test_appends_only_the_text_label_and_metadata(self) -> None:
        added = self.append()
        saved = json.loads(self.corpus_path.read_text(encoding="utf-8"))

        self.assertEqual(added, saved["cases"][0])
        self.assertEqual("două lapte Zuzu", added["utterance"])
        self.assertEqual(
            {
                "id",
                "utterance",
                "expected_product_id",
                "locale",
                "noise_condition",
            },
            set(added),
        )

    def test_requires_explicit_confirmation(self) -> None:
        before = self.corpus_path.read_bytes()

        with self.assertRaisesRegex(CorpusError, "confirmation"):
            self.append(confirmed_text_only=False)

        self.assertEqual(before, self.corpus_path.read_bytes())

    def test_rejects_duplicates_and_unknown_products_without_modifying_file(self) -> None:
        self.append()
        after_first = self.corpus_path.read_bytes()

        with self.assertRaisesRegex(CorpusError, "duplicate"):
            self.append()
        self.assertEqual(after_first, self.corpus_path.read_bytes())

        with self.assertRaisesRegex(CorpusError, "unknown expected product"):
            self.append(case_id="real-s25-clean-002", expected_product_id=99)
        self.assertEqual(after_first, self.corpus_path.read_bytes())

    def test_requires_real_id_and_supported_noise_label(self) -> None:
        with self.assertRaisesRegex(CorpusError, "start with 'real-'"):
            self.append(case_id="synthetic-ro-clean-999")
        with self.assertRaisesRegex(CorpusError, "noise_condition"):
            self.append(noise_condition="warehouse-name")


if __name__ == "__main__":
    unittest.main()
