"""

```python
"""
# Storage note. The string literals on the first and last lines of this file are
# inert. The file is committed through an API that rewrites text as Markdown and
# stores only fenced code unchanged, so the two fence lines sit inside those
# literals. A normal git commit may delete both literals.
__doc__ = """
CI failure reporter for Eve.

Restates failing tests at the END of a job log. `lintDebug` prints thousands of
warning lines after `testDebugUnitTest`, which pushes the real test failures out
of the readable log tail and leaves only "There were failing tests".

Diagnostics only. It reads JUnit XML that the build already produced. It never
re-runs, skips, filters or re-classifies a test, and it always exits 0 so it
cannot change a job's verdict (usage errors exit 2). Run it from a step guarded
by `if: failure()`.

Usage:
    ci_failure_report.py --unit app/build/test-results/testDebugUnitTest
                         [--gradle-log ci-full-gradle.log]
    ci_failure_report.py --native app/build/outputs/androidTest-results/connected ui-renderings

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

APP_PREFIXES = ("com.eve.", "org.telegram.", "me.vkryl.")
CAUSED_BY = "Caused by: "
DOLLAR = chr(36)
FENCE = chr(96) * 3
FRAME_RE = re.compile(r"^\s*at\s+([^\s(]+)[(]([^)]*)[)]")
CLASS_NAME_RE = re.compile(r"^[A-Za-z_\x24][\w.\x24]*\Z")
STREAM_RE = re.compile(r"<system-(out|err)>.*?</system-\1>", re.S)
LOG_MARKERS = (
    "FAILURES!!!",
    "INSTRUMENTATION_FAILED",
    "INSTRUMENTATION_RESULT: shortMsg",
    "Process crashed",
    "FATAL EXCEPTION",
    "Error in ",
    "Failure in ",
)

MAX_LINE = 300
MAX_COMPACT_GROUPS = 30
MAX_ROOT_CAUSES = 10
MAX_ANNOTATIONS = 10
MAX_HEADER_CHARS = 20000


def clip(text, limit=MAX_LINE):
    text = text.rstrip()
    return text if len(text) <= limit else text[: limit - 3] + "..."


def neutralize(line):
    """Echoed test output must never be parsed as a workflow command."""
    line = line.replace("##[", "#_#[")
    stripped = line.lstrip()
    if stripped.startswith("::"):
        line = line[: len(line) - len(stripped)] + ": :" + stripped[2:]
    return line


def escape_data(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def annotation(level, title, body):
    return "::%s title=%s::%s" % (level, escape_property(title), escape_data(clip(body, 900)))


# --------------------------------------------------------------------------
# Stack trace analysis
# --------------------------------------------------------------------------

def parse_frame(line):
    """Return (qualified method, source location) for a stack frame line."""
    match = FRAME_RE.match(line)
    if not match:
        return None
    # Java 9+ prefixes frames with "loader//" or "module/".
    return match.group(1).rsplit("/", 1)[-1], match.group(2)


def is_app_frame(line):
    frame = parse_frame(line)
    return bool(frame) and frame[0].startswith(APP_PREFIXES)


class SourceIndex:
    """Tells test code from production code.

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
        outer = frame[0].rsplit(".", 1)[0].split(DOLLAR, 1)[0]
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


def split_exception(line):
    """Split 'pkg.SomeException: message' into (class, message)."""
    line = line.strip()
    if line.startswith(CAUSED_BY):
        line = line[len(CAUSED_BY):]
    head, sep, tail = line.partition(": ")
    if CLASS_NAME_RE.match(head) and "." in head:
        return head, tail if sep else ""
    if CLASS_NAME_RE.match(line.rstrip(":")) and "." in line:
        return line.rstrip(":"), ""
    return "", line


def trace_blocks(lines):
    """Split a trace into the outer exception and its 'Caused by:' blocks."""
    starts = [0] + [i for i, line in enumerate(lines) if i and line.startswith(CAUSED_BY)]
    return [lines[start:end] for start, end in zip(starts, starts[1:] + [len(lines)])]


