#!/usr/bin/env python3
"""
Telegram Port Integrity and Fidelity Verifier.
Validates PORT_MANIFEST.json, pinned Telegram upstream source, verbatim byte-identity,
transplanted blocks, and repo coverage gates.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys

PINNED_UPSTREAM_REVISION = "21f7d109da6ab3506c9e9577f98c6a38771ab335"
VALID_MODES = {"VERBATIM", "CUT", "SHIM", "COMPOSED", "TRANSPLANT", "NEW", "REFERENCE"}


def compute_sha256(file_path):
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def get_upstream_head(upstream_root):
    # Try git rev-parse first
    try:
        res = subprocess.run(
            ["git", "-C", upstream_root, "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True
        )
        sha = res.stdout.strip()
        if sha:
            return sha
    except Exception:
        pass

    # Fallback to reading .git/HEAD directly
    git_dir = os.path.join(upstream_root, ".git")
    if os.path.isdir(git_dir):
        head_file = os.path.join(git_dir, "HEAD")
        if os.path.isfile(head_file):
            with open(head_file, "r") as f:
                content = f.read().strip()
            if content.startswith("ref: "):
                ref_path = os.path.join(git_dir, content[5:].strip())
                if os.path.isfile(ref_path):
                    with open(ref_path, "r") as rf:
                        return rf.read().strip()
            elif len(content) == 40:
                return content

    # Check for archive metadata or pinned marker
    pinned_marker = os.path.join(upstream_root, ".pinned_revision")
    if os.path.isfile(pinned_marker):
        with open(pinned_marker, "r") as f:
            return f.read().strip()

    return None


def validate_manifest_structure(manifest):
    errors = []
    if not isinstance(manifest, dict):
        return ["Manifest root must be a JSON object"]

    rev = manifest.get("pinned_revision")
    if rev != PINNED_UPSTREAM_REVISION:
        errors.append(
            f"Invalid pinned_revision: expected '{PINNED_UPSTREAM_REVISION}', got '{rev}'"
        )

    files = manifest.get("files")
    if not isinstance(files, list):
        errors.append("'files' field must be a list")
        return errors

    seen_paths = set()
    for idx, f in enumerate(files):
        if not isinstance(f, dict):
            errors.append(f"File record #{idx} is not an object")
            continue

        path = f.get("path")
        if not path or not isinstance(path, str):
            errors.append(f"File record #{idx} missing valid 'path'")
            continue

        if path.startswith("/") or path.startswith("\\") or ".." in path.split("/"):
            errors.append(f"File record '{path}' contains illegal path traversal")

        if path in seen_paths:
            errors.append(f"Duplicate path in manifest: '{path}'")
        seen_paths.add(path)

        mode = f.get("mode")
        if mode not in VALID_MODES:
            errors.append(f"File '{path}' has unknown mode '{mode}'")

        eve_sha = f.get("eve_sha256")
        if not eve_sha or len(eve_sha) != 64:
            errors.append(f"File '{path}' missing valid 64-char 'eve_sha256'")

        if mode in ("VERBATIM", "CUT") or (mode == "SHIM" and f.get("upstream_path")):
            up_path = f.get("upstream_path")
            if not up_path or not isinstance(up_path, str):
                errors.append(f"File '{path}' (mode {mode}) missing 'upstream_path'")
            elif up_path.startswith("/") or ".." in up_path.split("/"):
                errors.append(f"File '{path}' upstream_path contains illegal traversal")

            up_sha = f.get("upstream_sha256")
            if not up_sha or len(up_sha) != 64:
                errors.append(f"File '{path}' (mode {mode}) missing valid 'upstream_sha256'")
        elif mode in ("NEW", "COMPOSED") or (mode == "SHIM" and not f.get("upstream_path")):
            if not f.get("reason"):
                errors.append(f"File '{path}' (mode {mode}) missing 'reason'")

    blocks = manifest.get("blocks", [])
    if not isinstance(blocks, list):
        errors.append("'blocks' field must be a list")
    else:
        for b_idx, b in enumerate(blocks):
            if not isinstance(b, dict):
                errors.append(f"Block #{b_idx} is not an object")
                continue
            for req in ("target_path", "upstream_path", "upstream_lines", "sha256"):
                val = b.get(req)
                if not val or not isinstance(val, str):
                    errors.append(f"Block #{b_idx} missing valid '{req}'")

    return errors


def verify_manifest_files(repo_root, upstream_root, manifest):
    errors = []
    files = manifest.get("files", [])

    for f in files:
        rel_path = f["path"]
        mode = f["mode"]
        expected_eve_sha = f["eve_sha256"]
        local_path = os.path.join(repo_root, rel_path)

        if not os.path.isfile(local_path):
            errors.append(f"Target file does not exist: '{rel_path}'")
            continue

        actual_eve_sha = compute_sha256(local_path)
        if actual_eve_sha != expected_eve_sha:
            errors.append(
                f"Target file hash mismatch on '{rel_path}': "
                f"actual={actual_eve_sha}, expected={expected_eve_sha}"
            )

        if mode in ("NEW", "COMPOSED") or not f.get("upstream_path"):
            continue

        up_rel = f["upstream_path"]
        expected_up_sha = f["upstream_sha256"]
        up_abs = os.path.join(upstream_root, up_rel)

        if not os.path.isfile(up_abs):
            errors.append(
                f"Upstream file does not exist: '{up_rel}' (referenced by '{rel_path}')"
            )
            continue

        actual_up_sha = compute_sha256(up_abs)
        if actual_up_sha != expected_up_sha:
            errors.append(
                f"Upstream file hash mismatch on '{up_rel}': "
                f"actual={actual_up_sha}, expected={expected_up_sha}"
            )

        if mode == "VERBATIM":
            if actual_eve_sha != actual_up_sha:
                errors.append(
                    f"VERBATIM file is not byte-identical to upstream: '{rel_path}'"
                )
            if expected_eve_sha != expected_up_sha:
                errors.append(
                    f"VERBATIM manifest record hashes differ for '{rel_path}'"
                )

        elif mode in ("CUT", "SHIM"):
            # Verify difference exists and is recorded
            if actual_eve_sha == actual_up_sha:
                errors.append(
                    f"File labeled as '{mode}' is byte-identical to upstream (should be VERBATIM): '{rel_path}'"
                )

    return errors


def verify_blocks(repo_root, upstream_root, manifest):
    errors = []
    blocks = manifest.get("blocks", [])

    for idx, b in enumerate(blocks):
        target_path = os.path.join(repo_root, b["target_path"])
        up_path = os.path.join(upstream_root, b["upstream_path"])
        lines_spec = b["upstream_lines"]
        expected_hash = b["sha256"]

        if not os.path.isfile(up_path):
            errors.append(f"Block #{idx} upstream file missing: '{b['upstream_path']}'")
            continue

        if not os.path.isfile(target_path):
            errors.append(f"Block #{idx} target file missing: '{b['target_path']}'")
            continue

        try:
            parts = lines_spec.split("-")
            start = int(parts[0])
            end = int(parts[1]) if len(parts) > 1 else start
        except ValueError:
            errors.append(f"Block #{idx} malformed lines specification: '{lines_spec}'")
            continue

        with open(up_path, "rb") as uf:
            ulines = uf.readlines()

        if start < 1 or end > len(ulines) or start > end:
            errors.append(
                f"Block #{idx} line range out of bounds: {lines_spec} (file has {len(ulines)} lines)"
            )
            continue

        block_bytes = b"".join(ulines[start - 1:end])
        actual_block_hash = hashlib.sha256(block_bytes).hexdigest()

        if actual_block_hash != expected_hash:
            errors.append(
                f"Block #{idx} ({b['upstream_path']}:{lines_spec}) hash mismatch: "
                f"actual={actual_block_hash}, expected={expected_hash}"
            )
            continue

        with open(target_path, "rb") as tf:
            target_bytes = tf.read()

        if block_bytes not in target_bytes:
            errors.append(
                f"Block #{idx} ({b['upstream_path']}:{lines_spec}) not found verbatim in '{b['target_path']}'"
            )

    return errors


def verify_repo_coverage(repo_root, manifest):
    errors = []
    manifest_paths = {f["path"] for f in manifest.get("files", [])}

    # 1. Every file in app/src/main/java/org/telegram/** must be tracked
    org_tg_dir = os.path.join(repo_root, "app/src/main/java/org/telegram")
    if os.path.isdir(org_tg_dir):
        for root, dirs, fnames in os.walk(org_tg_dir):
            for fname in fnames:
                full_path = os.path.join(root, fname)
                rel_path = os.path.relpath(full_path, repo_root)
                if rel_path not in manifest_paths:
                    errors.append(f"Untracked Telegram source file: '{rel_path}'")

    # 2. Every file in com/eve/** that imports or directly references org.telegram.*
    eve_dir = os.path.join(repo_root, "app/src/main/java/com/eve")
    if os.path.isdir(eve_dir):
        for root, dirs, fnames in os.walk(eve_dir):
            for fname in fnames:
                if fname.endswith(".java") or fname.endswith(".kt"):
                    full_path = os.path.join(root, fname)
                    rel_path = os.path.relpath(full_path, repo_root)
                    with open(full_path, "r", errors="ignore") as f:
                        content = f.read()
                    if "org.telegram." in content:
                        if rel_path not in manifest_paths:
                            errors.append(
                                f"Untracked EVE file importing org.telegram: '{rel_path}'"
                            )

    return errors


def run_verification(repo_root, upstream_root):
    print("=== Telegram Port Integrity Verifier ===")
    print(f"Repo root:     {repo_root}")
    print(f"Upstream root: {upstream_root}")

    # 1. Pinned revision check
    actual_head = get_upstream_head(upstream_root)
    if not actual_head:
        print(f"ERROR: Cannot determine git HEAD in upstream root '{upstream_root}'.")
        return 1

    if actual_head != PINNED_UPSTREAM_REVISION:
        print(
            f"ERROR: Upstream revision mismatch! Expected '{PINNED_UPSTREAM_REVISION}', got '{actual_head}'."
        )
        return 1
    print(f"✓ Upstream HEAD matches pinned revision: {actual_head}")

    # 2. Manifest load and structure check
    manifest_path = os.path.join(repo_root, "PORT_MANIFEST.json")
    if not os.path.isfile(manifest_path):
        print(f"ERROR: Missing PORT_MANIFEST.json at '{manifest_path}'.")
        return 1

    with open(manifest_path, "r", encoding="utf-8") as f:
        try:
            manifest = json.load(f)
        except json.JSONDecodeError as e:
            print(f"ERROR: PORT_MANIFEST.json is not valid JSON: {e}")
            return 1

    struct_errors = validate_manifest_structure(manifest)
    if struct_errors:
        print(f"ERROR: Manifest schema validation failed with {len(struct_errors)} error(s):")
        for err in struct_errors:
            print(f"  - {err}")
        return 1
    print("✓ Manifest structure and schema valid.")

    # 3. File hash and mode checks
    file_errors = verify_manifest_files(repo_root, upstream_root, manifest)
    if file_errors:
        print(f"ERROR: File integrity checks failed with {len(file_errors)} error(s):")
        for err in file_errors:
            print(f"  - {err}")
        return 1
    print(f"✓ Verified {len(manifest.get('files', []))} manifest files (hashes & modes).")

    # 4. Transplanted blocks check
    block_errors = verify_blocks(repo_root, upstream_root, manifest)
    if block_errors:
        print(f"ERROR: Block verification failed with {len(block_errors)} error(s):")
        for err in block_errors:
            print(f"  - {err}")
        return 1
    print(f"✓ Verified {len(manifest.get('blocks', []))} transplanted blocks byte-identically.")

    # 5. Coverage check
    cov_errors = verify_repo_coverage(repo_root, manifest)
    if cov_errors:
        print(f"ERROR: Repository coverage check failed with {len(cov_errors)} error(s):")
        for err in cov_errors:
            print(f"  - {err}")
        return 1
    print("✓ Complete repository coverage verified (0 unlisted files).")

    print("\n✓ ALL INTEGRITY GATES PASSED SUCCESSFULLY.")
    return 0


def main():
    parser = argparse.ArgumentParser(
        description="Verify Telegram Port integrity and fidelity against pinned upstream source."
    )
    parser.add_argument(
        "--repo-root",
        default=".",
        help="Path to the EVE repository root (default: current directory)",
    )
    parser.add_argument(
        "--upstream-root",
        required=True,
        help="Path to the pinned Telegram-Android source root",
    )
    args = parser.parse_args()

    repo_root = os.path.abspath(args.repo_root)
    upstream_root = os.path.abspath(args.upstream_root)

    sys.exit(run_verification(repo_root, upstream_root))


if __name__ == "__main__":
    main()
