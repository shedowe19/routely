#!/usr/bin/env python3
"""Fail-closed release planning and create-only publication; Python stdlib only.

No release operation runs at import time. GITHUB_TOKEN is read only from the
environment and is never included in errors. API redirects are disabled.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

MAX_CODE = 2_100_000_000
MAX_JSON = 4 * 1024 * 1024
MAX_METADATA = 16 * 1024
MAX_APK = 200 * 1024 * 1024
ASSET_NAME = "release-version.json"
PLAN_KEYS = {"schema_version", "repository", "tag_name", "version_name", "version_code", "commit_sha"}
METADATA_KEYS = PLAN_KEYS | {"apk_name", "apk_sha256", "apk_size"}
VERSION_RE = re.compile(r"[0-9A-Za-z][0-9A-Za-z._-]{0,63}\Z")
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
REPO_RE = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")


class GuardError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise GuardError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "JSON contains duplicate fields.")
        result[key] = value
    return result


def decode_json(data):
    try:
        return json.loads(data.decode("utf-8", errors="strict"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError, RecursionError):
        raise GuardError("GitHub returned invalid UTF-8 JSON.") from None


def positive_int(value):
    return type(value) is int and value > 0


def version_name(value):
    require(isinstance(value, str) and VERSION_RE.fullmatch(value) is not None,
            "Version name must contain 1–64 letters, digits, dots, underscores or hyphens.")
    require(".." not in value and not value.endswith((".", ".lock")), "Version name cannot form an invalid Git tag.")
    return value


def version_code(value):
    if isinstance(value, str):
        require(re.fullmatch(r"[1-9][0-9]{0,9}", value) is not None, "Version code must be a positive decimal integer.")
        value = int(value)
    require(positive_int(value) and value <= MAX_CODE, "Version code exceeds the Android version-code limit.")
    return value


def repository(value):
    require(isinstance(value, str) and REPO_RE.fullmatch(value) is not None, "Invalid repository identity.")
    require(all(part not in (".", "..") for part in value.split("/")), "Invalid repository identity.")
    return value


def commit_sha(value):
    require(isinstance(value, str) and SHA_RE.fullmatch(value) is not None, "Invalid full commit SHA.")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Github:
    def __init__(self, repo, token, opener=None):
        self.repo = repository(repo)
        require(isinstance(token, str) and token and "\n" not in token and "\r" not in token,
                "GitHub authentication is required.")
        self._token = token
        self._opener = opener or urllib.request.build_opener(NoRedirect())

    def _request(self, url, method="GET", data=None, content_type=None, limit=MAX_JSON,
                 optional=False, asset_redirect=False, authenticated=True):
        try:
            parsed = urllib.parse.urlsplit(url)
            port = parsed.port
        except ValueError:
            raise GuardError("Unsafe GitHub request origin.") from None
        hosts = {"api.github.com", "uploads.github.com"} if authenticated else {"release-assets.githubusercontent.com", "objects.githubusercontent.com"}
        require(parsed.scheme == "https" and parsed.hostname in hosts and port in (None, 443)
                and not parsed.username and not parsed.password and not parsed.fragment, "Unsafe GitHub request origin.")
        headers = {"Accept": "application/octet-stream" if asset_redirect else "application/vnd.github+json",
                   "User-Agent": "routely-release-guard", "X-GitHub-Api-Version": "2022-11-28"}
        if authenticated:
            headers["Authorization"] = "Bearer " + self._token
        if content_type:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self._opener.open(req, timeout=30) as response:
                raw = response.read(limit + 1)
                require(len(raw) <= limit, "GitHub response exceeds the bounded size limit.")
                return raw
        except urllib.error.HTTPError as error:
            status = error.code
            location = error.headers.get("Location", "")
            error.close()
            if status == 404 and optional:
                return None
            if asset_redirect and status in (301, 302, 303, 307, 308):
                # Never forward authentication to a release-asset CDN, nor log its signed URL.
                return self._request(location, limit=limit, authenticated=False)
            raise GuardError(f"GitHub request failed (HTTP {status}); release authorization stopped.") from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise GuardError("GitHub request failed; release authorization stopped.") from None

    def json(self, method, path, payload=None, optional=False):
        require(path.startswith("/") and not path.startswith("//"), "Invalid GitHub API path.")
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        raw = self._request("https://api.github.com" + path, method, data,
                            "application/json" if data is not None else None, optional=optional)
        return None if raw is None else decode_json(raw)

    def asset(self, asset_id):
        require(positive_int(asset_id), "Invalid release asset identity.")
        raw = self._request(f"https://api.github.com/repos/{self.repo}/releases/assets/{asset_id}",
                            limit=MAX_METADATA, asset_redirect=True)
        return decode_json(raw)

    def upload(self, release_id, name, data, content_type):
        require(positive_int(release_id), "Invalid reserved release identity.")
        require(name == ASSET_NAME or re.fullmatch(r"routely-v[0-9A-Za-z._-]+\.apk", name), "Invalid asset name.")
        require(0 < len(data) <= (MAX_METADATA if name == ASSET_NAME else MAX_APK), "Invalid asset size.")
        url = f"https://uploads.github.com/repos/{self.repo}/releases/{release_id}/assets?name=" + urllib.parse.quote(name, safe="")
        # GitHub rejects duplicate asset names (422). No delete, patch or overwrite path exists.
        return decode_json(self._request(url, "POST", data, content_type))


def bootstrap(path, repo):
    data = decode_json(Path(path).read_bytes())
    require(isinstance(data, dict) and type(data.get("schema_version")) is int and data["schema_version"] == 1 and data.get("repository") == repo,
            "Version floor does not belong to this repository/schema.")
    floor = version_code(data.get("legacy_max_version_code"))
    legacy = data.get("legacy_releases")
    require(isinstance(legacy, dict) and legacy and all(isinstance(tag, str) and positive_int(rid) for tag, rid in legacy.items())
            and len(set(legacy.values())) == len(legacy), "Invalid legacy release identities.")
    return floor, legacy


def plan(repo, name, code, sha):
    return {"schema_version": 1, "repository": repository(repo), "tag_name": "v" + version_name(name),
            "version_name": name, "version_code": version_code(code), "commit_sha": commit_sha(sha)}


def validate_metadata(data, repo, tag):
    require(isinstance(data, dict) and set(data) == METADATA_KEYS, "Invalid release metadata schema.")
    expected = plan(repo, data.get("version_name"), data.get("version_code"), data.get("commit_sha"))
    require(all(data[key] == value and type(data[key]) is type(value) for key, value in expected.items())
            and data["tag_name"] == tag, "Release metadata identity does not match its release.")
    require(data["apk_name"] == f"routely-v{data['version_name']}.apk"
            and isinstance(data["apk_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", data["apk_sha256"])
            and positive_int(data["apk_size"]) and data["apk_size"] <= MAX_APK, "Invalid APK metadata.")
    return data


def ref_commit(client, tag, optional=False):
    ref = client.json("GET", f"/repos/{client.repo}/git/ref/tags/" + urllib.parse.quote(tag, safe=""), optional=optional)
    if ref is None:
        return None
    require(isinstance(ref, dict) and ref.get("ref") == "refs/tags/" + tag, "Ambiguous tag identity.")
    obj = ref.get("object")
    seen = set()
    for _ in range(8):
        require(isinstance(obj, dict), "Malformed Git tag.")
        sha = commit_sha(obj.get("sha"))
        require(sha not in seen, "Cyclic annotated tag.")
        seen.add(sha)
        if obj.get("type") == "commit":
            return sha
        require(obj.get("type") == "tag", "Tag does not point to a commit.")
        annotated = client.json("GET", f"/repos/{client.repo}/git/tags/{sha}")
        require(isinstance(annotated, dict) and annotated.get("sha") == sha, "Invalid annotated tag identity.")
        obj = annotated.get("object")
    raise GuardError("Annotated tag exceeds the bounded resolution depth.")


def assert_absent(client, tag):
    require(ref_commit(client, tag, optional=True) is None, "Version tag already exists; choose a new version name.")
    existing = client.json("GET", f"/repos/{client.repo}/releases/tags/" + urllib.parse.quote(tag, safe=""), optional=True)
    require(existing is None, "Version release already exists; choose a new version name.")


def release_assets(release):
    assets = release.get("assets")
    require(isinstance(assets, list) and len(assets) <= 100, "Invalid or excessive release assets.")
    ids, names = set(), set()
    for asset in assets:
        require(isinstance(asset, dict) and positive_int(asset.get("id")) and asset["id"] not in ids
                and isinstance(asset.get("name"), str) and asset["name"] not in names and positive_int(asset.get("size"))
                and asset.get("state") == "uploaded", "Invalid or ambiguous release asset.")
        ids.add(asset["id"])
        names.add(asset["name"])
    return assets


def check_metadata_assets(client, release, expected=None):
    assets = release_assets(release)
    metadata_assets = [asset for asset in assets if asset["name"] == ASSET_NAME]
    require(len(metadata_assets) == 1 and metadata_assets[0]["size"] <= MAX_METADATA, "Published release lacks unique bounded version metadata.")
    metadata = validate_metadata(client.asset(metadata_assets[0]["id"]), client.repo, release["tag_name"])
    if expected is not None:
        require(metadata == expected, "Published metadata does not match the built APK/commit.")
    apks = [asset for asset in assets if asset["name"] == metadata["apk_name"]]
    require(len(apks) == 1 and apks[0]["size"] == metadata["apk_size"], "Published APK does not match its version metadata.")
    digest = apks[0].get("digest")
    require(digest == "sha256:" + metadata["apk_sha256"], "Published APK digest is missing or differs from its version metadata.")
    require(ref_commit(client, release["tag_name"]) == metadata["commit_sha"], "Published tag differs from the built commit.")
    return metadata


def released_floor(client, floor, legacy):
    legacy_floor = floor
    seen_ids, seen_tags, seen_codes = set(), set(), set()
    for page in range(1, 21):
        releases = client.json("GET", f"/repos/{client.repo}/releases?per_page=100&page={page}")
        require(isinstance(releases, list) and len(releases) <= 100, "Invalid release list response.")
        for release in releases:
            require(isinstance(release, dict) and positive_int(release.get("id")) and isinstance(release.get("tag_name"), str)
                    and type(release.get("draft")) is bool and release["id"] not in seen_ids
                    and release["tag_name"] not in seen_tags, "Ambiguous release history; no code may be selected.")
            seen_ids.add(release["id"])
            seen_tags.add(release["tag_name"])
            if release["draft"]:
                continue
            known_legacy = legacy.get(release["tag_name"]) == release["id"]
            if known_legacy:
                assets = release.get("assets")
                require(isinstance(assets, list) and all(isinstance(asset, dict) for asset in assets), "Invalid legacy release assets.")
                if not any(asset.get("name") == ASSET_NAME for asset in assets):
                    continue
            metadata = check_metadata_assets(client, release)
            code = metadata["version_code"]
            if not known_legacy or code > legacy_floor:
                require(code not in seen_codes and code > legacy_floor, "Published version-code history is not strictly above the legacy floor/unique.")
                seen_codes.add(code)
            floor = max(floor, code)
        if len(releases) < 100:
            return floor
    raise GuardError("Release history exceeds the bounded pagination limit.")


def choose_code(requested, floor):
    code = version_code(requested) if requested else version_code(floor + 1)
    require(code > floor, "Version code must exceed every published code and the durable legacy floor.")
    return code


def preflight(client, floor_path, name, requested, sha):
    version_name(name)
    commit_sha(sha)
    if requested:
        version_code(requested)
    repo = client.json("GET", f"/repos/{client.repo}")
    require(isinstance(repo, dict) and repo.get("full_name") == client.repo, "GitHub repository identity is unavailable.")
    assert_absent(client, "v" + name)
    floor, legacy = bootstrap(floor_path, client.repo)
    return plan(client.repo, name, choose_code(requested, released_floor(client, floor, legacy)), sha)


def apk_metadata(release_plan, apk_path, aapt):
    apk = Path(apk_path)
    require(apk.is_file() and 0 < apk.stat().st_size <= MAX_APK, "Signed APK is missing or exceeds the size limit.")
    try:
        result = subprocess.run([aapt, "dump", "badging", str(apk)], check=True, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        raise GuardError("Cannot validate the signed APK manifest.") from None
    packages = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", result.stdout, re.MULTILINE)
    require(packages == [("de.traewelling.app", str(release_plan["version_code"]), release_plan["version_name"])],
            "Signed APK package/version differs from the selected release.")
    with apk.open("rb") as source:
        data = source.read(MAX_APK + 1)
    require(0 < len(data) <= MAX_APK, "Signed APK changed or exceeds the size limit.")
    metadata = dict(release_plan, apk_name=apk.name, apk_sha256=hashlib.sha256(data).hexdigest(), apk_size=len(data))
    validate_metadata(metadata, release_plan["repository"], release_plan["tag_name"])
    return metadata


def assert_reserved(client, release_id, metadata, draft):
    require(ref_commit(client, metadata["tag_name"]) == metadata["commit_sha"], "Reserved tag differs from the built commit.")
    release = client.json("GET", f"/repos/{client.repo}/releases/{release_id}")
    require(isinstance(release, dict) and positive_int(release.get("id")) and release["id"] == release_id and release.get("tag_name") == metadata["tag_name"]
            and release.get("draft") is draft and release.get("prerelease") is False,
            "Reserved release identity/state changed; publication stopped.")
    return release


def reserve(client, floor_path, metadata, body):
    validate_metadata(metadata, client.repo, metadata.get("tag_name"))
    selected = preflight(client, floor_path, metadata["version_name"], str(metadata["version_code"]), metadata["commit_sha"])
    require(all(metadata[key] == value for key, value in selected.items()), "Selected version changed during the build.")
    # create-ref and create-release reject existing identities atomically (422).
    created = client.json("POST", f"/repos/{client.repo}/git/refs", {"ref": "refs/tags/" + metadata["tag_name"], "sha": metadata["commit_sha"]})
    require(isinstance(created, dict) and created.get("ref") == "refs/tags/" + metadata["tag_name"], "Tag reservation failed.")
    require(ref_commit(client, metadata["tag_name"]) == metadata["commit_sha"], "Tag reservation has the wrong commit.")
    release = client.json("POST", f"/repos/{client.repo}/releases", {
        "tag_name": metadata["tag_name"], "target_commitish": metadata["commit_sha"], "name": "Release " + metadata["tag_name"],
        "body": body, "draft": True, "prerelease": False, "generate_release_notes": False})
    require(isinstance(release, dict) and positive_int(release.get("id")), "Draft reservation failed.")
    own = assert_reserved(client, release["id"], metadata, True)
    require(release_assets(own) == [], "Reserved draft contains foreign assets.")
    return release["id"]


def assert_new_code(client, floor_path, metadata):
    floor, legacy = bootstrap(floor_path, client.repo)
    choose_code(str(metadata["version_code"]), released_floor(client, floor, legacy))


def publish(client, release_id, metadata, apk_path, floor_path):
    validate_metadata(metadata, client.repo, metadata.get("tag_name"))
    require(positive_int(release_id), "Invalid reserved release identity.")
    own = assert_reserved(client, release_id, metadata, True)
    require(release_assets(own) == [], "Reserved draft already contains assets; no overwrite is permitted.")
    assert_new_code(client, floor_path, metadata)
    with Path(apk_path).open("rb") as source:
        apk = source.read(MAX_APK + 1)
    require(len(apk) == metadata["apk_size"] and hashlib.sha256(apk).hexdigest() == metadata["apk_sha256"], "Signed APK changed after validation.")
    # Both uploads are create-only. A partial failed publication remains a draft
    # and is never retried by overwriting/removing assets or changing its tag.
    client.upload(release_id, metadata["apk_name"], apk, "application/vnd.android.package-archive")
    client.upload(release_id, ASSET_NAME, (json.dumps(metadata, sort_keys=True, indent=2) + "\n").encode("utf-8"), "application/json")
    own = assert_reserved(client, release_id, metadata, True)
    require(len(release_assets(own)) == 2, "Reserved draft contains unexpected assets.")
    check_metadata_assets(client, own, metadata)
    assert_new_code(client, floor_path, metadata)
    client.json("PATCH", f"/repos/{client.repo}/releases/{release_id}", {"draft": False, "target_commitish": metadata["commit_sha"]})
    final = assert_reserved(client, release_id, metadata, False)
    check_metadata_assets(client, final, metadata)


def write_json(path, value):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("preflight", "reserve", "publish"))
    parser.add_argument("--floor", default=".github/release-version-floor.json")
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--apk")
    parser.add_argument("--aapt")
    parser.add_argument("--body-file")
    parser.add_argument("--release-id", type=int)
    args = parser.parse_args()
    client = Github(os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_TOKEN", ""))
    name, code, sha = os.environ.get("RELEASE_VERSION_NAME", ""), os.environ.get("RELEASE_VERSION_CODE", ""), os.environ.get("GITHUB_SHA", "")
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Publication is allowed only for manual release dispatches.")
    head = subprocess.run(["git", "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
    require(head == commit_sha(sha), "Checkout differs from the dispatch commit.")
    if args.command == "preflight":
        selected = preflight(client, args.floor, name, code, sha)
        write_json(args.metadata, selected)
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"RELEASE_VERSION_NAME={selected['version_name']}\nRELEASE_VERSION_CODE={selected['version_code']}\n")
        print(f"Release version validated: {selected['tag_name']}, code {selected['version_code']}.")
    elif args.command == "reserve":
        selected = decode_json(Path(args.metadata).read_bytes())
        require(isinstance(selected, dict) and set(selected) == PLAN_KEYS and selected == plan(client.repo, name, code, sha), "Build release plan changed.")
        require(args.apk and args.aapt and args.body_file, "Signed APK validation arguments are required.")
        metadata = apk_metadata(selected, args.apk, args.aapt)
        body = Path(args.body_file).read_text(encoding="utf-8")
        require(len(body.encode("utf-8")) <= 64 * 1024, "Release notes exceed the bounded size limit.")
        rid = reserve(client, args.floor, metadata, body)
        write_json(args.metadata, metadata)
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"release_id={rid}\n")
        print("Commit-bound tag and private draft reserved; no release published yet.")
    else:
        require(args.apk and args.release_id, "Publication requires its reserved draft and signed APK.")
        metadata = decode_json(Path(args.metadata).read_bytes())
        require(isinstance(metadata, dict) and all(metadata.get(key) == value for key, value in plan(client.repo, name, code, sha).items()), "Release plan changed before publication.")
        publish(client, args.release_id, metadata, args.apk, args.floor)
        print("Release published with verified commit, APK and version metadata.")


if __name__ == "__main__":
    try:
        main()
    except (GuardError, OSError, subprocess.SubprocessError, KeyError) as error:
        # Never print raw network exceptions, response bodies, environment or tokens.
        message = str(error) if isinstance(error, GuardError) else "Release guard could not complete safely."
        print("Release guard: " + message, file=sys.stderr)
        sys.exit(1)
