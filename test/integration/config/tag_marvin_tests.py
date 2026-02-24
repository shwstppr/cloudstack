#!/usr/bin/env python3
"""
Auto-tag Marvin/CloudStack test files into categories:
- business: no VM/Network/VPC/Volume/K8s cluster creation calls
- lowres: creation calls <= number of tests (rough proxy)
- hires:  creation calls >  number of tests
- infra:  host/systemvm/cluster/zone update related

This is heuristic/static analysis: expect a few false positives/negatives.
Use manual overrides by editing the output file if needed.
"""

from __future__ import annotations

import argparse
import ast
import json
import os
import re
from dataclasses import dataclass, asdict
from typing import Iterable, Optional, Set, Dict, List

try:
    import yaml  # type: ignore
except Exception:
    yaml = None  # optional


# ---- Heuristic patterns (tune these for your codebase) ----

# "Creation" operations: VM/Network/VPC/Volume/K8s cluster creation.
# We match both direct function names and API/client method names.
CREATION_NAME_PATTERNS = [
    r"\bVirtualMachine.create\b|\bdeployVirtualMachine\b",
    r"\bNetwork.create\b|\bcreateNetwork\b",
    r"\bVPC.create\b|\bVpc.create\b|\bcreateVpc\b|\bcreateVPC\b",
    r"\bVolume.create\b|\bcreateVolume\b",
    r"\bTemplate.create\b|\bTemplate.create\b|\bISO.create\b",
    r"\bcreateKubernetesCluster\b",
    # Common helper naming in tests:
    r"\bcreate_vm\b|\bdeploy_vm\b|\bdeployVm\b",
    r"\bcreate_network\b|\bcreate_vpc\b|\bcreate_volume\b",
    r"\bcreate.*network\b|\bcreate.*Network\b|\bdeploy.*network\b|\bdeploy.*Network\b",
    r"\bcreate.*vpc\b|\bcreate.*Vpc\b|\bdeploy.*vpc\b|\bdeploy.*Vpc\b",
]

# Infra-related operations: host/systemvm/cluster/zone update, maintenance, etc.
INFRA_NAME_PATTERNS = [
    r"\baddHost\b|\bupdateHost\b|\bdeleteHost\b|\bHAForHost\b|\bdeleteHost\b|\breconnectHost\b",
    r"\bupdateCluster\b|\baddCluster\b|\bdeleteCluster\b|\bCluster.create\b|\bCluster.update\b|\bCluster.delete\b",
    r"\bupdateZone\b|\bcreateZone\b|\bdeleteZone\b",
    r"\bSystemVm\b|\bSystemVM\b|\bSSVM\b|\bCPVM\b|\bSecondaryStorageVm\b",
    r"\bstartSystemVm\b|\bstopSystemVm\b|\brebootSystemVm\b",
    r"\bhostha\b|\bHostHA\b|\bHAForHost\b",
    r"\bShutdownCmd\b|\bupdateImageStoreCmd\b|\bStartCommand\b",
    r"\bMaintenance\b|\btriggerShutdownCmd\b|\bcancelShutdownCmd\b|\bmaintenance\b",
    r"\bprovisionCertificate\b|\bconfigureOutOfBandManagement\b",
]

# Some tests might not use obvious method names but include keywords in strings/comments.
INFRA_TEXT_KEYWORDS = [
    "Maintenance", "overprovisioning", "StartCommand", "UPDATE host"
]


# Precompiled regex
CREATION_RE = re.compile("|".join(CREATION_NAME_PATTERNS), re.IGNORECASE)
INFRA_RE = re.compile("|".join(INFRA_NAME_PATTERNS), re.IGNORECASE)
INFRA_TEXT_RE = re.compile("|".join(re.escape(k) for k in INFRA_TEXT_KEYWORDS), re.IGNORECASE)


@dataclass
class FileTagging:
    file: str
    category: str
    num_tests: int
    create_calls: int
    infra_hits: int
    notes: List[str]


class Analyzer(ast.NodeVisitor):
    def __init__(self, source_text: str) -> None:
        self.source_text = source_text
        self.num_tests = 0
        self.create_calls = 0
        self.infra_hits = 0
        self.has_test_methods = False
        self._pass = 1  # Track which pass we're on

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        if node.name.startswith("test_"):
            self.num_tests += 1
            self.has_test_methods = True
        self.generic_visit(node)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        # same logic for async tests
        if node.name.startswith("test_"):
            self.num_tests += 1
            self.has_test_methods = True
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:
        # Count calls if the file has test methods (determined in first pass)
        should_count = self.has_test_methods

        call_name = self._get_call_name(node.func)
        if call_name:
            if should_count and CREATION_RE.search(call_name):
                self.create_calls += 1
            if should_count and INFRA_RE.search(call_name):
                self.infra_hits += 1

        self.generic_visit(node)

    def visit_Constant(self, node: ast.Constant) -> None:
        # Look for infra-ish keywords in string constants as extra signal
        if isinstance(node.value, str):
            if INFRA_TEXT_RE.search(node.value):
                self.infra_hits += 1
        self.generic_visit(node)

    @staticmethod
    def _get_call_name(func: ast.AST) -> Optional[str]:
        # Normalize possible call forms:
        # - foo(...)
        # - obj.foo(...)
        # - self.apiClient.createVirtualMachine(...)
        if isinstance(func, ast.Name):
            return func.id
        if isinstance(func, ast.Attribute):
            # Build dotted name
            parts = []
            cur: Optional[ast.AST] = func
            while isinstance(cur, ast.Attribute):
                parts.append(cur.attr)
                cur = cur.value
            if isinstance(cur, ast.Name):
                parts.append(cur.id)
            parts.reverse()
            return ".".join(parts)
        return None


