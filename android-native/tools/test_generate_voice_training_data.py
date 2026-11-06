import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from generate_voice_training_data import Product, generate_dataset, load_catalog, write_dataset


def products():
    return [
        Product(1, "Lapte Zuzu 1L", ("lapte zuzu", "zuzu")),
        Product(2, "Lapte Zuzu 2L", ("zuzu doi litri",)),
        Product(3, "Lapte Fulga 1L", ("lapte fulga",)),
        Product(4, "Ouă M 10 buc", ("oua zece",)),
        Product(5, "Ouă M 20 buc", ("oua douazeci",)),
        Product(6, "Pâine albă 500g", ("paine alba",)),
        Product(7, "Pâine neagră 500g", ("paine neagra",)),
        Product(8, "Apă Borsec 2L", ("apa borsec",)),
        Product(9, "Apă Dorna 2L", ("apa dorna",)),
        Product(10, "Ulei floarea soarelui 1L", ("ulei",)),
        Product(11, "Săpun lichid 500ml", ("sapun lichid",)),
        Product(12, "Detergent rufe colorate 2kg", ("detergent colorate",)),
    ]


class GenerateVoiceTrainingDataTest(unittest.TestCase):
    def test_exact_counts_groups_and_deterministic_output(self):
        first = generate_dataset(products(), positive_count=120, negatives_per_positive=2, seed=7)
        second = generate_dataset(products(), positive_count=120, negatives_per_positive=2, seed=7)

        self.assertEqual(first, second)
        self.assertEqual(120, first["positive_count"])
        self.assertEqual(240, first["hard_negative_count"])
        self.assertEqual(360, len(first["examples"]))
        groups = {}
        for row in first["examples"]:
            groups.setdefault(row["group_id"], []).append(row)
        self.assertEqual(120, len(groups))
        self.assertTrue(all(sum(r["label"] for r in rows) == 1 for rows in groups.values()))
        self.assertTrue(all(len(rows) == 3 for rows in groups.values()))

    def test_product_splits_are_disjoint(self):
        dataset = generate_dataset(products(), positive_count=120, seed=11)
        seen = {}
        for row in dataset["examples"]:
            seen.setdefault(row["target_product_id"], set()).add(row["split"])
        self.assertTrue(all(len(splits) == 1 for splits in seen.values()))
        self.assertEqual({"train", "validation", "test"}, {next(iter(s)) for s in seen.values()})

    def test_numeric_and_family_neighbors_are_used_as_hard_negatives(self):
        dataset = generate_dataset(products(), positive_count=12, negatives_per_positive=2, seed=1)
        milk_rows = [
            row for row in dataset["examples"]
            if row["target_product_id"] == 1 and row["label"] == 0
        ]
        self.assertIn(2, {row["candidate_product_id"] for row in milk_rows})
        self.assertIn("numeric_variant", {row["negative_reason"] for row in milk_rows})

    def test_reads_only_product_columns_from_sqlite_catalog(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.db"
            db = sqlite3.connect(path)
            try:
                db.execute("CREATE TABLE products (id INTEGER, name TEXT, aliases TEXT, secret TEXT)")
                db.execute("INSERT INTO products VALUES (1, 'Lapte', 'milk', 'ignore-me')")
                db.execute("INSERT INTO products VALUES (2, 'Pâine', NULL, 'ignore-me-too')")
                db.commit()
            finally:
                db.close()
            loaded = load_catalog(path)
            self.assertEqual([Product(1, "Lapte", ("milk",)), Product(2, "Pâine", ())], loaded)

    def test_written_dataset_contains_no_source_path(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "dataset.json"
            dataset = generate_dataset(products(), positive_count=10, seed=3)
            write_dataset(dataset, output)
            saved = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(dataset, saved)
            self.assertNotIn(str(output), output.read_text(encoding="utf-8"))

    def test_full_dataset_generates_from_the_benchmark_fixture(self):
        android_root = Path(__file__).resolve().parent.parent
        catalog = load_catalog(
            android_root / "app/src/test/resources/voice-benchmark/corpus.json"
        )

        first = generate_dataset(catalog)
        second = generate_dataset(catalog)

        self.assertEqual(first, second)
        self.assertEqual(2500, first["positive_count"])
        self.assertEqual(5000, first["hard_negative_count"])
        self.assertEqual(7500, len(first["examples"]))


if __name__ == "__main__":
    unittest.main()
