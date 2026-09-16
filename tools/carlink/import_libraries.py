#!/usr/bin/env python3
"""Import the user-supplied, hash-pinned Android 10 ARM64 receiver libraries."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path(__file__).with_name("libraries.json")
DESTINATION = ROOT / "app/src/main/jniLibs/arm64-v8a"


def patch_library(data, entry):
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise ValueError(f"Unrecognized native ABI: {entry['name']}")
    for patch in entry["patches"]:
        old = patch["from"].encode() + b"\0"
        new = patch["to"].encode() + b"\0"
        if len(new) > len(old) or data.count(old) != patch["count"]:
            raise ValueError(f"Unexpected string layout: {entry['name']}")
        data = data.replace(old, new.ljust(len(old), b"\0"))
    return data


def main():
    manifest = json.loads(MANIFEST.read_text())
    if "--verify" in sys.argv:
        for entry in manifest["libraries"]:
            data = (DESTINATION / entry["name"]).read_bytes()
            if hashlib.sha256(data).hexdigest() != entry["importedSha256"]:
                raise ValueError(f"Imported library changed: {entry['name']}")
        print("Carlink imported library hashes verified")
        return
    # Validate every input before changing any installed library.
    imported = [(entry, patch_library((ROOT / entry["source"]).read_bytes(), entry))
                for entry in manifest["libraries"]]
    for entry, data in imported:
        if hashlib.sha256(data).hexdigest() != entry["importedSha256"]:
            raise ValueError(f"Unexpected patched library: {entry['name']}")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for entry, data in imported:
        (DESTINATION / entry["name"]).write_bytes(data)
        entry["importedSha256"] = hashlib.sha256(data).hexdigest()
    (DESTINATION.parent / "carlink-libraries.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Imported {len(imported)} verified libraries into {DESTINATION}")


if __name__ == "__main__":
    main()