def categorize(num_tests: int, create_calls: int, infra_hits: int) -> (str, List[str]):
    notes: List[str] = []

    # Priority 1: Infra wins - any infra pattern marks it as infra
    if infra_hits > 0:
        return "infra", [f"infra_hits={infra_hits}"]

    # Priority 2: Business - no creation operations found
    if create_calls == 0:
        return "business", ["no creation calls found"]

    # Priority 3, 4, 5: Lowres, Midres, or Hires - based on create_calls count
    # If we have creation calls, we need at least one test to classify
    if num_tests <= 0:
        # Edge case: has creation calls but no test_ functions - still classify based on calls
        if create_calls == 1:
            return "lowres", [f"no test_ functions found; create_calls={create_calls}"]
        elif create_calls <= 2:  # midres threshold when no tests
            return "midres", [f"no test_ functions found; create_calls={create_calls}"]
        else:
            return "hires", [f"no test_ functions found; create_calls={create_calls}"]

    # Classify based on create_calls vs num_tests
    if create_calls == 1:
        return "lowres", [f"create_calls({create_calls}) == 1"]
    elif create_calls <= num_tests:
        return "midres", [f"1 < create_calls({create_calls}) <= num_tests({num_tests})"]
    else:
        return "hires", [f"create_calls({create_calls}) > num_tests({num_tests})"]


def iter_test_files(root: str) -> Iterable[str]:
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if fn.startswith("test_") and fn.endswith(".py"):
                yield os.path.join(dirpath, fn)


def analyze_file(path: str) -> Optional[FileTagging]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()
        tree = ast.parse(text, filename=path)
    except SyntaxError as e:
        return FileTagging(
            file=path,
            category="unknown",
            num_tests=0,
            create_calls=0,
            infra_hits=0,
            notes=[f"syntax error: {e}"],
        )
    except Exception as e:
        return FileTagging(
            file=path,
            category="unknown",
            num_tests=0,
            create_calls=0,
            infra_hits=0,
            notes=[f"read/parse error: {e}"],
        )

    # First pass: count test methods and determine if file is a test file
    a = Analyzer(text)

    # Scan for test methods first
    class TestMethodScanner(ast.NodeVisitor):
        def __init__(self):
            self.has_test_methods = False
            self.num_tests = 0

        def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
            if node.name.startswith("test_"):
                self.num_tests += 1
                self.has_test_methods = True
            self.generic_visit(node)

        def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
            if node.name.startswith("test_"):
                self.num_tests += 1
                self.has_test_methods = True
            self.generic_visit(node)

    scanner = TestMethodScanner()
    scanner.visit(tree)
    a.has_test_methods = scanner.has_test_methods
    a.num_tests = scanner.num_tests

    # Second pass: count creation/infra calls now that we know if it's a test file
    a.visit(tree)

    cat, notes = categorize(a.num_tests, a.create_calls, a.infra_hits)
    return FileTagging(
        file=path,
        category=cat,
        num_tests=a.num_tests,
        create_calls=a.create_calls,
        infra_hits=a.infra_hits,
        notes=notes,
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("root", help="Root directory containing Marvin tests")
    ap.add_argument("-o", "--out", default="test_tags.yaml", help="Output file (yaml or json)")
    ap.add_argument("--format", choices=["yaml", "json"], default=None, help="Force output format")
    args = ap.parse_args()

    out_format = args.format
    if out_format is None:
        out_format = "json" if args.out.lower().endswith(".json") else "yaml"

    results: List[FileTagging] = []
    for f in sorted(iter_test_files(args.root)):
        results.append(analyze_file(f))

    payload = {
        "root": os.path.abspath(args.root),
        "generated_by": "tag_marvin_tests.py",
        "categories": {
            "business": [],
            "lowres": [],
            "midres": [],
            "hires": [],
            "infra": [],
            "unknown": [],
        },
        "details": [asdict(r) for r in results],
    }

    for r in results:
        payload["categories"].setdefault(r.category, [])
        payload["categories"][r.category].append(r.file)

    if out_format == "yaml":
        if yaml is None:
            raise SystemExit("PyYAML not installed. Either install pyyaml or use --format json / .json output.")
        with open(args.out, "w", encoding="utf-8") as f:
            yaml.safe_dump(payload, f, sort_keys=False)
    else:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2)

    print(f"Wrote {args.out}")
    for k, v in payload["categories"].items():
        print(f"{k:8s}: {len(v)} files")


if __name__ == "__main__":
    main()