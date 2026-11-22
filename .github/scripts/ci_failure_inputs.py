"""

```python
"""
# Storage note: the first and last string literals of this file are inert; see the
# note at the top of ci_failure_report.py.
__doc__ = """
Inputs of the Eve CI failure reporter (ci_failure_report.py): JUnit XML reports,
the Gradle console log and instrumentation logs. Everything here is read-only.
"""

import glob
import os
import re
import xml.etree.ElementTree as ET

from ci_failure_trace import SourceIndex, analyze_trace, clip

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
"""
```

"""