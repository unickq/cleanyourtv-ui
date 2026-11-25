"""Home picks use KinoPub's hot catalogue and retain home artwork."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/io/cleanyourtv/ui/kinopub"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.getenv("JAVA_HOME") else shutil.which("java")


class KinoHotTest(unittest.TestCase):
    @unittest.skipUnless(JAVA, "JDK 17 required")
    def test_hot_movies_and_series_keep_home_artwork(self):
        source = (SOURCE / "KinoPub.java").read_text()
        start = source.index("    static List<Tmdb.Title> hot(")
        end = source.index("\n    }", start) + len("\n    }")
        program = """import java.util.*;
class KinoHotCheck {
    static class Context {}
    static class Tmdb { static class Title {} }
    static String requested;
    static final List<Tmdb.Title> TITLES = List.of(new Tmdb.Title());
    static String items(Context c, String path) { requested = path; return path; }
    static List<Tmdb.Title> shelfTitles(String items, boolean homeArtwork) {
        if (!homeArtwork) throw new AssertionError("Home artwork required");
        return TITLES;
    }
""" + source[start:end] + """
    public static void main(String[] args) throws Exception {
        for (boolean movies : new boolean[]{true, false}) {
            if (hot(new Context(), movies) != TITLES) throw new AssertionError("Lost titles");
            String expected = "/v1/items/hot?type=" + (movies ? "movie" : "serial") + "&perpage=20";
            if (!expected.equals(requested)) throw new AssertionError(requested);
        }
    }
}
"""
        with tempfile.TemporaryDirectory() as folder:
            check = Path(folder) / "KinoHotCheck.java"
            check.write_text(program)
            result = subprocess.run([JAVA, check], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        home = (SOURCE / "KinoHomePicks.java").read_text()
        self.assertIn("KinoPub.hot(context, tab == 1)", home)
        self.assertNotIn("KinoPub.popular(", home)
