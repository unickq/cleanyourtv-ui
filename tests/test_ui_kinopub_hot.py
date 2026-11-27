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

    @unittest.skipUnless(JAVA, "JDK 17 required")
    def test_home_filters_keep_catalogue_and_foreign_cartoons(self):
        source = (SOURCE / "KinoPub.java").read_text()
        methods = []
        for signature in ("    private static boolean isAnime(", "    private static boolean isRussianProduction(",
                          "    private static List<Tmdb.Title> shelfTitles("):
            start = source.index(signature)
            end = source.index("\n    }", start) + len("\n    }")
            methods.append(source[start:end])
        program = """import java.util.*;
class KinoAnimeCheck {
    static final String KIND = "kinopub";
    static class JSONObject {
        final Map<String, Object> values = new HashMap<>();
        JSONObject put(String key, Object value) { values.put(key, value); return this; }
        int optInt(String key) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : 0; }
        String optString(String key) { return optString(key, ""); }
        String optString(String key, String fallback) {
            Object value = values.get(key); return value instanceof String ? (String) value : fallback;
        }
        JSONObject optJSONObject(String key) { return (JSONObject) values.get(key); }
        JSONArray optJSONArray(String key) { return (JSONArray) values.get(key); }
    }
    static class JSONArray {
        final List<JSONObject> items;
        JSONArray(JSONObject... items) { this.items = Arrays.asList(items); }
        int length() { return items.size(); }
        JSONObject optJSONObject(int index) { return items.get(index); }
    }
    static class Tmdb {
        static class Title {
            final int id;
            final String year;
            String kinoCaption;
            boolean kinoNeedsSeasons;
            Title(String kind, int id, String title, String poster, String wide, String year, int score) {
                this.id = id; this.year = year;
            }
        }
    }
    static class R { static class string { static int series = 1, movie = 2; } static class plurals { static int seasons; } }
    static class Strings {
        static String get(int key) { return "Movie"; }
        static String quantity(int key, int count) { return "Seasons"; }
        static String duration(int minutes) { return "Duration"; }
    }
    static String displayTitle(String title) { return title; }
    static String posterUrl(JSONObject posters, String size) { return ""; }
    static int seasonCount(JSONObject item) { return 0; }
    static int movieMinutes(JSONObject item) { return 0; }
""" + "\n".join(methods) + """
    static JSONObject genre(int id) { return new JSONObject().put("id", id); }
    static JSONArray countries(String... names) {
        return new JSONArray(Arrays.stream(names).map(name -> new JSONObject().put("title", name)).toArray(JSONObject[]::new));
    }
    static JSONObject item(int id, JSONArray genres) {
        return new JSONObject().put("id", id).put("title", "Title " + id).put("genres", genres);
    }
    public static void main(String[] args) {
        List<JSONObject> input = Arrays.asList(
            item(10, new JSONArray(genre(25))),
            item(11, new JSONArray(genre(7), genre(25))),
            item(12, new JSONArray(genre(23))),
            item(13, new JSONArray(genre(7))),
            item(14, null),
            item(15, new JSONArray((JSONObject) null)),
            item(16, null).put("countries", countries("Россия")),
            item(17, null).put("countries", countries("США", "Россия")),
            item(18, null).put("countries", countries("Russia")),
            item(19, null).put("countries", countries("Украина")).put("title", "Фильм на русском"),
            item(20, null).put("countries", new JSONArray((JSONObject) null)),
            item(21, null).put("countries", countries("СССР")),
            item(22, null).put("countries", countries("  РОССИЯ  ")),
            item(23, null).put("countries", countries("Росія")),
            item(24, null).put("countries", countries("Russian Federation")),
            item(25, null).put("countries", countries("Российская Федерация")));
        List<Tmdb.Title> home = shelfTitles(input, true);
        if (!home.stream().map(t -> t.id).toList().equals(Arrays.asList(12, 13, 14, 15, 19, 20, 21)))
            throw new AssertionError("Home must hide anime and Russian production, retaining foreign cartoons and unknown countries");
        if (shelfTitles(input, false).size() != input.size())
            throw new AssertionError("Home filters must not affect the catalogue");
    }
}
"""
        with tempfile.TemporaryDirectory() as folder:
            check = Path(folder) / "KinoAnimeCheck.java"
            check.write_text(program)
            result = subprocess.run([JAVA, check], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
