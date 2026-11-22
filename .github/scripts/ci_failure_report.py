#!/usr/bin/env python3
"""
CI failure reporter for Eve.

Restates failing tests at the END of a job log. `lintDebug` prints thousands of
warning lines after `testDebugUnitTest`, which pushes the real test failures out
of the readable log tail and leaves only "There were failing tests".

Diagnostics only. It reads JUnit XML that the build already produced. It never
re-runs, skips, filters or re-classifies a test, and it always exits 0 so it
cannot change a job's verdict (usage errors exit 2). Run it from a step guarded
by `if: failure()`.

Usage:
    ci\_failure\_report.py --unit app/build/test-results/testDebugUnitTest
                         \[--gradle-log ci-full-gradle.log\]
    ci\_failure\_report.py --native app/build/outputs/androidTest-results/connected ui-renderings

For every failing test it prints the root-cause exception (the last
"Caused by:"), the first causal app frame and a condensed stack trace. Tests
that share a root cause and frame are grouped. Annotations are printed last.
"""

import argparse
import glob
import os
import re
import sys
import traceback
import xml.etree.ElementTree as ET

APP\_PREFIXES = ("com.eve.", "org.telegram.", "me.vkryl.")
CAUSED\_BY = "Caused by: "
FRAME\_RE = re.compile(r"^\\s*at\\s+(\[^\\s(\]+)((\[^)\]*))")
CLASS\_NAME\_RE = re.compile(r"^\[A-Za-z\_$$][\w.$$\]*$")
STREAM\_RE = re.compile(r"<system-(out|err)>.*?</system-\\1>", re.S)
LOG\_MARKERS = (
    "FAILURES!!!",
    "INSTRUMENTATION\_FAILED",
    "INSTRUMENTATION\_RESULT: shortMsg",
    "Process crashed",
    "FATAL EXCEPTION",
    "Error in ",
    "Failure in ",
)

MAX\_LINE = 300
MAX\_COMPACT\_GROUPS = 30
MAX\_ROOT\_CAUSES = 10
MAX\_ANNOTATIONS = 10
MAX\_HEADER\_CHARS = 20000

def clip(text, limit=MAX\_LINE):
    text = text.rstrip()
    return text if len(text) &lt;= limit else text\[: limit - 3\] + "..."

def neutralize(line):
    """Echoed test output must never be parsed as a workflow command."""
    line = line.replace("##\[", "#\_#\[")
    stripped = line.lstrip()
    if stripped.startswith("::"):
        line = line\[: len(line) - len(stripped)\] + ": :" + stripped\[2:\]
    return line

def escape\_data(text):
    return text.replace("%", "%25").replace("\\r", "%0D").replace("\\n", "%0A")

def escape\_property(text):
    return escape\_data(text).replace(":", "%3A").replace(",", "%2C")

def annotation(level, title, body):
    return "::%s title=%s::%s" % (level, escape\_property(title), escape\_data(clip(body, 900)))

# --------------------------------------------------------------------------

# Stack trace analysis

# --------------------------------------------------------------------------

def parse\_frame(line):
    """Return (qualified method, source location) for a stack frame line."""
    match = FRAME\_RE.match(line)
    if not match:
        return None
    # Java 9+ prefixes frames with "loader//" or "module/".
    return match.group(1).rsplit("/", 1)\[-1\], match.group(2)

def is\_app\_frame(line):
    frame = parse\_frame(line)
    return bool(frame) and frame\[0\].startswith(APP\_PREFIXES)

class SourceIndex:
    """Tells test code from production code.

```
A frame is test code when its source file lives in a test source set, or
when its class is one of the test classes named by the reports. Names are
not guessed from "Test" or "Shadow": Eve has production classes such as
TestActivity and ShadowSectionCell.
"""

def __init__(self, source_root=None, test_classes=()):
    self.source_root = source_root if source_root and os.path.isdir(source_root) else None
    self.test_classes = set(test_classes)
    self._cache = {}

def is_test_frame(self, line):
    frame = parse_frame(line)
    if not frame:
        return False
    outer = frame[0].rsplit(".", 1)[0].split("$", 1)[0]
    key = (outer, frame[1].split(":", 1)[0])
    if key not in self._cache:
        self._cache[key] = self._lookup(*key)
    return self._cache[key]

