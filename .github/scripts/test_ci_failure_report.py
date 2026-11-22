"""

```python
"""
# Storage note: the first and last string literals of this file are inert; see the
# note at the top of ci_failure_report.py.
__doc__ = """
Unit tests for ci_failure_report.py and ci_failure_inputs.py.
Uses temporary JUnit XML, Gradle log and summary fixtures; no network access.
"""

import contextlib
import io
import os
import shutil
import tempfile
import unittest
from xml.sax.saxutils import escape

import ci_failure_report as report
from test_ci_failure_trace import PRODUCTION_TRACE

ASSERTION_TRACE = (
    "java.lang.AssertionError: expected:<1> but was:<2>\n"
    "\tat app//org.junit.Assert.fail(Assert.java:89)\n"
    "\tat app//com.eve.app.data.ScoreMathTest.addsMarks(ScoreMathTest.kt:17)\n"
)


def suite_xml(classname, cases):
    """cases: (name, kind, trace); kind is None, 'failure', 'error' or 'skipped'."""
    parts = ['<?xml version="1.0" encoding="UTF-8"?>', '<testsuite name="%s">' % classname]
    for name, kind, trace in cases:
        parts.append('<testcase name="%s" classname="%s" time="0.1">' % (name, classname))
        if kind == "skipped":
            parts.append("<skipped/>")
        elif kind:
            first = (trace or "x").splitlines()[0]
            parts.append('<%s message="%s" type="t">%s</%s>' % (
                kind, escape(first, {'"': "&quot;"}), escape(trace or ""), kind))
        parts.append("</testcase>")
    parts.append("<system-out><![CDATA[noise]]></system-out></testsuite>")
    return "\n".join(parts)


