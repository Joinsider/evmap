#!/usr/bin/env python3
"""Converts Xcode code coverage into SonarQube's generic coverage format.

Usage: xccov-to-sonar.py > sonar-coverage-ios.xml

Reads the result bundle the CI test step writes (evMap_ios/EVMap/build/Test.xcresult) and reports
the app's own sources (evMap_ios/EVMap/EVMap), with paths relative to the repository root — the
analysis runs on a different machine than the tests, from that root.

SonarQube Cloud cannot read an .xcresult bundle; it reads line coverage in its generic XML format
(sonar.coverageReportPaths). SonarSource's sonar-scanning-examples ship a shell script for this,
but downloading it at CI time would run unreviewed code, so the conversion lives here. See ADR 0016.

The script deliberately takes no arguments. Its only job is one CI step whose paths are fixed, and
a command line built from caller input is an argument-injection surface (pythonsecurity:S8705) that
buys nothing here: the one argument handed to xccov is derived from this file's own location.
"""
import json
import os
import subprocess
import sys
from xml.sax.saxutils import quoteattr

REPO_ROOT = os.path.realpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
RESULT_BUNDLE = os.path.join(REPO_ROOT, "evMap_ios", "EVMap", "build", "Test.xcresult")
SOURCE_ROOT = os.path.join(REPO_ROOT, "evMap_ios", "EVMap", "EVMap") + os.sep


def line_coverage():
    """Every file's per-line coverage from the bundle, in one xccov call."""
    if not os.path.isdir(RESULT_BUNDLE):
        sys.exit(f"xccov-to-sonar: no result bundle at {RESULT_BUNDLE} — run the tests with "
                 "-enableCodeCoverage YES -resultBundlePath build/Test.xcresult first")
    output = subprocess.run(["xcrun", "xccov", "view", "--archive", "--json", RESULT_BUNDLE],
                            capture_output=True, text=True, check=True).stdout
    return json.loads(output)


def main():
    coverage = line_coverage()
    print('<coverage version="1">')
    reported = 0
    for path in sorted(coverage):
        # Only the app's sources: the bundle also covers test files and generated code, which are
        # not what the coverage gate is meant to measure.
        real = os.path.realpath(path)
        if not real.startswith(SOURCE_ROOT):
            continue
        executable = [entry for entry in coverage[path] if entry.get("isExecutable")]
        if not executable:
            continue
        print(f"  <file path={quoteattr(os.path.relpath(real, REPO_ROOT))}>")
        for entry in executable:
            covered = "true" if entry.get("executionCount", 0) > 0 else "false"
            print(f'    <lineToCover lineNumber="{entry["line"]}" covered="{covered}"/>')
        print("  </file>")
        reported += 1
    print("</coverage>")
    print(f"xccov-to-sonar: {reported} file(s) under {os.path.relpath(SOURCE_ROOT, REPO_ROOT)}", file=sys.stderr)
    if reported == 0:
        # An empty report would read as 0 % coverage rather than as a broken pipeline.
        sys.exit("xccov-to-sonar: no covered source files found — was -enableCodeCoverage YES set?")


if __name__ == "__main__":
    if len(sys.argv) != 1:
        sys.exit(__doc__)
    main()
