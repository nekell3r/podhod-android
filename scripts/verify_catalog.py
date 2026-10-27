#!/usr/bin/env python3
"""Verify the pinned catalog without modifying either client or backend."""
import hashlib
import json
from pathlib import Path
import subprocess


root = Path(__file__).resolve().parents[1]
lock = json.loads((root / "catalog.lock.json").read_text())
catalog = root / "catalog/exercise-catalog.json"
if hashlib.sha256(catalog.read_bytes()).hexdigest() != lock["sha256"]:
    raise SystemExit("Android catalog SHA-256 mismatch")
if json.loads(catalog.read_text())["version"] != lock["catalogVersion"]:
    raise SystemExit("Android catalog version mismatch")
backend = root.parent / "podhod-backend"
if backend.exists():
    source = subprocess.check_output(["git", "-C", str(backend), "show", lock["revision"] + ":" + lock["path"]])
    if source != catalog.read_bytes():
        raise SystemExit("Android catalog differs from its pinned backend revision")
print("Catalog version, SHA-256 and pinned backend export verified")