def analyze_trace(text, index=None):
    """Root-cause exception and first causal app frame of a Java stack trace."""
    index = index or SourceIndex()
    lines = [line.rstrip() for line in (text or "").splitlines() if line.strip()]
    info = {
        "exception": "", "message": "", "root_exception": "", "root_message": "",
        "app_frame": "", "test_frame": "", "top_frame": "",
    }
    if not lines:
        return info
    blocks = trace_blocks(lines)
    info["exception"], info["message"] = split_exception(blocks[0][0])
    info["root_exception"], info["root_message"] = split_exception(blocks[-1][0])
    # Walk from the root cause outwards: the root block is often abbreviated
    # with "... N more", so the nearest enclosing block supplies the frame.
    for block in reversed(blocks):
        frames = [line for line in block[1:] if parse_frame(line)]
        if not info["top_frame"] and frames:
            info["top_frame"] = frames[0].strip()
        for line in frames:
            if not is_app_frame(line):
                continue
            if index.is_test_frame(line):
                if not info["test_frame"]:
                    info["test_frame"] = line.strip()
            elif not info["app_frame"]:
                info["app_frame"] = line.strip()
        if info["app_frame"]:
            break
    return info


def condense_trace(text, max_lines, index=None):
    """Keep the head of the trace, every 'Caused by:' and the first app frames."""
    index = index or SourceIndex()
    lines = [line.rstrip() for line in (text or "").splitlines() if line.strip()]
    if len(lines) <= max_lines:
        return [clip(line) for line in lines]
    blocks = trace_blocks(lines)
    if len(blocks) == 1:
        budgets = [max_lines]
    else:
        budgets = [2] * len(blocks)
        budgets[0] = max(3, max_lines // 3)
        budgets[-1] = max(4, max_lines // 2)
    out = []
    for block, budget in zip(blocks, budgets):
        keep = list(range(min(budget, len(block))))
        for position, line in enumerate(block):
            if is_app_frame(line) and not index.is_test_frame(line):
                if position not in keep:
                    keep.append(position)
                break
        previous = -1
        for position in keep:
            if position != previous + 1:
                out.append("\t... %d line(s) omitted" % (position - previous - 1))
            out.append(clip(block[position]))
            previous = position
        if previous + 1 < len(block):
            out.append("\t... %d line(s) omitted" % (len(block) - previous - 1))
    return out


# --------------------------------------------------------------------------
# JUnit XML
# --------------------------------------------------------------------------

def find_xml_files(directories):
    files = []
    for directory in directories:
        if os.path.isdir(directory):
            files.extend(glob.glob(os.path.join(directory, "**", "*.xml"), recursive=True))
    return sorted(set(files))


def parse_xml(path):
    """Parse one report. Falls back to dropping the captured output streams."""
    try:
        return ET.parse(path).getroot(), None
    except ET.ParseError as first_error:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                text = handle.read()
            return ET.fromstring(STREAM_RE.sub("", text)), None
        except (ET.ParseError, OSError):
            return None, str(first_error)
    except OSError as error:
        return None, str(error)


def collect_results(directories, source_root=None):
    """Read every JUnit XML report below the given directories."""
    result = {
        "directories": list(directories),
        "missing": [d for d in directories if not os.path.isdir(d)],
        "files": 0, "unparseable": [], "tests": 0, "skipped": 0, "failures": [],
        "index": SourceIndex(source_root),
    }
    for path in find_xml_files(directories):
        root, error = parse_xml(path)
        if root is None:
            result["unparseable"].append((path, error))
            continue
        if root.tag not in ("testsuite", "testsuites"):
            continue
        result["files"] += 1
        for case in root.iter("testcase"):
            result["tests"] += 1
            if case.get("classname"):
                result["index"].test_classes.add(case.get("classname"))
            if case.find("skipped") is not None:
                result["skipped"] += 1
                continue
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                trace = node.text or ""
                if not trace.strip():
                    trace = ": ".join(p for p in (node.get("type"), node.get("message")) if p)
                result["failures"].append({
                    "class": case.get("classname") or "",
                    "name": case.get("name") or "",
                    "kind": kind,
                    "type": node.get("type") or "",
                    "trace": trace,
                })
                break
    # Analysed after reading everything, so every test class is known.
    for failure in result["failures"]:
        failure.update(analyze_trace(failure["trace"], result["index"]))
        if not failure["root_exception"]:
            failure["root_exception"] = failure["type"]
    return result


def group_failures(failures):
    """Group failing tests that share a root cause and a causal frame."""
    groups = {}
    for failure in failures:
        frame = failure["app_frame"] or failure["test_frame"] or failure["top_frame"]
        key = (failure["root_exception"], frame or clip(failure["root_message"], 120))
        groups.setdefault(key, []).append(failure)
    ordered = sorted(groups.items(), key=lambda item: (-len(item[1]), item[0]))
    return [members for _, members in ordered]


def root_causes(failures):
    """Count failing tests per root-cause exception and message."""
    counts = {}
    for failure in failures:
        key = describe(failure, 160)
        counts[key] = counts.get(key, 0) + 1
    return sorted(counts.items(), key=lambda item: (-item[1], item[0]))


# --------------------------------------------------------------------------
# Plain-text logs
# --------------------------------------------------------------------------

def gradle_excerpt(path, max_lines=30):
    """'* What went wrong:' blocks, failed tasks and compiler errors."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            lines = [line.rstrip("\n") for line in handle]
    except OSError:
        return None
    out, seen, capturing = [], set(), False
    for line in lines:
        text = line.rstrip()
        if text.startswith("* What went wrong:"):
            capturing = True
        elif capturing and text.startswith("* "):
            capturing = False
        keep = capturing and text
        keep = keep or text.startswith("e: ") or ": error:" in text
        keep = keep or (text.startswith("> Task ") and text.endswith(" FAILED"))
        if keep and text not in seen:
            seen.add(text)
            out.append(clip(text))
    if len(out) > max_lines:
        out = out[:max_lines] + ["... %d more line(s) omitted" % (len(out) - max_lines)]
    return out


def log_excerpts(directories, context=12, max_hits=3):
    """Failure markers found in instrumentation logs, with a little context."""
    out = []
    for directory in directories:
        if not os.path.isdir(directory):
            out.append("log directory not found: %s" % directory)
            continue
        for path in sorted(glob.glob(os.path.join(directory, "*.log"))):
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as handle:
                    lines = [line.rstrip("\n") for line in handle]
            except OSError as error:
                out.append("%s: unreadable (%s)" % (path, error))
                continue
            hits, position = 0, 0
            while position < len(lines) and hits < max_hits:
                if any(marker in lines[position] for marker in LOG_MARKERS):
                    hits += 1
                    out.append("%s:%d" % (path, position + 1))
                    out.extend("    " + clip(line) for line in lines[position:position + context])
                    position += context
                else:
                    position += 1
    return out


# --------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------

def test_id(failure):
    return "%s.%s" % (failure["class"], failure["name"])


def describe(failure, limit=220):
    exception = failure["root_exception"] or failure["exception"] or "(no exception class)"
    message = failure["root_message"] or failure["message"]
    return clip("%s: %s" % (exception, message) if message else exception, limit)


def causal_frame(failure):
    if failure["app_frame"]:
        return failure["app_frame"]
    if failure["test_frame"]:
        return "%s (test code; no production app frame in the trace)" % failure["test_frame"]
    return failure["top_frame"] or "(no stack frame)"


def build_report(mode, result, groups, options, gradle_lines=None, log_lines=None):
    """Return (header, detail, compact, annotations) as lists of lines."""
    failing = len(result["failures"])
    index = result["index"]
    context = " ".join(
        "%s=%s" % (label, os.environ[name])
        for label, name in (("commit", "GITHUB_SHA"), ("run", "GITHUB_RUN_ID"), ("job", "GITHUB_JOB"))
        if os.environ.get(name)
    )
    header = ["=== EVE CI failure report (%s) ===" % mode]
    if context:
        header.append(context)
    header.append("scanned: %s" % ", ".join(result["directories"]))
    for directory in result["missing"]:
        header.append("directory not found: %s" % directory)
    totals = "reports=%d tests=%d failing=%d skipped=%d groups=%d" % (
        result["files"], result["tests"], failing, result["skipped"], len(groups))
    header.append(totals)
    for path, error in result["unparseable"]:
        header.append("unparseable report: %s (%s)" % (path, error))
    finding = ""
    if result["files"] == 0:
        finding = ("No JUnit XML report was found: the tests did not run or wrote no results. "
                   "The failure is earlier than the test task.")
    elif failing == 0:
        finding = "The reports contain no failing test: the failure comes from another task or step."
    if finding:
        header.append(finding)
    if gradle_lines is not None:
        header.append("--- Gradle failure excerpt ---")
        header.extend(gradle_lines or ["(no '* What went wrong:' block or compiler error found)"])
    if log_lines:
        header.append("--- instrumentation log excerpts ---")
        header.extend(log_lines)

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


def limit_lines(lines, max_chars, what="detail"):
    """Cut a section to a character budget without dropping later sections."""
    out, used = [], 0
    for line in lines:
        used += len(line) + 1
        if used > max_chars:
            out.append("... %s truncated at %d characters; see the compact summary." % (what, max_chars))
            break
        out.append(line)
    return out


def write_summary(path, mode, body_lines):
    if not path:
        return
    text = "\n".join(body_lines).replace(FENCE, "'''")
    try:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write("### CI failure report (%s)\n\n%stext\n%s\n%s\n" % (mode, FENCE, text[:60000],