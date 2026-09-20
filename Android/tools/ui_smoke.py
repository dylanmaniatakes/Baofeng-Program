"""Read-only UI smoke test on an emulator with an existing local workspace."""
import argparse
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--device", default="emulator-5554")
parser.add_argument("--output", default="/tmp/baofeng-ui-smoke")
args = parser.parse_args()
if not args.device.startswith("emulator-"):
    raise SystemExit("This UI test only operates on an emulator, never a physical radio phone")
out = pathlib.Path(args.output)
out.mkdir(parents=True, exist_ok=True)

def adb(*command):
    return subprocess.check_output([args.adb, "-s", args.device, *command], timeout=30)

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/baofeng-smoke.xml")
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/baofeng-smoke.xml")).iter("node"))

def tap(label):
    matches = [n for n in nodes() if label in (n.get("text"), n.get("content-desc"))]
    if not matches:
        raise AssertionError("UI element missing: " + label)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(0.5)

def screenshot(name):
    (out / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

adb("shell", "am", "start", "-n", "com.ticnitsi.baofengprogram/.MainActivity")
tap("More options")
tap("Appearance")
tap("Light")
tap("Done")
tap("Memories")
screenshot("memories-light")
tap("001")
screenshot("memory-editor")
tap("Save memory")
assert not any("settings changed" in n.get("text", "") for n in nodes()), "Saving an untouched memory changed radio data"
tap("Radio settings")
screenshot("settings-light")
tap("More options")
tap("Appearance")
tap("Dark")
tap("Done")
screenshot("settings-dark")
adb("shell", "wm", "size", "720x1280")
adb("shell", "wm", "density", "320")
time.sleep(1)
screenshot("small-phone")
adb("shell", "wm", "size", "1600x1000")
adb("shell", "wm", "density", "200")
time.sleep(1)
screenshot("tablet")
adb("shell", "wm", "size", "reset")
adb("shell", "wm", "density", "reset")
print("UI smoke passed; screenshots in", out)
