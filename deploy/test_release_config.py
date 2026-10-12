import hashlib
import tempfile
import unittest
from pathlib import Path
import release_config


class ReleaseConfigTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "fictional-backend.env"
        self.valid = "# Fictional runtime settings\n" + "\n".join(key + "=true" for key in release_config.REQUIRED_FLAGS)
        self.path.write_text(self.valid, encoding="utf-8")

    def test_exact_file_is_read_without_exporting_private_values_or_paths(self):
        self.path.write_text(self.valid + "\nSITE_DB_PASSWORD=fictional-private-value\n", encoding="utf-8")
        result = release_config.verify(self.path)
        self.assertTrue(result["verified"])
        self.assertEqual(hashlib.sha256(self.path.read_bytes()).hexdigest(), result["sha256"])
        self.assertNotIn("fictional-private-value", str(result))
        self.assertNotIn(str(self.path), str(result))
        self.assertEqual([], result["errors"])

    def test_every_required_flag_must_be_explicitly_true(self):
        for flag in release_config.REQUIRED_FLAGS:
            with self.subTest(flag=flag):
                for replacement in ("", flag + "=false", flag + "=1", flag + "=${FICTIONAL_ENABLE}"):
                    self.path.write_text(self.valid.replace(flag + "=true", replacement), encoding="utf-8")
                    result = release_config.verify(self.path)
                    self.assertFalse(result["verified"])
                    self.assertEqual(["required_runtime_flag_disabled:" + flag], result["errors"])

    def test_literal_quotes_and_whitespace_are_accepted_but_not_executed(self):
        self.path.write_text("\n".join("  " + key + " = 'true'  " for key in release_config.REQUIRED_FLAGS), encoding="utf-8")
        self.assertTrue(release_config.verify(self.path)["verified"])
        self.path.write_text("\n".join(key + '= "true"' for key in release_config.REQUIRED_FLAGS), encoding="utf-8")
        self.assertTrue(release_config.verify(self.path)["verified"])

    def test_duplicates_invalid_lines_and_controls_are_rejected_without_contents(self):
        for extra in ("SITE_DB_PASSWORD=fictional-secret\nSITE_DB_PASSWORD=fictional-second",
                      "export SITE_DB_PASSWORD=fictional-secret", "fictional-secret", "BAD=fictional\x00secret",
                      "BAD='fictional-unclosed", "BAD=\"fictional-unclosed", "BAD=fictional\rsecret"):
            self.path.write_bytes((self.valid + "\n" + extra).encode())
            result = release_config.verify(self.path)
            self.assertFalse(result["verified"])
            self.assertEqual(["invalid_backend_runtime_configuration"], result["errors"])
            self.assertNotIn("fictional-secret", str(result))

    def test_missing_unreadable_oversized_or_non_utf8_file_never_passes(self):
        for path in (None, self.path.parent / "fictional-absent", self.path.parent):
            self.assertFalse(release_config.verify(path)["verified"])
        self.path.write_bytes(b"F" * (release_config.MAX_BYTES + 1))
        self.assertFalse(release_config.verify(self.path)["verified"])
        self.path.write_bytes(b"\xff")
        self.assertFalse(release_config.verify(self.path)["verified"])

    def test_bom_or_duplicate_flag_does_not_silently_change_effective_config(self):
        for raw in ("\ufeff" + self.valid, self.valid + "\nSITE_SOURCES_DAY_ROSTER_ENABLED=false"):
            self.path.write_text(raw, encoding="utf-8")
            self.assertFalse(release_config.verify(self.path)["verified"])


if __name__ == "__main__":
    unittest.main()
