"""Execute the production device-notification method with the HTTP boundary recorded."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/kinopub/KinoPub.java"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.getenv("JAVA_HOME") else shutil.which("java")


class KinoDeviceTest(unittest.TestCase):
    @unittest.skipUnless(JAVA, "JDK 17 required")
    def test_device_identity_encoding_and_optional_failure(self):
        source = SOURCE.read_text()
        start = source.index("    static void notifyDevice(")
        end = source.index("\n    }", start) + len("\n    }")
        enc = next(line for line in source.splitlines() if "private static String enc(" in line)
        program = """import java.net.*;
import java.io.*;
import java.util.*;
class KinoDeviceCheck {
    static class Context {}
    static class Build {
        static String MANUFACTURER = "TCL", DEVICE = "G19", MODEL = "Smart TV Pro";
        static class VERSION { static String RELEASE = "14"; }
    }
    static class AppLog { static String last = ""; static void w(String message) { last = message; } }
    static String path, form, bearer;
    static boolean fail;
    static String accessToken(Context c) { return "private-test-token"; }
    static void request(String target, String body, String token) throws IOException {
        path = target; form = body; bearer = token;
        if (fail) throw new IOException("private-test-token: raw server payload");
    }
""" + source[start:end] + "\n" + enc + """
    static Map<String, String> fields() throws Exception {
        Map<String, String> fields = new HashMap<>();
        for (String part : form.split("&")) {
            String[] pair = part.split("=", 2);
            fields.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
        }
        return fields;
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Context c = new Context(); notifyDevice(c);
        Map<String, String> info = fields();
        check(path.equals("/v1/device/notify"), "Wrong endpoint");
        check(bearer.equals("private-test-token") && !form.contains(bearer), "Token belongs only in authorization");
        check(info.size() == 3 && info.get("title").equals("Android CleanYourTV UI"), "Device title lost");
        check(info.get("hardware").equals("TCL G19 (Smart TV Pro)"), "Model lost");
        check(info.get("software").equals("Android14"), "Software format differs from Android clients");
        Build.MANUFACTURER = "TCL & Компания"; Build.MODEL = "G08 + TV/Pro";
        notifyDevice(c); info = fields();
        check(info.size() == 3 && info.get("hardware").equals("TCL & Компания G19 (G08 + TV/Pro)")
                && info.get("title").equals("Android CleanYourTV UI"), "Unsafe form encoding");
        fail = true; notifyDevice(c);
        check(AppLog.last.contains("IOException") && !AppLog.last.contains("private-test-token")
                && !AppLog.last.contains("raw server payload"), "Failure leaked private data");
    }
}
"""
        with tempfile.TemporaryDirectory() as folder:
            check = Path(folder) / "KinoDeviceCheck.java"
            check.write_text(program)
            result = subprocess.run([JAVA, check], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
