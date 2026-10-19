import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import behavior_corpus as corpus


class FakeResponse:
    def __init__(self, payload, url):
        self.payload = payload
        self.offset = 0
        self.url = url
        self.headers = {"Content-Length": str(len(payload))}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def geturl(self):
        return self.url

    def read(self, size=-1):
        if size < 0:
            size = len(self.payload)
        chunk = self.payload[self.offset:self.offset + size]
        self.offset += len(chunk)
        return chunk


class FakeOpener:
    def __init__(self, payload):
        self.payload = payload
        self.open = Mock(side_effect=self._open)

    def _open(self, request, timeout):
        return FakeResponse(self.payload, request.full_url)


class ResolveTests(unittest.TestCase):
    def setUp(self):
        self.project = {"id": "p123", "license": {"id": "MIT"},
                        "source_url": "https://github.com/example/example"}

    def version(self, ident, date, filename="z.jar", primary=False, games=None, loaders=None):
        content = (ident + filename).encode()
        return {"id": ident, "version_number": ident, "date_published": date,
                "game_versions": games or ["1.20.1"], "loaders": loaders or ["fabric"],
                "files": [{"filename": filename, "url": f"https://cdn.modrinth.com/data/p123/versions/{ident}/{filename}",
                           "primary": primary, "size": len(content),
                           "hashes": {"sha512": hashlib.sha512(content).hexdigest()}}]}

    def test_resolve_uses_exact_query_newest_date_then_id_and_metadata(self):
        old = self.version("v-old", "2024-01-01T00:00:00Z")
        tie_a = self.version("v-a", "2025-01-01T00:00:00Z", "b.jar")
        tie_z = self.version("v-z", "2025-01-01T00:00:00Z", "a.jar", primary=True)
        with patch.object(corpus, "_api_json", side_effect=[self.project, [old, tie_a, tie_z]]) as api:
            row = corpus.resolve_pick("example:1.20.1:fabric:17")
        self.assertEqual(row["version_id"], "v-z")
        self.assertEqual(row["filename"], "a.jar")
        self.assertEqual(row["license"], "MIT")
        self.assertEqual(row["source_url"], "https://github.com/example/example")
        self.assertEqual(row["project_url"], "https://modrinth.com/mod/example")
        query = parse_qs(urlsplit(api.call_args_list[1].args[0]).query)
        self.assertEqual(json.loads(query["game_versions"][0]), ["1.20.1"])
        self.assertEqual(json.loads(query["loaders"][0]), ["fabric"])
        self.assertEqual(query["include_changelog"], ["false"])

    def test_loader_membership_is_checked_even_after_server_filter(self):
        mismatch = self.version("v-wrong", "2025-01-01T00:00:00Z", loaders=["forge"])
        with patch.object(corpus, "_api_json", side_effect=[self.project, [mismatch]]):
            with self.assertRaisesRegex(corpus.CorpusError, "no Modrinth version matches"):
                corpus.resolve_pick("example:1.20.1:fabric:17")

    def test_filename_fallback_is_lexically_deterministic(self):
        version = self.version("v1", "2025-01-01T00:00:00Z", "z.jar")
        version["files"].append({**self.version("v1", "2025-01-01T00:00:00Z", "a.jar")["files"][0]})
        with patch.object(corpus, "_api_json", side_effect=[self.project, [version]]):
            row = corpus.resolve_pick("example:1.20.1:fabric:17")
        self.assertEqual(row["filename"], "a.jar")


class FetchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.payload = b"small immutable test jar bytes"
        self.row = {
            "project": "example", "project_id": "p123", "version_id": "v456",
            "version_number": "1.0", "game_version": "1.20.1", "loader": "fabric",
            "target_java": 17, "filename": "example.jar",
            "url": "https://cdn.modrinth.com/data/p123/versions/v456/example.jar",
            "sha512": hashlib.sha512(self.payload).hexdigest(), "size": len(self.payload),
            "license": "MIT", "project_url": "https://modrinth.com/mod/example",
            "source_url": "https://gitlab.com/example/project",
        }
        self.lock_path = self.root / "lock.json"
        self.cache = self.root / "cache"
        self.plan_path = self.root / "plan.json"
        self.write_lock()

    def tearDown(self):
        self.temp.cleanup()

    def write_lock(self, row=None):
        self.lock_path.write_text(json.dumps({"schema": 1, "artifacts": [row or self.row]}, sort_keys=True) + "\n",
                                  encoding="utf-8")

    def test_rejects_malformed_hash_path_and_url(self):
        invalid_rows = []
        bad_hash = {**self.row, "sha512": "f" * 127}
        bad_name = {**self.row, "filename": "../example.jar"}
        bad_url = {**self.row, "url": "http://cdn.modrinth.com/data/p123/versions/v456/example.jar"}
        wrong_host = {**self.row, "url": "https://evil.example/data/p123/versions/v456/example.jar"}
        encoded_traversal = {**self.row, "url": "https://cdn.modrinth.com/data/p123/versions/v456/%252e%252e/example.jar"}
        encoded_separator = {**self.row, "url": "https://cdn.modrinth.com/data/p123/versions/v456/example%2f.jar"}
        invalid_rows.extend([bad_hash, bad_name, bad_url, wrong_host, encoded_traversal, encoded_separator])
        for row in invalid_rows:
            with self.subTest(row=row):
                self.write_lock(row)
                with self.assertRaises(corpus.CorpusError):
                    corpus.load_lock(self.lock_path)

    def test_plus_filename_accepts_raw_and_percent_encoded_path_characters(self):
        filename = "Just Enough Items+1.jar"
        row = {**self.row, "filename": filename}
        prefix = "https://cdn.modrinth.com/data/p123/versions/v456/"
        for url in (prefix + "Just%20Enough%20Items+1.jar", prefix + "Just%20Enough%20Items%2B1.jar"):
            with self.subTest(url=url):
                candidate = {**row, "url": url}
                self.write_lock(candidate)
                _raw, lock = corpus.load_lock(self.lock_path)
                self.assertEqual(lock["artifacts"][0]["filename"], filename)

    def test_historical_sodium_url_uses_opaque_cdn_version_directory(self):
        row = {
            "project": "sodium", "project_id": "AANobbMI", "version_id": "YAGZ1cCS",
            "version_number": "mc1.16.5-0.2.0+build.4", "game_version": "1.16.5", "loader": "fabric",
            "target_java": 8, "filename": "sodium-fabric-mc1.16.5-0.2.0+build.4.jar",
            "url": "https://cdn.modrinth.com/data/AANobbMI/versions/mc1.16.5-0.2.0/sodium-fabric-mc1.16.5-0.2.0%2Bbuild.4.jar",
            "sha512": "a" * 128, "size": 1234, "license": "MIT",
            "project_url": "https://modrinth.com/mod/sodium", "source_url": "https://github.com/CaffeineMC/sodium",
        }
        self.write_lock(row)
        _raw, lock = corpus.load_lock(self.lock_path)
        self.assertEqual(lock["artifacts"][0]["version_id"], "YAGZ1cCS")

    def test_lock_accepts_declared_jar_size_above_64_mib_without_allocating_it(self):
        row = {**self.row, "size": 64 * 1024 * 1024 + 1}
        self.write_lock(row)
        _raw, lock = corpus.load_lock(self.lock_path)
        self.assertEqual(lock["artifacts"][0]["size"], 64 * 1024 * 1024 + 1)

    def test_corrupt_hash_named_cache_fails_without_network_or_replacement(self):
        self.cache.mkdir()
        cached = self.cache / f"{self.row['sha512']}.jar"
        cached.write_bytes(b"corrupt")
        with patch.object(corpus, "build_opener") as build_opener:
            with self.assertRaisesRegex(corpus.CorpusError, "corrupted cached JAR"):
                corpus.fetch(self.lock_path, self.cache, self.plan_path)
        build_opener.assert_not_called()
        self.assertEqual(cached.read_bytes(), b"corrupt")
        self.assertFalse(self.plan_path.exists())

    def test_offline_cache_miss_fails_without_network(self):
        with patch.object(corpus, "build_opener") as build_opener:
            with self.assertRaisesRegex(corpus.CorpusError, "offline cache miss"):
                corpus.fetch(self.lock_path, self.cache, self.plan_path, offline=True)
        build_opener.assert_not_called()
        self.assertFalse(self.plan_path.exists())

    def test_download_verifies_then_offline_cached_plan_leaves_lock_unchanged(self):
        original_lock = self.lock_path.read_bytes()
        lock_hash = hashlib.sha256(original_lock).hexdigest()
        opener = FakeOpener(self.payload)
        with patch.object(corpus, "build_opener", return_value=opener):
            corpus.fetch(self.lock_path, self.cache, self.plan_path)
        self.assertEqual(opener.open.call_count, 1)
        self.assertEqual(opener.open.call_args.args[0].full_url, self.row["url"])
        cached = self.cache / f"{self.row['sha512']}.jar"
        self.assertEqual(cached.read_bytes(), self.payload)
        with patch.object(corpus, "build_opener", side_effect=AssertionError("offline cache should be reused")):
            corpus.fetch(self.lock_path, self.cache, self.plan_path, offline=True)
        self.assertEqual(self.lock_path.read_bytes(), original_lock)
        plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        self.assertEqual(plan["schema"], 1)
        self.assertEqual(plan["lock_sha256"], lock_hash)
        self.assertEqual(plan["artifacts"][0]["path"], str(cached.resolve()))
        self.assertEqual(set(plan["artifacts"][0]), set(self.row) | {"path"})

    def test_bad_download_is_not_installed(self):
        with patch.object(corpus, "build_opener", return_value=FakeOpener(b"wrong bytes")):
            with self.assertRaisesRegex(corpus.CorpusError, "size mismatch"):
                corpus.fetch(self.lock_path, self.cache, self.plan_path)
        self.assertEqual(list(self.cache.glob("*.jar")), [])
        self.assertFalse(self.plan_path.exists())


if __name__ == "__main__":
    unittest.main()
