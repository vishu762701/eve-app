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

Modules: ci_failure_trace.py (stack trace analysis) and ci_failure_inputs.py
(JUnit XML and logs) sit next to this file.
"""

import argparse
import os
import sys
import traceback

from ci_failure_inputs import collect_results, gradle_excerpt, log_excerpts
from ci_failure_trace import clip, condense_trace

FENCE = chr(96) * 3
MAX_COMPACT_GROUPS = 30
MAX_ROOT_CAUSES = 10
MAX_ANNOTATIONS = 10
MAX_HEADER_CHARS = 20000


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
            handle.write("### CI failure report (%s)\n\n%stext\n%s\n%s\n" % (mode, FENCE, text[:60000], FENCE))
    except OSError as error:
        print("could not write step summary: %s" % error)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Restate failing tests at the end of a CI job log.")
    parser.add_argument("--unit", nargs="+", metavar="DIR", help="JUnit XML directories of JVM unit tests")
    parser.add_argument("--native", nargs="+", metavar="DIR",
                        help="instrumentation results directory, then optional log directories")
    parser.add_argument("--gradle-log", metavar="FILE", help="Gradle console log to excerpt")
    parser.add_argument("--source-root", default=os.path.join("app", "src"),
                        help="source sets used to tell test code from production code")
    parser.add_argument("--summary-file", default=os.environ.get("GITHUB_STEP_SUMMARY"))
    parser.add_argument("--max-groups", type=int, default=10)
    parser.add_argument("--max-tests", type=int, default=12)
    parser.add_argument("--max-stack-lines", type=int, default=30)
    parser.add_argument("--max-chars", type=int, default=40000)
    parser.add_argument("--no-annotations", action="store_true")
    options = parser.parse_args(argv)
    if not options.unit and not options.native:
        parser.error("one of --unit or --native is required")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")

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


def run(argv=None):
    """Run main(). A bug in this reporter is reported, never turned into an exit status."""
    try:
        return main(argv)
    except Exception:  # pylint: disable=broad-except
        print("ci_failure_report.py crashed; read the archived test reports or the Gradle log instead:")
        print("\n".join(neutralize(line) for line in traceback.format_exc().splitlines()))
        print(annotation("error", "CI failure reporter crashed",
                         "The failing tests were not summarised. See the traceback in the job log."))
        return 0


if __name__ == "__main__":
    sys.exit(run())
"""
```

"""