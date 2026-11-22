"""

```python
"""
# Storage note: the first and last string literals of this file are inert; see the
# note at the top of ci_failure_report.py.
__doc__ = """
Stack trace analysis for the Eve CI failure reporter (ci_failure_report.py).

Finds the root-cause exception (the last "Caused by:") and the first causal app
frame of a Java stack trace, and condenses long traces. The only I/O is the
source-tree lookup that tells test code from production code.
"""

import os
import re

APP_PREFIXES = ("com.eve.", "org.telegram.", "me.vkryl.")
CAUSED_BY = "Caused by: "
DOLLAR = chr(36)
FRAME_RE = re.compile(r"^\s*at\s+([^\s(]+)[(]([^)]*)[)]")
CLASS_NAME_RE = re.compile(r"^[A-Za-z_\x24][\w.\x24]*\Z")
MAX_LINE = 300


def clip(text, limit=MAX_LINE):
    text = text.rstrip()
    return text if len(text) <= limit else text[: limit - 3] + "..."


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
"""
```

"""