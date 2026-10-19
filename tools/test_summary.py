"""Prints a Markdown summary of the JUnit XML results for the GitHub job summary."""
import glob
import sys
import xml.etree.ElementTree as ET

PATTERN = "app/build/test-results/testDebugUnitTest/*.xml"

rows = []
total = failed = errors = skipped = 0
for path in sorted(glob.glob(PATTERN)):
    suite = ET.parse(path).getroot()
    tests = int(suite.get("tests", 0))
    f = int(suite.get("failures", 0))
    e = int(suite.get("errors", 0))
    s = int(suite.get("skipped", 0))
    total += tests
    failed += f
    errors += e
    skipped += s
    rows.append((suite.get("name", path).rsplit(".", 1)[-1], tests, f + e, s))

print("## Unit tests")
if not rows:
    print("\nNo test results found (the build may have failed before the tests ran).")
    sys.exit(0)

status = "all passed" if failed + errors == 0 else f"{failed + errors} failed"
print(f"\n**{total} tests, {status}**" + (f", {skipped} skipped" if skipped else ""))
print("\n| Suite | Tests | Failed | Skipped |")
print("|---|---|---|---|")
for name, tests, bad, skip in rows:
    print(f"| {name} | {tests} | {bad} | {skip} |")
