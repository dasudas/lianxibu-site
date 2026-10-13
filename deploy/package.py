"""Build immutable, public-safe component artifacts. No deployment or credentials."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import re
import shutil
import tarfile
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
RELATIVE_ASSET = re.compile(r"(?P<quote>['\"])(?P<path>(?:\.{1,2}/|/(?!/))[^'\"]+\.(?:js|css|svg|png|jpg|jpeg|webp|woff2|ico))(?P=quote)")
FORBIDDEN_TEXT = [
    re.compile(rb"sk-[A-Za-z0-9]{20,}"),
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"[A-Za-z0-9.-]+\.mysql\.rds\.[A-Za-z0-9.-]+", re.I),
    re.compile(rb"root@[A-Za-z0-9.-]+"),
]
FORBIDDEN_PARTS = {".env", "secrets", "outbox", "evidence", "screenshots"}
FORBIDDEN_SUFFIXES = {".sql", ".sqlite", ".sqlite3", ".db", ".map", ".pem", ".key", ".p12", ".pfx", ".bak", ".dump"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def reject_path(path: str, component: str) -> None:
    parts = Path(path.replace("\\", "/")).parts
    if any(part.lower() in FORBIDDEN_PARTS for part in parts):
        raise ValueError("private source path in artifact")
    if component == "frontend" and any(part.lower() in {"backend", "collector"} for part in parts):
        raise ValueError("frontend contains foreign source path")
    if any(part.lower().startswith(".env") for part in parts) or Path(path).suffix.lower() in FORBIDDEN_SUFFIXES:
        raise ValueError("forbidden artifact path")


def reject_text(raw: bytes) -> None:
    if any(pattern.search(raw) for pattern in FORBIDDEN_TEXT):
        raise ValueError("suspected private value in artifact")


def inspect_jar(path: Path) -> None:
    if not zipfile.is_zipfile(path):
        raise ValueError("backend artifact must be a JAR")
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if "BOOT-INF/classes/application.yaml" not in names:
            raise ValueError("backend bootstrap resource missing")
        for name in names:
            reject_path(name, "backend")
            if name.startswith("BOOT-INF/classes/") and not name.endswith("/"):
                reject_text(archive.read(name))
            if name.startswith("BOOT-INF/classes/"):
                component_path = name[len("BOOT-INF/classes/"):]
                foreign_source = Path(component_path).suffix.lower() in {".go", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".css", ".html", ".vue", ".svelte"}
                if component_path.startswith(("frontend/", "collector/")) or foreign_source:
                    raise ValueError("foreign source embedded in backend")


def inspect_collector(path: Path) -> None:
    with path.open("rb") as source:
        header = source.read(20)
    if len(header) < 20 or header[:5] != b"\x7fELF\x02" or header[5] != 1 or int.from_bytes(header[18:20], "little") != 62:
        raise ValueError("collector artifact must be linux/amd64 ELF")
    reject_text(path.read_bytes())


def fingerprint_frontend(source: Path, target: Path) -> dict[str, str]:
    if not (source / "index.html").is_file():
        raise ValueError("frontend index missing")
    if (source / "index.html").is_symlink():
        raise ValueError("frontend index symlink forbidden")
    assets = [path for path in source.rglob("*") if path.is_file() and path.name != "index.html"]
    for path in assets:
        relative = path.relative_to(source).as_posix()
        if relative in {"asset-map.json", "cache-policy.json"}:
            raise ValueError("frontend artifact metadata name is reserved")
        reject_path(relative, "frontend")
        if path.is_symlink():
            raise ValueError("frontend symlink forbidden")
    mapping: dict[str, str] = {}
    visiting: set[str] = set()

    def rewrite_relative(text: str, parent: str) -> str:
        def replace(match: re.Match[str]) -> str:
            path = match.group("path")
            root_relative = path.startswith("/")
            dependency = posixpath.normpath(path[1:] if root_relative else posixpath.join(posixpath.dirname(parent), path))
            if dependency.startswith("../") or dependency not in available:
                raise ValueError("unresolved or escaping frontend import")
            resolved = visit(dependency)
            new_path = "/" + resolved if root_relative else posixpath.relpath(resolved, posixpath.dirname(parent) or ".")
            if not root_relative and not new_path.startswith("."):
                new_path = "./" + new_path
            quote = match.group("quote")
            return quote + new_path + quote

        rewritten = RELATIVE_ASSET.sub(replace, text)
        dynamic_import = re.search(r"import\s*\(\s*`", rewritten)
        css_asset = Path(parent).suffix.lower() in {".css", ".svg", ".html"} and re.search(r"url\(\s*[^'\"\s)]", rewritten)
        if dynamic_import or css_asset:
            raise ValueError("unverifiable dynamic asset reference")
        return rewritten

    available = {path.relative_to(source).as_posix(): path for path in assets}

    def visit(relative: str) -> str:
        if relative in mapping:
            return mapping[relative]
        if relative in visiting:
            raise ValueError("cyclic frontend asset dependency")
        visiting.add(relative)
        path = available[relative]
        raw = path.read_bytes()
        if path.suffix.lower() in {".js", ".css", ".svg"}:
            text = raw.decode("utf-8", "strict")
            if re.search(r"(?:import|from)\s*['\"]\.\./(?:backend|collector)/", text):
                raise ValueError("cross-component frontend import")
            raw = rewrite_relative(text, relative).encode("utf-8")
            reject_text(raw)
        else:
            reject_text(raw)
        digest = hashlib.sha256(raw).hexdigest()[:12]
        output = posixpath.join(posixpath.dirname(relative), f"{path.stem}.{digest}{path.suffix}")
        destination = target / output
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        mapping[relative] = output
        visiting.remove(relative)
        return output

    for relative in sorted(available):
        visit(relative)
    index = rewrite_relative((source / "index.html").read_text(encoding="utf-8"), "index.html")
    if any(f'"./{relative}"' in index or f"'./{relative}'" in index for relative in mapping):
        raise ValueError("unfingerprinted frontend entry")
    reject_text(index.encode("utf-8"))
    (target / "index.html").write_text(index, encoding="utf-8")
    (target / "asset-map.json").write_text(json.dumps(mapping, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    (target / "cache-policy.json").write_text(json.dumps({
        "index.html": "no-cache", "runtime-config": "no-cache", "fingerprinted_assets": "public,max-age=31536000,immutable"
    }, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return mapping


def contract_versions() -> dict[str, str]:
    contracts = ROOT / "contracts"
    business = json.loads((contracts / "business.openapi.json").read_text(encoding="utf-8"))
    collector = json.loads((contracts / "collector.openapi.json").read_text(encoding="utf-8"))
    schemas = json.loads((contracts / "schemas.json").read_text(encoding="utf-8"))
    wire = schemas["$defs"]["ObservationEnvelope"]["oneOf"][0]["properties"]["protocol_revision"]["const"]
    return {"business_api": business["info"]["version"], "collector_api": collector["info"]["version"], "collector_wire": wire}


def lock_hashes(component: str) -> dict[str, str]:
    paths = {
        "backend": ["backend/pom.xml", "backend/dependency-lock.dot"],
        "collector": ["collector/go.mod", "collector/go.sum"],
        "frontend": ["frontend/package-lock.json"],
    }[component]
    return {path: sha256(ROOT / path) for path in paths}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("component", choices=["backend", "collector", "frontend"])
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--toolchain", required=True)
    args = parser.parse_args()
    for label in (args.version, args.build_id, args.toolchain):
        if not SAFE_NAME.fullmatch(label):
            raise ValueError("artifact metadata requires a safe nonsecret label")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_commit):
        raise ValueError("source commit must be a full Git SHA")
    source = args.input.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    destination = output / args.component / args.version
    if destination.exists():
        raise ValueError("immutable artifact version already exists")
    with tempfile.TemporaryDirectory(prefix="package-", dir=output) as temporary:
        work = Path(temporary)
        payload = work / "payload"
        payload.mkdir()
        artifact_name = f"{args.component}-{args.version}"
        if args.component == "backend":
            inspect_jar(source)
            artifact = payload / f"{artifact_name}.jar"
            shutil.copyfile(source, artifact)
        elif args.component == "collector":
            inspect_collector(source)
            artifact = payload / artifact_name
            shutil.copyfile(source, artifact)
        else:
            artifact = payload / f"{artifact_name}.tar.gz"
            staged = work / "site"
            staged.mkdir()
            fingerprint_frontend(source, staged)
            with tarfile.open(artifact, "w:gz") as archive:
                for path in sorted(staged.rglob("*")):
                    if path.is_file():
                        archive.add(path, arcname=path.relative_to(staged).as_posix(), recursive=False)
        manifest = {
        "component": args.component,
        "version": args.version,
        "source_commit": args.source_commit,
        "build_id": args.build_id,
        "artifact_name": artifact.name,
        "artifact_sha256": sha256(artifact),
        "build_tool": args.toolchain,
        "lock_sha256": lock_hashes(args.component),
        "supported_contracts": contract_versions(),
        "required_features": [],
        }
        if args.component == "backend":
            manifest.update(schema_min=None, schema_max=None)
        if args.component == "collector":
            manifest.update(queue_schema_min=None, queue_schema_max=None)
        if args.component == "frontend":
            manifest.update(minimum_business_contract_revision=None)
        (payload / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not payload.resolve().is_relative_to(output) or not destination.resolve().is_relative_to(output):
            raise ValueError("artifact path escapes output directory")
        payload.rename(destination)
        print(json.dumps({"component": args.component, "version": args.version,
                          "artifact_sha256": manifest["artifact_sha256"]}, sort_keys=True))


if __name__ == "__main__":
    main()
