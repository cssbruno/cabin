import hashlib
import json
import unittest
from pathlib import Path
from import_libraries import DESTINATION, MANIFEST, ROOT, patch_library


class LibraryImportTest(unittest.TestCase):
    def test_rejects_an_unrecognized_binary_before_patching(self):
        with self.assertRaisesRegex(ValueError, "Unrecognized native ABI"):
            patch_library(b"unknown engine", {"name": "engine", "sha256": "0" * 64})

    def test_patches_preserve_layout_and_reject_missing_strings(self):
        original = b"prefix\0/proc/mysocket\0suffix"
        entry = {"name": "engine", "sha256": hashlib.sha256(original).hexdigest(),
                 "patches": [{"from": "/proc/mysocket", "to": "cabin.carlink", "count": 1}]}
        patched = patch_library(original, entry)
        self.assertEqual(len(original), len(patched))
        self.assertTrue(patched.endswith(b"suffix"))
        self.assertIn(b"cabin.carlink\0", patched)
        entry["patches"][0]["count"] = 2
        with self.assertRaisesRegex(ValueError, "Unexpected string layout"):
            patch_library(original, entry)

    def test_imported_files_match_pinned_hashes_and_have_private_endpoints(self):
        for entry in json.loads(MANIFEST.read_text())["libraries"]:
            data = (DESTINATION / entry["name"]).read_bytes()
            self.assertEqual(entry["importedSha256"], hashlib.sha256(data).hexdigest(), entry["name"])
            self.assertNotIn(b"/proc/mysocket\0", data)
            self.assertNotIn(b"/data/KeyChains", data)
            self.assertNotIn(b"com.syu.carlink", data)
            self.assertNotIn(b"com.syu.cpres", data)


if __name__ == "__main__":
    unittest.main()
