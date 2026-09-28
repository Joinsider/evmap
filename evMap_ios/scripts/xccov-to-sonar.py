#!/usr/bin/env python3
"""Converts Xcode code coverage into SonarQube's generic coverage format.

Usage: xccov-to-sonar.py <Test.xcresult> <source root> > coverage.xml

SonarQube Cloud cannot read an .xcresult bundle; it reads line coverage in its generic XML format
(sonar.coverageReportPaths). SonarSource's sonar-scanning-examples ship a shell script for this,
but downloading it at CI time would run unreviewed code with the SONAR_TOKEN in scope, so the
conversion lives here instead. See ADR 0016.

Only files under <source root> are reported: the result bundle also covers test files and
generated code, which are not what the coverage gate is meant to measure.
"""
import json
import os
import subprocess
import sys
from xml.sax.saxutils import quoteattr


def xccov(*args):
    return subprocess.run(["xcrun", "xccov", "view", "--archive", *args],
                          capture_output=True, text=True, check=True).stdout


def main(result_bundle, source_root):
    root = os.path.realpath(source_root) + os.sep
    files = [line.strip() for line in xccov("--file-list", result_bundle).splitlines() if line.strip()]
    print('<coverage version="1">')
    reported = 0
    for path in sorted(files):
        if not os.path.realpath(path).startswith(root):
            continue
        lines = json.loads(xccov("--file", path, "--json", result_bundle)).get(path, [])
        executable = [entry for entry in lines if entry.get("isExecutable")]
        if not executable:
            continue
        print(f"  <file path={quoteattr(path)}>")
        for entry in executable:
            covered = "true" if entry.get("executionCount", 0) > 0 else "false"
            print(f'    <lineToCover lineNumber="{entry["line"]}" covered="{covered}"/>')
        print("  </file>")
        reported += 1
    print("</coverage>")
    print(f"xccov-to-sonar: {reported} file(s) under {source_root}", file=sys.stderr)
    if reported == 0:
        # An empty report would read as 0 % coverage rather than as a broken pipeline.
        sys.exit("xccov-to-sonar: no covered source files found — was -enableCodeCoverage YES set?")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
