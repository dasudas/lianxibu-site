"""Read-only upgrade/rollback checks. Never switches a service or migrates data."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
from pathlib import Path
import release_config

COMPONENTS = {"backend", "collector", "frontend"}

def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

def version(value):
    if not isinstance(value, str) or not re.fullmatch(r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){1,2}", value):
        raise ValueError("unknown_contract_revision")
    return tuple(int(part) for part in value.split(".")) + (0,) * (3 - len(value.split(".")))

def load_manifest(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("invalid_manifest_path")
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("component") not in COMPONENTS:
        raise ValueError("unknown_component")
    name = value.get("artifact_name")
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}", name) or name in {".", ".."}:
        raise ValueError("artifact_path_escape")
    artifact = path.parent / name
    if artifact.is_symlink() or not artifact.is_file() or artifact.resolve().parent != path.parent.resolve():
        raise ValueError("invalid_artifact_path")
    expected = value.get("artifact_sha256")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected) or digest(artifact) != expected:
        raise ValueError("artifact_hash_mismatch")
    if not isinstance(value.get("required_features"), list) or any(not isinstance(item, str) for item in value["required_features"]):
        raise ValueError("unknown_required_features")
    contracts = value.get("supported_contracts")
    if not isinstance(contracts, dict):
        raise ValueError("unknown_contracts")
    for key in ("business_api", "collector_api", "collector_wire"):
        version(contracts.get(key))
    return value

def bounds(manifest, prefix, actual):
    low, high = manifest.get(prefix + "_min"), manifest.get(prefix + "_max")
    if any(type(item) is not int or item < 0 for item in (low, high, actual)):
        return "unknown_" + prefix + "_compatibility"
    if not low <= actual <= high:
        return "incompatible_" + prefix
    return None

def check(current_paths, candidate_path, facts, backend_env_path=None):
    current = [load_manifest(path) for path in current_paths]
    if len(current) != 3 or {item["component"] for item in current} != COMPONENTS:
        raise ValueError("three_unique_current_components_required")
    selected = {item["component"]: item for item in current}
    candidate = load_manifest(candidate_path)
    selected[candidate["component"]] = candidate
    backend, collector, frontend = (selected[key] for key in ("backend", "collector", "frontend"))
    errors = []
    for error in (bounds(backend, "schema", facts.get("schema_revision")), bounds(collector, "queue_schema", facts.get("queue_schema_revision"))):
        if error:
            errors.append(error)
    # Manifest versions alone do not prove optional backwards compatibility.
    # Initially require the exact tested contract set; do not infer support from a matching major.
    for consumer, keys in ((frontend, ("business_api",)), (collector, ("collector_api", "collector_wire"))):
        for key in keys:
            if backend["supported_contracts"][key] != consumer["supported_contracts"][key]:
                errors.append("unproven_" + key + "_pair")
    try:
        if version(frontend.get("minimum_business_contract_revision")) > version(backend["supported_contracts"]["business_api"]):
            errors.append("frontend_minimum_not_met")
    except ValueError:
        errors.append("unknown_frontend_minimum")
    capability = facts.get("backend_capabilities", {})
    provided = capability.get("supported_features")
    if capability.get("artifact_sha256") != backend["artifact_sha256"] or not isinstance(provided, list) or any(not isinstance(item, str) for item in provided):
        errors.append("unproven_backend_capabilities")
    else:
        for item in selected.values():
            if not set(item["required_features"]).issubset(provided):
                errors.append(item["component"] + "_required_feature_missing")
    gates = facts.get("release_gates", {})
    missing = [key for key in ("V03", "V05", "V07", "V08", "V09") if gates.get(key) is not True]
    compatible = not errors
    runtime = release_config.verify(backend_env_path)
    errors.extend(runtime["errors"])
    return {"component": candidate["component"], "artifact_sha256": candidate["artifact_sha256"],
            "compatibility_verified": compatible, "runtime_configuration_verified": runtime["verified"],
            "backend_environment_sha256": runtime["sha256"], "errors": sorted(set(errors)),
            "missing_release_gates": missing, "release_ready": not errors and not missing,
            "action": "read_only_preflight"}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--current", nargs=3, type=Path, required=True, metavar="MANIFEST")
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--facts", type=Path, required=True, help="Controlled observed compatibility/gate evidence; never commit live facts")
    parser.add_argument("--backend-env", type=Path, required=True, help="Controlled private backend EnvironmentFile; values are never printed")
    args = parser.parse_args()
    try:
        result = check(args.current, args.candidate, json.loads(args.facts.read_text(encoding="utf-8")), args.backend_env)
    except (ValueError, OSError, TypeError, KeyError, AttributeError):
        # Do not echo private paths, content, or connection details from malformed runtime input.
        result = {"release_ready": False, "errors": ["invalid_or_unreadable_preflight_input"], "action": "read_only_preflight"}
    print(json.dumps(result, sort_keys=True))
    return 0 if result["release_ready"] else 2

if __name__ == "__main__":
    raise SystemExit(main())
