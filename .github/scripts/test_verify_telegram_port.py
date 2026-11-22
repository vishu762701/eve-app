#!/usr/bin/env python3
"""
Unit tests for verify_telegram_port.py.
Exercises every failure mode using temporary file fixtures without network access.
"""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest

from verify_telegram_port import (
    PINNED_UPSTREAM_REVISION,
    validate_manifest_structure,
    verify_manifest_files,
    verify_blocks,
    verify_repo_coverage,
    run_verification,
)


def sha256_text(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class TestTelegramPortVerifier(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.repo_root = os.path.join(self.test_dir, "repo")
        self.upstream_root = os.path.join(self.test_dir, "upstream")
        os.makedirs(self.repo_root)
        os.makedirs(self.upstream_root)

        # Initialize mock upstream git head
        git_dir = os.path.join(self.upstream_root, ".git")
        os.makedirs(git_dir)
        with open(os.path.join(git_dir, "HEAD"), "w") as f:
            f.write(PINNED_UPSTREAM_REVISION + "\n")

        # Standard file fixtures
        self.up_file_rel = "TMessagesProj/src/main/java/org/telegram/Sample.java"
        self.repo_file_rel = "app/src/main/java/org/telegram/Sample.java"

        up_file_abs = os.path.join(self.upstream_root, self.up_file_rel)
        os.makedirs(os.path.dirname(up_file_abs), exist_ok=True)
        self.sample_content = (
            "package org.telegram;\n"
            "public class Sample {\n"
            "    public static final int CONSTANT = 42;\n"
            "}\n"
        )
        with open(up_file_abs, "w") as f:
            f.write(self.sample_content)

        repo_file_abs = os.path.join(self.repo_root, self.repo_file_rel)
        os.makedirs(os.path.dirname(repo_file_abs), exist_ok=True)
        with open(repo_file_abs, "w") as f:
            f.write(self.sample_content)

        self.sample_hash = sha256_text(self.sample_content)

        # Standard block fixture
        block_bytes = "    public static final int CONSTANT = 42;\n".encode("utf-8")
        self.block_hash = hashlib.sha256(block_bytes).hexdigest()

        self.valid_manifest = {
            "pinned_revision": PINNED_UPSTREAM_REVISION,
            "schema_version": 2,
            "files": [
                {
                    "path": self.repo_file_rel,
                    "upstream_path": self.up_file_rel,
                    "upstream_sha256": self.sample_hash,
                    "mode": "VERBATIM",
                    "eve_sha256": self.sample_hash,
                }
            ],
            "blocks": [
                {
                    "target_path": self.repo_file_rel,
                    "upstream_path": self.up_file_rel,
                    "upstream_lines": "3-3",
                    "sha256": self.block_hash,
                }
            ],
        }

        with open(os.path.join(self.repo_root, "PORT_MANIFEST.json"), "w") as f:
            json.dump(self.valid_manifest, f)

    def tearDown(self):
        shutil.rmtree(self.test_dir)

    def test_valid_manifest_and_files_pass(self):
        code = run_verification(self.repo_root, self.upstream_root)
        self.assertEqual(code, 0)

    def test_wrong_upstream_revision_fails(self):
        with open(os.path.join(self.upstream_root, ".git", "HEAD"), "w") as f:
            f.write("0000000000000000000000000000000000000000\n")
        code = run_verification(self.repo_root, self.upstream_root)
        self.assertNotEqual(code, 0)

    def test_missing_upstream_file_fails(self):
        os.remove(os.path.join(self.upstream_root, self.up_file_rel))
        errors = verify_manifest_files(self.repo_root, self.upstream_root, self.valid_manifest)
        self.assertTrue(any("Upstream file does not exist" in e for e in errors))

    def test_missing_target_file_fails(self):
        os.remove(os.path.join(self.repo_root, self.repo_file_rel))
        errors = verify_manifest_files(self.repo_root, self.upstream_root, self.valid_manifest)
        self.assertTrue(any("Target file does not exist" in e for e in errors))

    def test_modified_verbatim_file_fails(self):
        with open(os.path.join(self.repo_root, self.repo_file_rel), "a") as f:
            f.write("// modified\n")
        errors = verify_manifest_files(self.repo_root, self.upstream_root, self.valid_manifest)
        self.assertTrue(any("hash mismatch" in e for e in errors))

    def test_stale_eve_or_upstream_sha_fails(self):
        bad_manifest = dict(self.valid_manifest)
        bad_manifest["files"][0]["eve_sha256"] = "a" * 64
        errors = verify_manifest_files(self.repo_root, self.upstream_root, bad_manifest)
        self.assertTrue(any("Target file hash mismatch" in e for e in errors))

    def test_wrong_source_line_range_or_hash_fails(self):
        bad_manifest = dict(self.valid_manifest)
        bad_manifest["blocks"][0]["upstream_lines"] = "99-100"
        errors = verify_blocks(self.repo_root, self.upstream_root, bad_manifest)
        self.assertTrue(any("out of bounds" in e for e in errors))

    def test_transplant_target_bytes_differ_fails(self):
        # Change target file so block is no longer present
        with open(os.path.join(self.repo_root, self.repo_file_rel), "w") as f:
            f.write("class CompletelyDifferent {}\n")
        errors = verify_blocks(self.repo_root, self.upstream_root, self.valid_manifest)
        self.assertTrue(any("not found verbatim" in e for e in errors))

    def test_untracked_telegram_file_fails_coverage(self):
        extra_file = os.path.join(self.repo_root, "app/src/main/java/org/telegram/Extra.java")
        with open(extra_file, "w") as f:
            f.write("package org.telegram;\n")
        errors = verify_repo_coverage(self.repo_root, self.valid_manifest)
        self.assertTrue(any("Untracked Telegram source file" in e for e in errors))

    def test_untracked_eve_importing_telegram_fails_coverage(self):
        extra_eve = os.path.join(self.repo_root, "app/src/main/java/com/eve/MyActivity.kt")
        os.makedirs(os.path.dirname(extra_eve), exist_ok=True)
        with open(extra_eve, "w") as f:
            f.write("package com.eve\nimport org.telegram.Sample\n")
        errors = verify_repo_coverage(self.repo_root, self.valid_manifest)
        self.assertTrue(any("Untracked EVE file importing org.telegram" in e for e in errors))

    def test_path_traversal_fails(self):
        bad_manifest = dict(self.valid_manifest)
        bad_manifest["files"][0]["path"] = "app/../../secret"
        errors = validate_manifest_structure(bad_manifest)
        self.assertTrue(any("illegal path traversal" in e for e in errors))

    def test_duplicate_paths_fail(self):
        bad_manifest = dict(self.valid_manifest)
        bad_manifest["files"].append(dict(bad_manifest["files"][0]))
        errors = validate_manifest_structure(bad_manifest)
        self.assertTrue(any("Duplicate path" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