def _lookup(self, outer, file_name):
    if outer in self.test_classes:
        return True
    package_dir = outer.rsplit(".", 1)[0].replace(".", os.sep) if "." in outer else ""
    if self.source_root and file_name.endswith((".kt", ".java")):
        for source_set in sorted(os.listdir(self.source_root)):
            for language in ("java", "kotlin"):
                path = os.path.join(self.source_root, source_set, language, package_dir, file_name)
                if os.path.isfile(path):
                    return source_set.startswith(("test", "androidTest"))
    return outer.endswith(("Fixture", "Fixtures"))
```

def split\_exception(line):
    """Split 'pkg.SomeException: message' into (class, message)."""
    line = line.strip()
    if line.startswith(CAUSED\_BY):
        line = line\[len(CAUSED\_BY):\]
    head, sep, tail = line.partition(": ")
    if CLASS\_NAME\_RE.match(head) and "." in head:
        return head, tail if sep else ""
    if CLASS\_NAME\_RE.match(line.rstrip(":")) and "." in line:
        return line.rstrip(":"), ""
    return "", line

def trace\_blocks(lines):
    """Split a trace into the outer exception and its 'Caused by:' blocks."""
    starts = \[0\] + \[i for i, line in enumerate(lines) if i and line.startswith(CAUSED\_BY)\]
    return \[lines\[start:end\] for start, end in zip(starts, starts\[1:\] + \[len(lines)\])\]

def analyze\_trace(text, index=None):
    """Root-cause exception and first causal app frame of a Java stack trace."""
    index = index or SourceIndex()
    lines = \[line.rstrip() for line in (text or "").splitlines() if line.strip()\]
    info = {
        "exception": "", "message": "", "root\_exception": "", "root\_message": "",
        "app\_frame": "", "test\_frame": "", "top\_frame": "",
    }
    if not lines:
        return info
    blocks = trace\_blocks(lines)
    info\["exception"\], info\["message"\] = split\_exception(blocks\[0\]\[0\])
    info\["root\_exception"\], info\["root\_message"\] = split\_exception(blocks\[-1\]\[0\])
    # Walk from the root cause outwards: the root block is often abbreviated
    # with "... N more", so the nearest enclosing block supplies the frame.
    for block in reversed(blocks):
        frames = \[line for line in block\[1:\] if parse\_frame(line)\]
        if not info\["top\_frame"\] and frames:
            info\["top\_frame"\] = frames\[0\].strip()
        for line in frames:
            if not is\_app\_frame(line):
                continue
            if index.is\_test\_frame(line):
                if not info\["test\_frame"\]:
                    info\["test\_frame"\] = line.strip()
            elif not info\["app\_frame"\]:
                info\["app\_frame"\] = line.strip()
        if info\["app\_frame"\]:
            break
    return info

def condense\_trace(text, max\_lines, index=None):
    """Keep the head of the trace, every 'Caused by:' and the first app frames."""
    index = index or SourceIndex()
    lines = \[line.rstrip() for line in (text or "").splitlines() if line.strip()\]
    if len(lines) &lt;= max\_lines:
        return \[clip(line) for line in lines\]
    blocks = trace\_blocks(lines)
    if len(blocks) == 1:
        budgets = \[max\_lines\]
    else:
        budgets = \[2\] \* len(blocks)
        budgets\[0\] = max(3, max\_lines // 3)
        budgets\[-1\] = max(4, max\_lines // 2)
    out = \[\]
    for block, budget in zip(blocks, budgets):
        keep = list(range(min(budget, len(block))))
        for position, line in enumerate(block):
            if is\_app\_frame(line) and not index.is\_test\_frame(line):
                if position not in keep:
                    keep.append(position)
                break
        previous = -1
        for position in keep:
            if position != previous + 1:
                out.append("\\t... %d line(s) omitted" % (position - previous - 1))
            out.append(clip(block\[position\]))
            previous = position
        if previous + 1 &lt; len(block):
            out.append("\\t... %d line(s) omitted" % (len(block) - previous - 1))
    return out

# --------------------------------------------------------------------------

# JUnit XML

# --------------------------------------------------------------------------

def find\_xml\_files(directories):
    files = \[\]
    for directory in directories:
        if os.path.isdir(directory):
            files.extend(glob.glob(os.path.join(directory, "\*\*", "\*.xml"), recursive=True))
    return sorted(set(files))

def parse\_xml(path):
    """Parse one report. Falls back to dropping the captured output streams."""
    try:
        return ET.parse(path).getroot(), None
    except ET.ParseError as first\_error:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            return ET.fromstring(STREAM\_RE.sub("", text)), None
        except (ET.ParseError, OSError):
            return None, str(first\_error)
    except OSError as error:
        return None, str(error)

def collect\_results(directories, source\_root=None):
    """Read every JUnit XML report below the given directories."""
    result = {
        "directories": list(directories),
        "missing": \[d for d in directories if not os.path.isdir(d)\],
        "files": 0, "unparseable": \[\], "tests": 0, "skipped": 0, "failures": \[\],
        "index": SourceIndex(source\_root),
    }
    for path in find\_xml\_files(directories):
        root, error = parse\_xml(path)
        if root is None:
            result\["unparseable"\].append((path, error))
            continue
        if root.tag not in ("testsuite", "testsuites"):
            continue
        result\["files"\] += 1
        for case in root.iter("testcase"):
            result\["tests"\] += 1
            if case.get("classname"):
                result\["index"\].test\_classes.add(case.get("classname"))
            if case.find("skipped") is not None:
                result\["skipped"\] += 1
                continue
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                trace = node.text or ""
                if not trace.strip():
                    trace = ": ".join(p for p in (node.get("type"), node.get("message")) if p)
                result\["failures"\].append({
                    "class": case.get("classname") or "",
                    "name": case.get("name") or "",
                    "kind": kind,
                    "type": node.get("type") or "",
                    "trace": trace,
                })
                break
    # Analysed after reading everything, so every test class is known.
    for failure in result\["failures"\]:
        failure.update(analyze\_trace(failure\["trace"\], result\["index"\]))
        if not failure\["root\_exception"\]:
            failure\["root\_exception"\] = failure\["type"\]
    return result

def group\_failures(failures):
    """Group failing tests that share a root cause and a causal frame."""
    groups = {}
    for failure in failures:
        frame = failure\["app\_frame"\] or failure\["test\_frame"\] or failure\["top\_frame"\]
        key = (failure\["root\_exception"\], frame or clip(failure\["root\_message"\], 120))
        groups.setdefault(key, \[\]).append(failure)
    ordered = sorted(groups.items(), key=lambda item: (-len(item\[1\]), item\[0\]))
    return \[members for \_, members in ordered\]

def root\_causes(failures):
    """Count failing tests per root-cause exception and message."""
    counts = {}
    for failure in failures:
        key = describe(failure, 160)
        counts\[key\] = counts.get(key, 0) + 1
    return sorted(counts.items(), key=lambda item: (-item\[1\], item\[0\]))

# --------------------------------------------------------------------------

# Plain-text logs

# --------------------------------------------------------------------------

def gradle\_excerpt(path, max\_lines=30):
    """'\* What went wrong:' blocks, failed tasks and compiler errors."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            lines = \[line.rstrip("\\n") for line in handle\]
    except OSError:
        return None
    out, seen, capturing = \[\], set(), False
    for line in lines:
        text = line.rstrip()
        if text.startswith("\* What went wrong:"):
            capturing = True
        elif capturing and text.startswith("\* "):
            capturing = False
        keep = capturing and text
        keep = keep or text.startswith("e: ") or ": error:" in text
        keep = keep or (text.startswith("&gt; Task ") and text.endswith(" FAILED"))
        if keep and text not in seen:
            seen.add(text)
            out.append(clip(text))
    if len(out) &gt; max\_lines:
        out = out\[:max\_lines\] + \["... %d more line(s) omitted" % (len(out) - max\_lines)\]
    return out

def log\_excerpts(directories, context=12, max\_hits=3):
    """Failure markers found in instrumentation logs, with a little context."""
    out = \[\]
    for directory in directories:
        if not os.path.isdir(directory):
            out.append("log directory not found: %s" % directory)
            continue
        for path in sorted(glob.glob(os.path.join(directory, "\*.log"))):
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as handle:
                    lines = \[line.rstrip("\\n") for line in handle\]
            except OSError as error:
                out.append("%s: unreadable (%s)" % (path, error))
                continue
            hits, position = 0, 0
            while position &lt; len(lines) and hits &lt; max\_hits:
                if any(marker in lines\[position\] for marker in LOG\_MARKERS):
                    hits += 1
                    out.append("%s:%d" % (path, position + 1))
                    out.extend("    " + clip(line) for line in lines\[position:position + context\])
                    position += context
                else:
                    position += 1
    return out

# --------------------------------------------------------------------------

# Report

# --------------------------------------------------------------------------

def test\_id(failure):
    return "%s.%s" % (failure\["class"\], failure\["name"\])

def describe(failure, limit=220):
    exception = failure\["root\_exception"\] or failure\["exception"\] or "(no exception class)"
    message = failure\["root\_message"\] or failure\["message"\]
    return clip("%s: %s" % (exception, message) if message else exception, limit)

def causal\_frame(failure):
    if failure\["app\_frame"\]:
        return failure\["app\_frame"\]
    if failure\["test\_frame"\]:
        return "%s (test code; no production app frame in the trace)" % failure\["test\_frame"\]
    return failure\["top\_frame"\] or "(no stack frame)"

def build\_report(mode, result, groups, options, gradle\_lines=None, log\_lines=None):
    """Return (header, detail, compact, annotations) as lists of lines."""
    failing = len(result\["failures"\])
    index = result\["index"\]
    context = " ".join(
        "%s=%s" % (label, os.environ\[name\])
        for label, name in (("commit", "GITHUB\_SHA"), ("run", "GITHUB\_RUN\_ID"), ("job", "GITHUB\_JOB"))
        if os.environ.get(name)
    )
    header = \["=== EVE CI failure report (%s) ===" % mode\]
    if context:
        header.append(context)
    header.append("scanned: %s" % ", ".join(result\["directories"\]))
    for directory in result\["missing"\]:
        header.append("directory not found: %s" % directory)
    totals = "reports=%d tests=%d failing=%d skipped=%d groups=%d" % (
        result\["files"\], result\["tests"\], failing, result\["skipped"\], len(groups))
    header.append(totals)
    for path, error in result\["unparseable"\]:
        header.append("unparseable report: %s (%s)" % (path, error))
    finding = ""
    if result\["files"\] == 0:
        finding = ("No JUnit XML report was found: the tests did not run or wrote no results. "
                   "The failure is earlier than the test task.")
    elif failing == 0:
        finding = "The reports contain no failing test: the failure comes from another task or step."
    if finding:
        header.append(finding)
    if gradle\_lines is not None:
        header.append("--- Gradle failure excerpt ---")
        header.extend(gradle\_lines or \["(no '\* What went wrong:' block or compiler error found)"\])
    if log\_lines:
        header.append("--- instrumentation log excerpts ---")
        header.extend(log\_lines)

```
detail = []
for number, members in enumerate(groups[: options.max_groups], 1):
    sample = members[0]
    detail.append("")
    detail.append("[G%d] %d test(s): %s" % (number, len(members), describe(sample)))
    detail.append("  first causal app frame: %s" % causal_frame(sample))
    if sample["app_frame"] and sample["test_frame"]:
        detail.append("  test frame: %s" % sample["test_frame"])
    for failure in members[: options.max_tests]:
        detail.append("  - %s" % test_id(failure))
    if len(members) > options.max_tests:
        detail.append("  - ... %d more test(s)" % (len(members) - options.max_tests))
    detail.append("  trace of %s:" % test_id(sample))
    detail.extend("    " + line for line in condense_trace(sample["trace"], options.max_stack_lines, index))
