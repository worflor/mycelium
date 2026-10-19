#!/usr/bin/env python3
"""Resolve and fetch a hash-pinned set of public Modrinth JARs.

Resolution selects the newest published matching version across release channels;
an equal publication timestamp is broken by the lexically greatest version ID.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


API = "https://api.modrinth.com/v2"
CDN_HOST = "cdn.modrinth.com"
USER_AGENT = "worflor/mycelium (https://github.com/worflor/mycelium)"
TIMEOUT_SECONDS = 20
RETRIES = 2
HEX512 = re.compile(r"^[0-9a-f]{128}$")
SLUG = re.compile(r"^[a-z0-9][a-z0-9-]*$")
REMOTE_ID = re.compile(r"^[A-Za-z0-9_-]+$")
ARTIFACT_FIELDS = {
    "project", "project_id", "version_id", "version_number", "game_version", "loader",
    "target_java", "filename", "url", "sha512", "size", "license", "project_url", "source_url",
}


class CorpusError(Exception):
    """An invalid request, response, or artifact prevents a trustworthy corpus."""


def _json_bytes(data: bytes, label: str):
    try:
        return json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise CorpusError(f"{label} is not valid UTF-8 JSON: {exc}") from exc


class _SameHostRedirect(HTTPRedirectHandler):
    def __init__(self, host: str):
        super().__init__()
        self.host = host

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urlsplit(newurl)
        if (parsed.scheme != "https" or parsed.hostname != self.host or parsed.username or parsed.password
                or parsed.port not in (None, 443)):
            raise CorpusError(f"refusing redirect outside https://{self.host}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _request(url: str, host: str, limit: int | None) -> bytes:
    opener = build_opener(_SameHostRedirect(host))
    last_error = None
    for attempt in range(RETRIES + 1):
        try:
            request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json, application/octet-stream"})
            with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
                final = urlsplit(response.geturl())
                if final.scheme != "https" or final.hostname != host:
                    raise CorpusError(f"refusing response from unexpected host for {url}")
                length = response.headers.get("Content-Length")
                if limit is not None and length is not None and int(length) > limit:
                    raise CorpusError(f"response exceeds {limit} byte limit: {url}")
                chunks = []
                total = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    chunks.append(chunk)
                    total += len(chunk)
                    if limit is not None and total > limit:
                        raise CorpusError(f"response exceeds {limit} byte limit: {url}")
                return b"".join(chunks)
        except CorpusError:
            raise
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as exc:
            last_error = exc
            if attempt == RETRIES:
                break
            time.sleep(0.25 * (attempt + 1))
    raise CorpusError(f"request failed for {url}: {last_error}")


def _api_json(url: str):
    return _json_bytes(_request(url, "api.modrinth.com", None), url)


def _download_verified(url: str, cache: Path, row):
    opener = build_opener(_SameHostRedirect(CDN_HOST))
    last_error = None
    for attempt in range(RETRIES + 1):
        fd, temporary = tempfile.mkstemp(prefix=".download-", suffix=".tmp", dir=cache)
        try:
            request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream"})
            digest = hashlib.sha512()
            size = 0
            with os.fdopen(fd, "wb") as stream:
                with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
                    final = urlsplit(response.geturl())
                    if final.scheme != "https" or final.hostname != CDN_HOST:
                        raise CorpusError("refusing response from unexpected Modrinth CDN host")
                    length = response.headers.get("Content-Length")
                    if length is not None and int(length) > row["size"]:
                        raise CorpusError(f"download exceeds declared size for {row['project']}")
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        size += len(chunk)
                        if size > row["size"]:
                            raise CorpusError(f"download exceeds declared size for {row['project']}")
                        digest.update(chunk)
                        stream.write(chunk)
                stream.flush()
                os.fsync(stream.fileno())
            if size != row["size"]:
                raise CorpusError(f"size mismatch for {row['project']}: expected {row['size']}, got {size}")
            if digest.hexdigest() != row["sha512"]:
                raise CorpusError(f"SHA-512 mismatch for {row['project']}")
            return Path(temporary)
        except CorpusError:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as exc:
            last_error = exc
            try:
                os.unlink(temporary)
            except OSError:
                pass
            if attempt == RETRIES:
                break
            time.sleep(0.25 * (attempt + 1))
    raise CorpusError(f"download failed for {url}: {last_error}")


def _parse_published(value: str):
    if not isinstance(value, str):
        raise CorpusError("Modrinth version is missing date_published")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise CorpusError(f"invalid date_published: {value!r}") from exc
    if parsed.tzinfo is None:
        raise CorpusError(f"date_published has no timezone: {value!r}")
    return parsed.astimezone(dt.timezone.utc)


def _license(project):
    value = project.get("license")
    if value is None:
        return ""
    if isinstance(value, dict):
        return value.get("id") or value.get("name") or ""
    if isinstance(value, str):
        return value
    raise CorpusError("project metadata has an invalid license")


def _choose_file(version):
    files = version.get("files")
    if not isinstance(files, list):
        raise CorpusError(f"version {version.get('id')} has no files list")
    jars = [item for item in files if isinstance(item, dict) and isinstance(item.get("filename"), str)
            and item["filename"].lower().endswith(".jar")]
    if not jars:
        raise CorpusError(f"version {version.get('id')} has no JAR file")
    primary = [item for item in jars if item.get("primary") is True]
    return min(primary or jars, key=lambda item: item["filename"])


def resolve_pick(pick: str):
    parts = pick.split(":")
    if len(parts) != 4:
        raise CorpusError(f"pick must be slug:game:loader:java, got {pick!r}")
    slug, game, loader, java = parts
    if not SLUG.fullmatch(slug) or not game or not loader or not java.isdecimal() or int(java) < 1:
        raise CorpusError(f"invalid pick: {pick!r}")
    query = urlencode({"game_versions": json.dumps([game], separators=(",", ":")),
                       "loaders": json.dumps([loader], separators=(",", ":")), "include_changelog": "false"})
    project = _api_json(f"{API}/project/{slug}")
    if not isinstance(project, dict) or not isinstance(project.get("id"), str):
        raise CorpusError(f"invalid project metadata for {slug}")
    versions = _api_json(f"{API}/project/{project['id']}/version?{query}")
    if not isinstance(versions, list):
        raise CorpusError(f"invalid versions response for {slug}")
    matches = []
    for version in versions:
        if not isinstance(version, dict):
            continue
        games, loaders = version.get("game_versions"), version.get("loaders")
        if isinstance(games, list) and isinstance(loaders, list) and game in games and loader in loaders:
            matches.append(version)
    if not matches:
        raise CorpusError(f"no Modrinth version matches {slug}:{game}:{loader}")
    selected = max(matches, key=lambda item: (_parse_published(item.get("date_published")), str(item.get("id", ""))))
    if not isinstance(selected.get("id"), str) or not isinstance(selected.get("version_number"), str):
        raise CorpusError(f"invalid version metadata for {slug}")
    file = _choose_file(selected)
    hashes = file.get("hashes")
    if not isinstance(hashes, dict) or not isinstance(hashes.get("sha512"), str) or not HEX512.fullmatch(hashes["sha512"]):
        raise CorpusError(f"selected JAR for {slug} has no valid SHA-512")
    size = file.get("size")
    if isinstance(size, bool) or not isinstance(size, int) or size < 1:
        raise CorpusError(f"selected JAR for {slug} has invalid size")
    row = {
        "project": slug,
        "project_id": project["id"],
        "version_id": selected["id"],
        "version_number": selected["version_number"],
        "game_version": game,
        "loader": loader,
        "target_java": int(java),
        "filename": file.get("filename"),
        "url": file.get("url"),
        "sha512": hashes["sha512"].lower(),
        "size": size,
        "license": _license(project),
        "project_url": f"https://modrinth.com/mod/{slug}",
        "source_url": project.get("source_url") or "",
    }
    validate_artifact(row)
    return row


def _safe_filename(value):
    return (isinstance(value, str) and value not in {"", ".", ".."}
            and Path(value).name == value and "/" not in value and "\\" not in value
            and "\x00" not in value and value.lower().endswith(".jar"))


def _valid_https_url(value, host):
    if not isinstance(value, str):
        return False
    try:
        parsed = urlsplit(value)
        return (parsed.scheme == "https" and parsed.hostname == host and parsed.port in (None, 443)
                and not parsed.username and not parsed.password and not parsed.fragment)
    except ValueError:
        return False


def _decoded_path_segments(path):
    raw_segments = path.split("/")
    if any(re.search(r"%(?![0-9a-fA-F]{2})", segment) for segment in raw_segments):
        raise CorpusError("artifact URL has malformed percent encoding")
    decoded = []
    for segment in raw_segments:
        try:
            value = unquote(segment, errors="strict")
        except UnicodeDecodeError as exc:
            raise CorpusError("artifact URL has invalid UTF-8 path encoding") from exc
        # Reject traversal and encoded separators even when repeated decoding is needed.
        for _ in range(3):
            if value in {".", ".."} or "/" in value or "\\" in value:
                raise CorpusError("artifact URL contains a path separator or traversal segment")
            try:
                next_value = unquote(value, errors="strict")
            except UnicodeDecodeError as exc:
                raise CorpusError("artifact URL has invalid UTF-8 path encoding") from exc
            if next_value == value:
                break
            value = next_value
        decoded.append(value)
    return decoded


def validate_artifact(row):
    if not isinstance(row, dict) or set(row) != ARTIFACT_FIELDS:
        raise CorpusError("artifact row has missing or unexpected fields")
    for field in ("project", "project_id", "version_id", "version_number", "game_version", "loader"):
        if not isinstance(row[field], str) or not row[field]:
            raise CorpusError(f"artifact field {field} must be a nonempty string")
    if not SLUG.fullmatch(row["project"]):
        raise CorpusError("unsafe project slug")
    for field in ("project_id", "version_id"):
        if not REMOTE_ID.fullmatch(row[field]):
            raise CorpusError(f"unsafe {field}")
    if isinstance(row["target_java"], bool) or not isinstance(row["target_java"], int) or row["target_java"] < 1:
        raise CorpusError("target_java must be a positive integer")
    if not _safe_filename(row["filename"]):
        raise CorpusError("unsafe JAR filename")
    if not isinstance(row["sha512"], str) or not HEX512.fullmatch(row["sha512"]):
        raise CorpusError("sha512 must be 128 lowercase hexadecimal characters")
    if isinstance(row["size"], bool) or not isinstance(row["size"], int) or row["size"] < 1:
        raise CorpusError("size must be a positive integer")
    if not isinstance(row["license"], str):
        raise CorpusError("license must be a string")
    if row["project_url"] != f"https://modrinth.com/mod/{row['project']}":
        raise CorpusError("project_url does not match the Modrinth project slug")
    if not isinstance(row["source_url"], str):
        raise CorpusError("source_url must be a string")
    if row["source_url"]:
        try:
            source = urlsplit(row["source_url"])
            valid_source = (source.scheme == "https" and bool(source.hostname) and source.port in (None, 443)
                            and not source.username and not source.password)
        except ValueError:
            valid_source = False
        if not valid_source:
            raise CorpusError("source_url must be an HTTPS URL without credentials")
    if not _valid_https_url(row["url"], CDN_HOST):
        raise CorpusError("artifact URL must use HTTPS on cdn.modrinth.com")
    parsed = urlsplit(row["url"])
    segments = _decoded_path_segments(parsed.path)
    expected_prefix = ["", "data", row["project_id"], "versions"]
    if (len(segments) != 6 or segments[:4] != expected_prefix or not segments[4]
            or segments[4] in {".", ".."} or segments[5] != row["filename"] or parsed.query):
        raise CorpusError("artifact URL has an unexpected Modrinth CDN path")


def load_lock(path):
    try:
        raw = path.read_bytes()
    except OSError as exc:
        raise CorpusError(f"cannot read lock {path}: {exc}") from exc
    data = _json_bytes(raw, str(path))
    if (not isinstance(data, dict) or set(data) != {"schema", "artifacts"}
            or isinstance(data.get("schema"), bool) or data.get("schema") != 1):
        raise CorpusError("lock must be schema 1 with only schema and artifacts")
    if not isinstance(data["artifacts"], list) or not data["artifacts"]:
        raise CorpusError("lock artifacts must be a nonempty list")
    for row in data["artifacts"]:
        validate_artifact(row)
    return raw, data


def _write_json_atomic(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(data, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def fetch(lock_path: Path, cache: Path, output: Path, offline=False):
    raw_lock, lock = load_lock(lock_path)
    if lock_path.resolve() == output.resolve():
        raise CorpusError("plan output must not overwrite the lock")
    cache.mkdir(parents=True, exist_ok=True)
    plan_rows = []
    for row in lock["artifacts"]:
        jar_path = cache / f"{row['sha512']}.jar"
        if jar_path.exists():
            _verify_file(jar_path, row)
        else:
            if offline:
                raise CorpusError(f"offline cache miss for {row['project']} ({row['sha512']})")
            temporary = _download_verified(row["url"], cache, row)
            try:
                # Do not replace a same-hash entry that appeared during the download.
                if jar_path.exists():
                    _verify_file(jar_path, row)
                    temporary.unlink()
                else:
                    os.replace(temporary, jar_path)
            except Exception:
                try:
                    temporary.unlink()
                except OSError:
                    pass
                raise
        plan_rows.append({**row, "path": str(jar_path.resolve())})
    plan = {"schema": 1, "lock_sha256": hashlib.sha256(raw_lock).hexdigest(), "artifacts": plan_rows}
    _write_json_atomic(output, plan)


def _verify_file(path: Path, row):
    digest = hashlib.sha512()
    size = 0
    try:
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                size += len(chunk)
                digest.update(chunk)
    except OSError as exc:
        raise CorpusError(f"cannot read cached JAR {path}: {exc}") from exc
    if size != row["size"] or digest.hexdigest() != row["sha512"]:
        raise CorpusError(f"corrupted cached JAR: {path}")


def resolve(picks, output: Path):
    if not picks:
        raise CorpusError("resolve requires at least one --pick")
    rows = [resolve_pick(pick) for pick in picks]
    rows.sort(key=lambda row: (row["project"], row["game_version"], row["loader"], row["target_java"]))
    _write_json_atomic(output, {"schema": 1, "artifacts": rows})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    resolve_parser = subparsers.add_parser("resolve", help="resolve exact Modrinth picks into a lock")
    resolve_parser.add_argument("--output", required=True, type=Path)
    resolve_parser.add_argument("--pick", action="append", default=[], metavar="SLUG:GAME:LOADER:JAVA")
    fetch_parser = subparsers.add_parser("fetch", help="verify or download locked JARs and write a plan")
    fetch_parser.add_argument("--lock", required=True, type=Path)
    fetch_parser.add_argument("--cache", required=True, type=Path)
    fetch_parser.add_argument("--output", required=True, type=Path)
    fetch_parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.command == "resolve":
            resolve(args.pick, args.output)
        else:
            fetch(args.lock, args.cache, args.output, args.offline)
    except CorpusError as exc:
        print(f"behavior_corpus: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
