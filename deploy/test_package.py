import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import package as artifacts


class PackageTests(unittest.TestCase):
    def test_fingerprints_entries_and_relative_imports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "input"
            output = root / "output"
            (source / "src").mkdir(parents=True)
            (source / "src" / "entry.js").write_text("import './module.js';\n", encoding="utf-8")
            (source / "src" / "module.js").write_text("export const answer = 42;\n", encoding="utf-8")
            (source / "src" / "style.css").write_text("body { color: navy; }\n", encoding="utf-8")
            (source / "index.html").write_text(
                '<link href="./src/style.css" rel="stylesheet"><script type="module" src="./src/entry.js"></script>',
                encoding="utf-8")
            mapping = artifacts.fingerprint_frontend(source, output)
            index = (output / "index.html").read_text(encoding="utf-8")
            entry = (output / mapping["src/entry.js"]).read_text(encoding="utf-8")
            self.assertIn(mapping["src/entry.js"], index)
            self.assertIn(mapping["src/style.css"], index)
            self.assertIn(Path(mapping["src/module.js"]).name, entry)
            self.assertNotIn("./module.js", entry)
            self.assertEqual(json.loads((output / "cache-policy.json").read_text())["index.html"], "no-cache")

    def test_rejects_private_files_and_cross_component_imports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "input"
            (source / "src").mkdir(parents=True)
            (source / "index.html").write_text('<script src="./src/entry.js"></script>', encoding="utf-8")
            entry = source / "src" / "entry.js"
            entry.write_text("export const ready = true;", encoding="utf-8")
            private = source / "src" / ".env"
            private.write_text("fictional=true", encoding="utf-8")
            with self.assertRaises(ValueError):
                artifacts.fingerprint_frontend(source, root / "output")
            private.unlink()
            entry.write_text("import '../backend/private.js';", encoding="utf-8")
            with self.assertRaises(ValueError):
                artifacts.fingerprint_frontend(source, root / "output")

    def test_rejects_private_backend_resource_and_invalid_collector(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "backend.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/classes/application.yaml", "ready: true\n")
                archive.writestr("BOOT-INF/classes/.env", "fictional=true\n")
            with self.assertRaises(ValueError):
                artifacts.inspect_jar(jar)
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/classes/application.yaml", "ready: true\n")
                archive.writestr("BOOT-INF/classes/Private.class", b"sk" + b"-" + b"a" * 24)
            with self.assertRaises(ValueError):
                artifacts.inspect_jar(jar)
            collector = root / "collector"
            collector.write_bytes(b"not-an-ELF-binary")
            with self.assertRaises(ValueError):
                artifacts.inspect_collector(collector)

    def test_rejects_secret_signature_in_artifact_content(self):
        marker = b"sk" + b"-" + b"a" * 24
        with self.assertRaises(ValueError):
            artifacts.reject_text(marker)


if __name__ == "__main__":
    unittest.main()