if len(groups) > options.max_groups:
    detail.append("")
    detail.append("... %d more group(s) have no trace here; see the compact summary."
                  % (len(groups) - options.max_groups))

compact, causes = [], root_causes(result["failures"])
if groups:
    compact.append("--- root causes: %d failing test(s) ---" % failing)
    compact.extend("x%d %s" % (count, cause) for cause, count in causes[:MAX_ROOT_CAUSES])
    if len(causes) > MAX_ROOT_CAUSES:
        compact.append("... %d more root cause(s)" % (len(causes) - MAX_ROOT_CAUSES))
    compact.append("--- compact summary: %d group(s) by root cause and causal frame ---" % len(groups))
    for number, members in enumerate(groups[:MAX_COMPACT_GROUPS], 1):
        compact.append(clip("G%d x%d | %s | %s | e.g. %s" % (
            number, len(members), describe(members[0]), causal_frame(members[0]),
            test_id(members[0])), 400))
    if len(groups) > MAX_COMPACT_GROUPS:
        compact.append("... %d more group(s); see the archived test reports."
                       % (len(groups) - MAX_COMPACT_GROUPS))

annotations = []
if groups:
    overview = "\n".join("x%d %s" % (count, cause) for cause, count in causes[:5])
    annotations.append(annotation(
        "error", "%s tests: %d failing in %d group(s)" % (mode, failing, len(groups)), overview))
    room = MAX_ANNOTATIONS - 1
    shown = groups[:room] if len(groups) <= room else groups[: room - 1]
    for number, members in enumerate(shown, 1):
        body = "%s\nfirst causal app frame: %s\ne.g. %s" % (
            describe(members[0]), causal_frame(members[0]), test_id(members[0]))
        annotations.append(annotation(
            "error", "%s G%d/%d (%d test(s))" % (mode, number, len(groups), len(members)), body))
    if len(groups) > len(shown):
        annotations.append(annotation(
            "error", "%s tests: more groups" % mode,
            "%d more group(s); see the compact summary above." % (len(groups) - len(shown))))
