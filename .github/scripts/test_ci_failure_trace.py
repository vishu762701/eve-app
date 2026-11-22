"""

```python
"""
# Storage note: the first and last string literals of this file are inert; see the
# note at the top of ci_failure_report.py.
__doc__ = """
Unit tests for ci_failure_trace.py. No network access.
"""

import os
import shutil
import tempfile
import unittest

import ci_failure_trace as analysis

PRODUCTION_TRACE = (
    "java.lang.RuntimeException: Unable to start activity ComponentInfo{com.eve.app/"
    "com.eve.app.ui.profile.ProfileActivity}: java.lang.IllegalStateException: boom\n"
    "\tat android.app.ActivityThread.performLaunchActivity(ActivityThread.java:3782)\n"
    "\tat org.robolectric.android.controller.ActivityController.create(ActivityController.java:206)\n"
    "\tat app//com.eve.app.ui.profile.ProfileNavigationRegressionTest.opensProfile"
    "(ProfileNavigationRegressionTest.kt:91)\n"
    "\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)\n"
    "Caused by: java.lang.IllegalStateException: boom\n"
    "\tat android.view.View.requireViewById(View.java:100)\n"
    "\tat app//com.eve.app.ui.profile.ProfileGooeyView.refreshNotchInfo(ProfileGooeyView.kt:212)\n"
    "\tat app//com.eve.app.ui.profile.ProfileActivity.onCreate(ProfileActivity.kt:187)\n"
    "\t... 42 more\n"
)

ABBREVIATED_ROOT_TRACE = (
    "java.lang.RuntimeException: wrapper\n"
    "\tat app//com.eve.app.ui.test.TestActivity.onCreate(TestActivity.kt:44)\n"
    "\tat app//com.eve.app.ui.test.TestActivityStartTest.starts(TestActivityStartTest.kt:9)\n"
    "Caused by: java.lang.NullPointerException: missing\n"
    "\t... 2 more\n"
)


class CiFailureTraceTest(unittest.TestCase):

    def test_root_cause_and_first_causal_app_frame(self):
        info = analysis.analyze_trace(
            PRODUCTION_TRACE,
            analysis.SourceIndex(test_classes={"com.eve.app.ui.profile.ProfileNavigationRegressionTest"}))
        self.assertEqual(info["exception"], "java.lang.RuntimeException")
        self.assertEqual(info["root_exception"], "java.lang.IllegalStateException")
        self.assertEqual(info["root_message"], "boom")
        self.assertEqual(
            info["app_frame"],
            "at app//com.eve.app.ui.profile.ProfileGooeyView.refreshNotchInfo(ProfileGooeyView.kt:212)")
        self.assertEqual(info["top_frame"], "at android.view.View.requireViewById(View.java:100)")

    def test_abbreviated_root_block_uses_enclosing_frames(self):
        info = analysis.analyze_trace(
            ABBREVIATED_ROOT_TRACE,
            analysis.SourceIndex(test_classes={"com.eve.app.ui.test.TestActivityStartTest"}))
        self.assertEqual(info["root_exception"], "java.lang.NullPointerException")
        self.assertEqual(
            info["app_frame"], "at app//com.eve.app.ui.test.TestActivity.onCreate(TestActivity.kt:44)")
        self.assertEqual(
            info["test_frame"],
            "at app//com.eve.app.ui.test.TestActivityStartTest.starts(TestActivityStartTest.kt:9)")

    def test_names_alone_do_not_mark_production_code_as_test_code(self):
        index = analysis.SourceIndex()
        self.assertFalse(index.is_test_frame("\tat com.eve.app.ui.test.TestActivity.onCreate(TestActivity.kt:1)"))
        self.assertFalse(index.is_test_frame("\tat com.eve.app.data.model.GeneratedTest.copy(GeneratedTest.kt:1)"))
        self.assertFalse(index.is_test_frame(
            "\tat org.telegram.ui.Cells.ShadowSectionCell.onMeasure(ShadowSectionCell.java:1)"))
        self.assertTrue(index.is_test_frame(
            "\tat com.eve.app.ui.home.HomeStartupBoundaryFixture\x24ShadowHome.get(HomeStartupBoundaryFixture.java:1)"))

    def test_source_tree_decides_test_versus_production(self):
        root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        for relative in ("main/java/com/eve/app/data/model/GeneratedTest.kt",
                         "test/java/com/eve/app/FakeApi.kt",
                         "androidTest/java/com/eve/app/Helper.java"):
            path = os.path.join(root, relative)
            os.makedirs(os.path.dirname(path))
            with open(path, "w", encoding="utf-8"):
                pass
        index = analysis.SourceIndex(root)
        self.assertFalse(index.is_test_frame("\tat com.eve.app.data.model.GeneratedTest.copy(GeneratedTest.kt:3)"))
        self.assertTrue(index.is_test_frame("\tat com.eve.app.FakeApi\x24call\x241.invoke(FakeApi.kt:8)"))
        self.assertTrue(index.is_test_frame("\tat com.eve.app.Helper.run(Helper.java:2)"))

    def test_condensed_trace_keeps_root_cause_and_app_frame(self):
        filler = "".join("\tat android.os.Looper.loop%d(Looper.java:%d)\n" % (i, i) for i in range(80))
        trace = ("java.lang.RuntimeException: outer\n" + filler
                 + "Caused by: java.lang.IllegalStateException: inner\n" + filler
                 + "\tat com.eve.app.ui.profile.ProfileActivity.onCreate(ProfileActivity.kt:187)\n")
        lines = analysis.condense_trace(trace, 12)
        self.assertLess(len(lines), 30)
        self.assertIn("Caused by: java.lang.IllegalStateException: inner", lines)
        self.assertIn("\tat com.eve.app.ui.profile.ProfileActivity.onCreate(ProfileActivity.kt:187)", lines)
        self.assertTrue(any("omitted" in line for line in lines))


if __name__ == "__main__":
    unittest.main()
"""
```

"""