class CiFailureReportTest(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.results = os.path.join(self.root, "test-results")
        os.makedirs(self.results)
        self.sources = os.path.join(self.root, "src")
        self.summary = os.path.join(self.root, "summary.md")

    def write(self, relative, text):
        path = os.path.join(self.root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        return path

    def write_suite(self, classname, cases, directory="test-results"):
        return self.write(os.path.join(directory, "TEST-%s.xml" % classname), suite_xml(classname, cases))

    def run_main(self, *args):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            code = report.main(list(args) + ["--source-root", self.sources, "--summary-file", self.summary])
        return code, stream.getvalue()

    # ---- reports ----

    def test_counts_failures_errors_skipped_and_passed(self):
        self.write_suite("com.eve.app.data.ScoreMathTest", [
            ("addsMarks", "failure", ASSERTION_TRACE),
            ("crashes", "error", PRODUCTION_TRACE),
            ("ignored", "skipped", None),
            ("passes", None, None),
        ])
        result = report.collect_results([self.results])
        self.assertEqual((result["files"], result["tests"], result["skipped"]), (1, 4, 1))
        self.assertEqual(sorted(f["kind"] for f in result["failures"]), ["error", "failure"])
        assertion = [f for f in result["failures"] if f["name"] == "addsMarks"][0]
        self.assertEqual(assertion["app_frame"], "")
        self.assertIn("ScoreMathTest.addsMarks", assertion["test_frame"])

    def test_groups_tests_sharing_root_cause_and_frame(self):
        self.write_suite("com.eve.app.ui.profile.ProfileNavigationRegressionTest", [
            ("opensProfile", "error", PRODUCTION_TRACE),
            ("recreates", "error", PRODUCTION_TRACE),
            ("repeats", "error", PRODUCTION_TRACE),
        ])
        self.write_suite("com.eve.app.data.ScoreMathTest", [("addsMarks", "failure", ASSERTION_TRACE)])
        code, output = self.run_main("--unit", self.results)
        self.assertEqual(code, 0)
        self.assertIn("reports=2 tests=4 failing=4 skipped=0 groups=2", output)
        self.assertIn("[G1] 3 test(s): java.lang.IllegalStateException: boom", output)
        self.assertIn("first causal app frame: at app//com.eve.app.ui.profile.ProfileGooeyView.refreshNotchInfo", output)
        self.assertIn("x3 java.lang.IllegalStateException: boom", output)
        self.assertIn("test code; no production app frame in the trace", output)

    def test_annotations_end_the_output_and_are_escaped(self):
        self.write_suite("com.eve.app.data.ScoreMathTest", [("addsMarks", "failure", ASSERTION_TRACE)])
        code, output = self.run_main("--unit", self.results)
        lines = [line for line in output.splitlines() if line.strip()]
        self.assertEqual(code, 0)
        self.assertTrue(lines[-1].startswith("::error title=unit G1/1 (1 test(s))::"))
        self.assertIn("%0Afirst causal app frame: at app//com.eve.app.data.ScoreMathTest.addsMarks", lines[-1])
        self.assertIn("%0A", lines[-1])
        commands = [line for line in lines if line.lstrip().startswith("::")]
        self.assertEqual(len(commands), 2)
        self.assertTrue(all(line.startswith("::error title=unit") for line in commands))
        self.assertTrue(commands[0].startswith("::error title=unit tests%3A 1 failing in 1 group(s)::"))

    def test_test_output_cannot_inject_workflow_commands(self):
        trace = ("java.lang.AssertionError: bad\n::set-output name=x::y\n   ::error::fake\n"
                 "##[error]fake\n\tat com.eve.app.data.ScoreMathTest.addsMarks(ScoreMathTest.kt:17)\n")
        self.write_suite("com.eve.app.data.ScoreMathTest", [("addsMarks", "failure", trace)])
        _, output = self.run_main("--unit", self.results, "--no-annotations")
        self.assertFalse([line for line in output.splitlines() if line.lstrip().startswith("::")])
        self.assertNotIn("##[", output)
        self.assertIn(": :set-output name=x::y", output)

    def test_missing_results_are_reported_without_failing(self):
        log = self.write("gradle.log", "\n".join([
            "> Task :app:compileDebugUnitTestKotlin FAILED",
            "e: file:///repo/app/src/test/java/com/eve/app/FooTest.kt:12:5 Unresolved reference: bar",
            "* What went wrong:",
            "Execution failed for task ':app:compileDebugUnitTestKotlin'.",
            "> Compilation error. See log for more details",
            "* Try:",
            "> Run with --info",
        ]))
        code, output = self.run_main("--unit", os.path.join(self.root, "absent"), "--gradle-log", log)
        self.assertEqual(code, 0)
        self.assertIn("directory not found:", output)
        self.assertIn("No JUnit XML report was found", output)
        self.assertIn("Unresolved reference: bar", output)
        self.assertIn("Execution failed for task ':app:compileDebugUnitTestKotlin'.", output)
        self.assertNotIn("Run with --info", output)
        last = [line for line in output.splitlines() if line.strip()][-1]
        self.assertTrue(last.startswith("::error title=unit tests%3A no failing test in the reports::"))

    def test_green_reports_produce_a_notice_not_an_error(self):
        self.write_suite("com.eve.app.data.ScoreMathTest", [("passes", None, None)])
        code, output = self.run_main("--unit", self.results)
        self.assertEqual(code, 0)
        self.assertIn("The reports contain no failing test", output)
        self.assertNotIn("::error", output)
        self.assertIn("::notice title=unit tests%3A no failing test in the reports::", output)

    def test_malformed_report_is_listed_and_others_still_count(self):
        self.write("test-results/TEST-broken.xml", "<testsuite><testcase name='a'")
        self.write("test-results/TEST-salvaged.xml",
                   "<testsuite><testcase name='a' classname='c.T'/><system-out>\x00<&</system-out></testsuite>")
        self.write_suite("com.eve.app.data.ScoreMathTest", [("addsMarks", "failure", ASSERTION_TRACE)])
        code, output = self.run_main("--unit", self.results)
        self.assertEqual(code, 0)
        self.assertIn("unparseable report:", output)
        self.assertIn("TEST-broken.xml", output)
        self.assertNotIn("TEST-salvaged.xml", output)
        self.assertIn("reports=2 tests=2 failing=1", output)

    def test_step_summary_is_written(self):
        self.write_suite("com.eve.app.data.ScoreMathTest", [("addsMarks", "failure", report.FENCE + "\n" + ASSERTION_TRACE)])
        self.run_main("--unit", self.results)
        with open(self.summary, encoding="utf-8") as handle:
            summary = handle.read()
        self.assertIn("### CI failure report (unit)", summary)
        self.assertIn("ScoreMathTest.addsMarks", summary)
        self.assertEqual(summary.count(report.FENCE), 2)

    def test_output_stays_inside_the_readable_log_tail(self):
        for number in range(120):
            frames = "".join(
                "\tat com.eve.app.pkg%d.Screen%d.method%d(Screen%d.kt:%d)%s\n" % (
                    number, number, i, number, i, " " + "x" * 400) for i in range(200))
            trace = "java.lang.IllegalStateException: failure number %d %s\n%s" % (number, "y" * 900, frames)
            self.write_suite("com.eve.app.pkg%d.Screen%dTest" % (number, number), [("fails", "error", trace)])
        code, output = self.run_main("--unit", self.results)
        self.assertEqual(code, 0)
        self.assertIn("failing=120", output)
        self.assertLess(len(output), 90000)
        self.assertEqual(len([line for line in output.splitlines() if line.startswith("::error")]), 10)
        self.assertIn("more group(s)", output)

    # ---- native mode, as invoked by build.yml, and the entry point ----

    def test_native_mode_reads_nested_reports_and_log_markers(self):
        nested = os.path.join("connected", "debug", "device")
        self.write(os.path.join(nested, "TEST-device.xml"),
                   "<testsuites>%s</testsuites>" % suite_xml(
                       "com.eve.app.ui.ProfileUiTest", [("opens", "failure", PRODUCTION_TRACE)]).split("?>", 1)[1])
        self.write("ui-renderings/gesture.log", "\n".join(
            ["line %d" % i for i in range(5)] + ["INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "after"]))
        code, output = self.run_main(
            "--native", os.path.join(self.root, "connected"), os.path.join(self.root, "ui-renderings"))
        self.assertEqual(code, 0)
        self.assertIn("=== EVE CI failure report (native) ===", output)
        self.assertIn("failing=1", output)
        self.assertIn("gesture.log:6", output)
        self.assertIn("Process crashed", output)
        self.assertTrue(output.rstrip().splitlines()[-1].startswith("::error title=native G1/1"))

    def test_usage_error_without_a_mode(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                report.run([])
        self.assertEqual(raised.exception.code, 2)

    def test_reporter_bug_is_reported_without_changing_the_exit_status(self):
        self.addCleanup(setattr, report, "collect_results", report.collect_results)
        report.collect_results = lambda *args, **kwargs: 1 / 0
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            code = report.run(["--unit", self.results, "--summary-file", self.summary])
        lines = stream.getvalue().rstrip().splitlines()
        self.assertEqual(code, 0)
        self.assertIn("ZeroDivisionError: division by zero", lines)
        self.assertTrue(lines[-1].startswith("::error title=CI failure reporter crashed::"))


if __name__ == "__main__":
    unittest.main()
"""
```

"""