else:
    evidence = [line for line in (gradle_lines or []) if not line.startswith("(")]
    level = "error" if result["files"] == 0 or evidence else "notice"
    annotations.append(annotation(
        level, "%s tests: no failing test in the reports" % mode,
        "\n".join([finding, totals] + evidence[:8])))
return header, detail, compact, annotations
```

def limit\_lines(lines, max\_chars, what="detail"):
    """Cut a section to a character budget without dropping later sections."""
    out, used = \[\], 0
    for line in lines:
        used += len(line) + 1
        if used &gt; max\_chars:
            out.append("... %s truncated at %d characters; see the compact summary." % (what, max\_chars))
            break
        out.append(line)
    return out

def write\_summary(path, mode, body\_lines):
    if not path:
        return
    text = "\\n".join(body\_lines).replace("`", "'''")     try:         with open(path, "a", encoding="utf-8") as handle:             handle.write("### CI failure report (%s)\n\n`text\\n%s\\n\`\`\`\\n" % (mode, text\[:60000\]))
    except OSError as error:
        print("could not write step summary: %s" % error)

def main(argv=None):
    parser = argparse.ArgumentParser(description="Restate failing tests at the end of a CI job log.")
    parser.add\_argument("--unit", nargs="+", metavar="DIR", help="JUnit XML directories of JVM unit tests")
    parser.add\_argument("--native", nargs="+", metavar="DIR",
                        help="instrumentation results directory, then optional log directories")
    parser.add\_argument("--gradle-log", metavar="FILE", help="Gradle console log to excerpt")
    parser.add\_argument("--source-root", default=os.path.join("app", "src"),
                        help="source sets used to tell test code from production code")
    parser.add\_argument("--summary-file", default=os.environ.get("GITHUB\_STEP\_SUMMARY"))
    parser.add\_argument("--max-groups", type=int, default=10)
    parser.add\_argument("--max-tests", type=int, default=12)
    parser.add\_argument("--max-stack-lines", type=int, default=30)
    parser.add\_argument("--max-chars", type=int, default=40000)
    parser.add\_argument("--no-annotations", action="store\_true")
    options = parser.parse\_args(argv)
    if not options.unit and not options.native:
        parser.error("one of --unit or --native is required")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

```
sections = []
if options.unit:
    sections.append(("unit", options.unit, []))
if options.native:
    sections.append(("native", options.native[:1], options.native[1:]))

annotations = []
for mode, directories, log_directories in sections:
    result = collect_results(directories, options.source_root)
    groups = group_failures(result["failures"])
    gradle_lines = None
    if mode == "unit" and options.gradle_log:
        gradle_lines = gradle_excerpt(options.gradle_log)
        if gradle_lines is None:
            gradle_lines = ["(Gradle log not readable: %s)" % options.gradle_log]
    log_lines = log_excerpts(log_directories) if log_directories else None
    header, detail, compact, section_annotations = build_report(
        mode, result, groups, options, gradle_lines, log_lines)
    body = (limit_lines(header, MAX_HEADER_CHARS, "header")
            + limit_lines(detail, options.max_chars) + [""] + compact)
    body = [neutralize(clip(line, 600)) for line in body]
    print("\n".join(body))
    write_summary(options.summary_file, mode, body)
    annotations.extend(section_annotations)

if not options.no_annotations:
    # Printed last, so the annotations and the lines above them end the log.
    print("\n".join(annotations[:MAX_ANNOTATIONS]))
sys.stdout.flush()
return 0
```

def run(argv=None):
    """Run main(). A bug in this reporter is reported, never turned into an exit status."""
    try:
        return main(argv)
    except Exception:  # pylint: disable=broad-except
        print("ci\_failure\_report.py crashed; read the archived test reports or the Gradle log instead:")
        print("\\n".join(neutralize(line) for line in traceback.format\_exc().splitlines()))
        print(annotation("error", "CI failure reporter crashed",
                         "The failing tests were not summarised. See the traceback in the job log."))
        return 0

if **name** == "**main**":
    sys.exit(run())