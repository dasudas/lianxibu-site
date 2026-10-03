import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import preflight

class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.current = [self.manifest(c, "fictional-current") for c in ("backend", "collector", "frontend")]
        self.candidate = self.manifest("frontend", "fictional-next")
        self.facts = {"schema_revision": 2, "queue_schema_revision": 3,
                      "backend_capabilities": {"artifact_sha256": self.read(self.current[0])["artifact_sha256"], "supported_features": ["fictional_feature"]},
                      "release_gates": {key: True for key in ("V03", "V05", "V07", "V08", "V09")}}
    def manifest(self, component, version):
        directory = self.root / component / version
        directory.mkdir(parents=True)
        payload = (component + version).encode()
        (directory / "fictional-artifact").write_bytes(payload)
        value = {"component": component, "version": version, "artifact_name": "fictional-artifact", "artifact_sha256": hashlib.sha256(payload).hexdigest(),
                 "required_features": ["fictional_feature"], "supported_contracts": {"business_api": "1.0", "collector_api": "1.0", "collector_wire": "1.0"},
                 "schema_min": 1, "schema_max": 2, "queue_schema_min": 3, "queue_schema_max": 3, "minimum_business_contract_revision": "1.0"}
        path = directory / "manifest.json"
        path.write_text(json.dumps(value))
        return path
    def read(self, path): return json.loads(path.read_text())
    def change(self, path, **values):
        data = self.read(path); data.update(values); path.write_text(json.dumps(data))
    def check(self): return preflight.check(self.current, self.candidate, self.facts)
    def test_upgrade_and_rollback_apply_same_read_only_checks(self):
        before = {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        self.assertTrue(self.check()["release_ready"])
        self.assertTrue(preflight.check([*self.current[:2], self.candidate], self.current[2], self.facts)["release_ready"])
        self.assertEqual(before, {path: path.read_bytes() for path in self.root.rglob("*") if path.is_file()})
    def test_bootstrap_unknown_ranges_and_unfinished_gates_never_pass(self):
        self.change(self.current[0], schema_min=None)
        self.assertIn("unknown_schema_compatibility", self.check()["errors"])
        self.change(self.current[0], schema_min=1)
        self.facts["release_gates"]["V03"] = False
        result = self.check(); self.assertTrue(result["compatibility_verified"]); self.assertFalse(result["release_ready"])
    def test_incompatible_queue_and_missing_capability_refuse(self):
        self.facts["queue_schema_revision"] = 4
        self.facts["backend_capabilities"]["supported_features"] = []
        self.assertIn("incompatible_queue_schema", self.check()["errors"])
        self.assertIn("frontend_required_feature_missing", self.check()["errors"])
    def test_hash_or_path_changes_refuse_before_compatibility(self):
        (self.candidate.parent / "fictional-artifact").write_text("changed")
        with self.assertRaisesRegex(ValueError, "hash_mismatch"): self.check()
        self.change(self.candidate, artifact_name="../private")
        with self.assertRaisesRegex(ValueError, "path_escape"): self.check()
    def test_backend_change_needs_candidate_bound_capability_evidence(self):
        self.candidate = self.manifest("backend", "fictional-next")
        self.assertIn("unproven_backend_capabilities", self.check()["errors"])
        self.facts["backend_capabilities"]["artifact_sha256"] = self.read(self.candidate)["artifact_sha256"]
        self.assertTrue(self.check()["release_ready"])
    def test_unproven_contract_pair_and_missing_frontend_minimum_refuse(self):
        self.change(self.candidate, supported_contracts={"business_api": "1.1", "collector_api": "1.0", "collector_wire": "1.0"}, minimum_business_contract_revision=None)
        self.assertIn("unproven_business_api_pair", self.check()["errors"])
        self.assertIn("unknown_frontend_minimum", self.check()["errors"])

if __name__ == "__main__": unittest.main()
