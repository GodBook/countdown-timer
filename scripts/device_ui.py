"""Small adb helper for repeatable UI smoke checks; does not require extra packages."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.awxds.countdowntimer"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, encoding="utf-8").strip()


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/timer-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/timer-ui.xml"))


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.4)


def tap_text(text):
    for node in hierarchy().iter("node"):
        if node.get("text") == text:
            tap(node)
            return
    raise AssertionError(f"Visible text not found: {text}")


def screenshot(name):
    hierarchy()
    path = Path(__file__).resolve().parents[1] / "verification" / "screenshots" / f"{name}.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    adb("shell", "screencap", "-p", "/sdcard/timer-screen.png")
    adb("pull", "/sdcard/timer-screen.png", str(path))
    print(path)


if __name__ == "__main__":
    command = sys.argv[1]
    if command == "tap":
        tap_text(sys.argv[2])
    elif command == "screenshot":
        screenshot(sys.argv[2])
    elif command == "text":
        print("\n".join(node.get("text", "") for node in hierarchy().iter("node") if node.get("text")))
    elif command == "duration":
        values = sys.argv[2:5]
        assert len(values) == 3
        for index, value in enumerate(values):
            inputs = [node for node in hierarchy().iter("node") if node.get("class") == "android.widget.EditText"]
            tap(inputs[index])
            adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            adb("shell", "input", "keyevent", "67", "67", "67")
            adb("shell", "input", "text", value)
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
    else:
        raise SystemExit("Use tap TEXT, screenshot NAME, text, or duration HOURS MINUTES SECONDS")
