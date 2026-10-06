"""Deterministic offline release/security regression tests. No network/signing."""
import copy
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.parse
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "release_guard.py"
SPEC = importlib.util.spec_from_file_location("release_guard", SCRIPT)
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)

REPO = "shedowe19/routely"
SHA = "a" * 40
OTHER_SHA = "b" * 40
APK = b"deterministic signed-apk stand-in; never an actual signed binary"


def metadata(name="1.9.0", code=14, sha=SHA):
    return dict(guard.plan(REPO, name, code, sha), apk_name=f"routely-v{name}.apk",
                apk_sha256=hashlib.sha256(APK).hexdigest(), apk_size=len(APK))


class FakeGithub:
    def __init__(self):
        self.repo = REPO
        self.releases = [{"id": 101, "tag_name": "v1.8.7", "draft": False, "prerelease": False, "assets": []}]
        self.refs = {"v1.8.7": {"type": "commit", "sha": OTHER_SHA}}
        self.annotated = {}
        self.asset_values = {}
        self.calls = []
        self.pages = None
        self.fail = None

    def json(self, method, path, payload=None, optional=False):
        self.calls.append((method, path, copy.deepcopy(payload)))
        if self.fail:
            self.fail(method, path, payload)
        if path == f"/repos/{REPO}" and method == "GET":
            return {"full_name": REPO}
        if "/releases?" in path:
            page = int(urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)["page"][0])
            return copy.deepcopy(self.pages.get(page, []) if self.pages is not None else self.releases[(page - 1) * 100:page * 100])
        if "/git/ref/tags/" in path:
            tag = urllib.parse.unquote(path.split("/git/ref/tags/", 1)[1])
            obj = self.refs.get(tag)
            if obj is None:
                if optional:
                    return None
                raise guard.GuardError("missing tag")
            return {"ref": "refs/tags/" + tag, "object": copy.deepcopy(obj)}
        if "/git/tags/" in path:
            sha = path.rsplit("/", 1)[1]
            return {"sha": sha, "object": copy.deepcopy(self.annotated[sha])}
        if "/releases/tags/" in path:
            tag = urllib.parse.unquote(path.split("/releases/tags/", 1)[1])
            found = [item for item in self.releases if item["tag_name"] == tag]
            if not found and optional:
                return None
            if len(found) != 1:
                raise guard.GuardError("release lookup failed")
            return copy.deepcopy(found[0])
        if path.endswith("/git/refs") and method == "POST":
            tag = payload["ref"].removeprefix("refs/tags/")
            if tag in self.refs:
                raise guard.GuardError("HTTP 422 tag exists")
            self.refs[tag] = {"type": "commit", "sha": payload["sha"]}
            return {"ref": payload["ref"], "object": self.refs[tag]}
        if path.endswith("/releases") and method == "POST":
            if any(item["tag_name"] == payload["tag_name"] for item in self.releases):
                raise guard.GuardError("HTTP 422 release exists")
            created = dict(payload, id=2000, assets=[])
            self.releases.insert(0, created)
            return copy.deepcopy(created)
        if "/releases/" in path:
            rid = int(path.rsplit("/", 1)[1])
            found = next(item for item in self.releases if item["id"] == rid)
            if method == "PATCH":
                found.update(payload)
            return copy.deepcopy(found)
        raise AssertionError((method, path, payload))

    def asset(self, aid):
        self.calls.append(("ASSET", aid, None))
        return copy.deepcopy(self.asset_values[aid])

    def upload(self, rid, name, data, content_type):
        self.calls.append(("UPLOAD", rid, name))
        if self.fail:
            self.fail("UPLOAD", str(rid), name)
        release = next(item for item in self.releases if item["id"] == rid)
        if any(asset["name"] == name for asset in release["assets"]):
            raise guard.GuardError("HTTP 422 asset exists")
        aid = 3000 + len(self.asset_values) + sum(len(item["assets"]) for item in self.releases)
        asset = {"id": aid, "name": name, "size": len(data), "state": "uploaded", "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
        release["assets"].append(asset)
        if name == guard.ASSET_NAME:
            self.asset_values[aid] = json.loads(data)
        return asset

    def published(self, value, rid=102):
        aid = rid * 10
        self.asset_values[aid] = copy.deepcopy(value)
        release = {"id": rid, "tag_name": value["tag_name"], "draft": False, "prerelease": False,
                   "assets": [{"id": aid, "name": guard.ASSET_NAME, "size": 512, "state": "uploaded"},
                              {"id": aid + 1, "name": value["apk_name"], "size": value["apk_size"], "state": "uploaded",
                               "digest": "sha256:" + value["apk_sha256"]}]}
        self.refs[value["tag_name"]] = {"type": "commit", "sha": value["commit_sha"]}
        self.releases.insert(0, release)
        return release


class ReleaseGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.floor = Path(self.temp.name) / "floor.json"
        self.floor.write_text(json.dumps({"schema_version": 1, "repository": REPO, "legacy_max_version_code": 13,
                                         "legacy_releases": {"v1.8.7": 101}}))
        self.apk = Path(self.temp.name) / "routely-v1.9.0.apk"
        self.apk.write_bytes(APK)
        self.client = FakeGithub()

    def preflight(self, name="1.9.0", code=""):
        return guard.preflight(self.client, self.floor, name, code, SHA)

    def writes(self):
        return [call for call in self.client.calls if call[0] in ("POST", "PATCH", "UPLOAD", "DELETE")]

    def test_legacy_bootstrap_auto_selects_14_never_one(self):
        self.assertEqual(self.preflight()["version_code"], 14)
        self.assertEqual(self.writes(), [])

    def test_explicit_code_at_or_below_released_floor_rejected(self):
        for code in ("1", "12", "13"):
            with self.subTest(code=code), self.assertRaises(guard.GuardError):
                self.preflight(code=code)
        self.assertEqual(self.writes(), [])

    def test_published_assets_drive_next_code_independent_of_list_order(self):
        self.client.published(metadata("2.0.0", 20), 104)
        self.client.published(metadata("1.9.5", 18), 103)
        self.assertEqual(self.preflight()["version_code"], 21)
        with self.assertRaises(guard.GuardError):
            self.preflight(code="20")

    def test_pagination_is_not_limited_to_first_page(self):
        first = [dict(id=10000 + index, tag_name=f"vdraft-{index}", draft=True) for index in range(100)]
        value = self.client.published(metadata("2.0.0", 22))
        self.client.pages = {1: first, 2: [value]}
        self.assertEqual(self.preflight()["version_code"], 23)

    def test_nonlegacy_missing_metadata_fails_closed(self):
        self.client.releases.append(dict(id=105, tag_name="v1.9.1", draft=False, assets=[]))
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_recreated_legacy_release_is_not_bootstrapped_by_name(self):
        self.client.releases[0]["id"] = 777
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_metadata_added_to_known_legacy_release_is_still_counted(self):
        self.client.releases.clear()
        self.client.published(metadata("1.8.7", 30), 101)
        self.assertEqual(self.preflight()["version_code"], 31)

    def test_existing_tag_rejected_even_without_release(self):
        self.client.refs["v1.9.0"] = {"type": "commit", "sha": SHA}
        with self.assertRaises(guard.GuardError):
            self.preflight()
        self.assertEqual(self.writes(), [])

    def test_existing_draft_release_rejected_even_without_tag(self):
        self.client.releases.append(dict(id=500, tag_name="v1.9.0", draft=True, assets=[]))
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_inputs_reject_payloads_before_any_api_read(self):
        for name in ("x\nRELEASE_VERSION_CODE=1", "$(echo secret)", "1.9/../0", "1..9", "1.9.lock", "1.9.", "a" * 65):
            with self.subTest(name=name), self.assertRaises(guard.GuardError):
                self.preflight(name=name)
        for code in ("01", "-1", "0", "1;echo secret", "2100000001", "14\nX=1"):
            with self.subTest(code=code), self.assertRaises(guard.GuardError):
                self.preflight(code=code)
        self.assertEqual(self.client.calls, [])

    def test_maximum_code_cannot_wrap_or_reset(self):
        self.client.published(metadata("2.0.0", guard.MAX_CODE))
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_api_failures_never_authorize_a_release(self):
        self.client.fail = lambda *_: (_ for _ in ()).throw(guard.GuardError("HTTP 403"))
        with self.assertRaises(guard.GuardError):
            self.preflight()
        self.assertEqual(self.writes(), [])

    def test_duplicate_release_identity_is_ambiguous(self):
        self.client.releases.append(copy.deepcopy(self.client.releases[0]))
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_duplicate_published_version_codes_fail_closed(self):
        self.client.published(metadata("1.9.1", 14), 103)
        self.client.published(metadata("1.9.2", 14), 104)
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_metadata_with_wrong_identity_invalid_types_or_extra_fields_rejected(self):
        for field, bad in (("repository", "other/repo"), ("tag_name", "v3.0"), ("schema_version", True),
                           ("version_code", True), ("commit_sha", "main"), ("apk_size", False), ("apk_name", "../file.apk")):
            changed = metadata()
            changed[field] = bad
            with self.subTest(field=field), self.assertRaises(guard.GuardError):
                guard.validate_metadata(changed, REPO, "v1.9.0")
        changed = metadata()
        changed["token"] = "never accepted"
        with self.assertRaises(guard.GuardError):
            guard.validate_metadata(changed, REPO, "v1.9.0")

    def test_duplicate_json_fields_and_invalid_utf8_rejected(self):
        for raw in (b'{"version_code":14,"version_code":1}', b'"\xff"'):
            with self.assertRaises(guard.GuardError):
                guard.decode_json(raw)

    def test_published_metadata_must_match_tag_and_apk_digest(self):
        release = self.client.published(metadata())
        self.client.refs["v1.9.0"]["sha"] = OTHER_SHA
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")
        self.client.refs["v1.9.0"]["sha"] = SHA
        release["assets"][1]["digest"] = "sha256:" + "f" * 64
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")

    def test_published_metadata_without_github_apk_digest_fails_closed(self):
        release = self.client.published(metadata())
        del release["assets"][1]["digest"]
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")

    def test_missing_or_wrong_remote_digest_never_publishes_draft(self):
        for digest in (None, "sha256:" + "f" * 64):
            with self.subTest(digest=digest):
                self.client = FakeGithub()
                rid = guard.reserve(self.client, self.floor, metadata(), "notes")
                original_upload = self.client.upload
                def upload(*args):
                    result = original_upload(*args)
                    if args[1].endswith(".apk"):
                        result["digest"] = digest
                    return result
                self.client.upload = upload
                with self.assertRaises(guard.GuardError):
                    guard.publish(self.client, rid, metadata(), self.apk, self.floor)
                self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))
    def test_annotated_tags_resolve_and_cycles_are_rejected(self):
        self.client.refs["vloop"] = {"type": "tag", "sha": OTHER_SHA}
        self.client.annotated[OTHER_SHA] = {"type": "commit", "sha": SHA}
        self.assertEqual(guard.ref_commit(self.client, "vloop"), SHA)
        self.client.annotated[OTHER_SHA] = {"type": "tag", "sha": OTHER_SHA}
        with self.assertRaises(guard.GuardError):
            guard.ref_commit(self.client, "vloop")

    def test_second_check_rejects_code_published_during_build_without_writes(self):
        selected = self.preflight()
        self.client.published(metadata("1.9.1", 14))
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(code=selected["version_code"]), "notes")
        self.assertEqual(self.writes(), [])

    def test_tag_race_is_rejected_atomically_before_draft_mutation(self):
        def race(method, path, payload):
            if method == "POST" and path.endswith("/git/refs"):
                self.client.refs["v1.9.0"] = {"type": "commit", "sha": OTHER_SHA}
        self.client.fail = race
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(), "notes")
        self.assertEqual([call[1] for call in self.writes()], [f"/repos/{REPO}/git/refs"])

    def test_draft_race_does_not_update_existing_release(self):
        def race(method, path, payload):
            if method == "POST" and path.endswith("/releases"):
                self.client.releases.append(dict(id=999, tag_name="v1.9.0", draft=False, assets=[]))
        self.client.fail = race
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(), "notes")
        self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))

    def test_exact_commit_bound_create_only_payload_and_complete_publish(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "literal\nnotes")
        create = next(call[2] for call in self.writes() if call[1] == f"/repos/{REPO}/releases")
        self.assertEqual(create["target_commitish"], SHA)
        self.assertTrue(create["draft"])
        self.assertEqual(create["body"], "literal\nnotes")
        guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        patches = [call for call in self.writes() if call[0] == "PATCH"]
        self.assertEqual(patches, [("PATCH", f"/repos/{REPO}/releases/{rid}", {"draft": False, "target_commitish": SHA})])
        self.assertEqual([call[2] for call in self.writes() if call[0] == "UPLOAD"], ["routely-v1.9.0.apk", guard.ASSET_NAME])
        self.assertEqual(guard.released_floor(self.client, 13, {"v1.8.7": 101}), 14)

    def test_changed_tag_stops_before_asset_upload(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "notes")
        self.client.refs["v1.9.0"]["sha"] = OTHER_SHA
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertEqual(len(self.writes()), before)

    def test_new_published_code_after_reservation_stops_before_upload(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "notes")
        self.client.published(metadata("1.9.1", 15), 106)
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertEqual(len(self.writes()), before)

    def test_new_published_code_during_upload_prevents_final_publish(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "notes")
        original_upload = self.client.upload
        def upload(*args):
            result = original_upload(*args)
            if args[1] == guard.ASSET_NAME:
                self.client.published(metadata("1.9.1", 15), 106)
            return result
        self.client.upload = upload
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))

    def test_foreign_release_or_existing_assets_stop_before_overwrite(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "notes")
        release = next(item for item in self.client.releases if item["id"] == rid)
        release["assets"] = [dict(id=555, name="foreign.apk", size=10, state="uploaded")]
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertEqual(len(self.writes()), before)
        release["assets"] = []
        release["tag_name"] = "vforeign"
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)

    def test_partial_asset_failure_never_publishes_or_retries_overwrites(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "notes")
        def fail(method, path, payload):
            if method == "UPLOAD" and payload == guard.ASSET_NAME:
                raise guard.GuardError("upload failed")
        self.client.fail = fail
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))
        self.assertTrue(next(item for item in self.client.releases if item["id"] == rid)["draft"])
        self.client.fail = None
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertEqual(len(self.writes()), before)

    def test_changed_signed_apk_never_uploads(self):
        rid = guard.reserve(self.client, self.floor, metadata(), "notes")
        self.apk.write_bytes(b"changed APK")
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor)
        self.assertEqual(len(self.writes()), before)

    def test_apk_manifest_version_must_match_selected_plan(self):
        selected = guard.plan(REPO, "1.9.0", 14, SHA)
        correct = "package: name='de.traewelling.app' versionCode='14' versionName='1.9.0' platformBuildVersionName='16'\n"
        with mock.patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=correct)):
            self.assertEqual(guard.apk_metadata(selected, self.apk, "/fake/aapt"), metadata())
        for bad in (correct.replace("'14'", "'1'"), correct.replace("de.traewelling.app", "de.traewelling.app.debug"), correct + correct):
            with mock.patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=bad)), self.assertRaises(guard.GuardError):
                guard.apk_metadata(selected, self.apk, "/fake/aapt")

    def test_invalid_bootstrap_or_other_repository_never_defaults_to_one(self):
        self.floor.write_text('{"schema_version":1,"repository":"other/repo","legacy_max_version_code":13,"legacy_releases":{"v1.8.7":101}}')
        with self.assertRaises(guard.GuardError):
            self.preflight()

    def test_non_dispatch_cli_stops_before_git_or_network(self):
        with mock.patch.dict(os.environ, {"GITHUB_REPOSITORY": REPO, "GITHUB_TOKEN": "offline-token", "GITHUB_EVENT_NAME": "push"}), \
             mock.patch.object(guard.sys, "argv", ["guard", "preflight", "--metadata", "unused.json"]), \
             mock.patch.object(guard.subprocess, "run") as run, self.assertRaises(guard.GuardError):
            guard.main()
        run.assert_not_called()


class Response(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class Opener:
    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        value = self.responses.pop(0)
        if isinstance(value, Exception):
            raise value
        return Response(value)


class TransportProtectionTest(unittest.TestCase):
    def test_https_origins_and_no_credentials_in_errors(self):
        opener = Opener([])
        client = guard.Github(REPO, "do-not-print-token", opener)
        for url in ("http://api.github.com/path", "https://api.github.com.evil.test/path", "https://api.github.com:444/path",
                    "https://user:secret@api.github.com/path", "https://evil.test/path"):
            with self.subTest(url=url), self.assertRaises(guard.GuardError) as error:
                client._request(url)
            self.assertNotIn("do-not-print-token", str(error.exception))
        self.assertEqual(opener.requests, [])

    def test_metadata_redirect_never_forwards_bearer_to_cdn(self):
        redirect = urllib.error.HTTPError("https://api.github.com/asset", 302, "redirect", {"Location": "https://release-assets.githubusercontent.com/public/asset?signature=private-temporary"}, None)
        opener = Opener([redirect, json.dumps(metadata()).encode()])
        client = guard.Github(REPO, "do-not-print-token", opener)
        self.assertEqual(client.asset(333), metadata())
        self.assertEqual(opener.requests[0].get_header("Authorization"), "Bearer do-not-print-token")
        self.assertIsNone(opener.requests[1].get_header("Authorization"))

    def test_unsafe_asset_redirect_is_rejected_without_following(self):
        redirect = urllib.error.HTTPError("https://api.github.com/asset", 302, "redirect", {"Location": "https://evil.test/asset"}, None)
        opener = Opener([redirect])
        with self.assertRaises(guard.GuardError):
            guard.Github(REPO, "do-not-print-token", opener).asset(333)
        self.assertEqual(len(opener.requests), 1)

    def test_regular_api_redirect_is_never_followed(self):
        error = urllib.error.HTTPError("https://api.github.com/api", 302, "redirect", {"Location": "https://api.github.com/new"}, None)
        opener = Opener([error])
        with self.assertRaises(guard.GuardError):
            guard.Github(REPO, "token", opener).json("GET", f"/repos/{REPO}")
        self.assertEqual(len(opener.requests), 1)

    def test_api_404_optional_is_distinct_from_auth_rate_limit_or_server_errors(self):
        for status in (401, 403, 429, 500):
            error = urllib.error.HTTPError("https://api.github.com/api", status, "sensitive upstream detail", {}, None)
            with self.subTest(status=status), self.assertRaises(guard.GuardError) as caught:
                guard.Github(REPO, "do-not-print-token", Opener([error])).json("GET", "/missing", optional=True)
            self.assertNotIn("sensitive upstream detail", str(caught.exception))
            self.assertNotIn("do-not-print-token", str(caught.exception))
        not_found = urllib.error.HTTPError("https://api.github.com/api", 404, "missing", {}, None)
        self.assertIsNone(guard.Github(REPO, "token", Opener([not_found])).json("GET", "/missing", optional=True))

    def test_metadata_download_and_regular_responses_are_bounded(self):
        with self.assertRaises(guard.GuardError):
            guard.Github(REPO, "token", Opener([b"x" * (guard.MAX_METADATA + 1)])).asset(1)
        with self.assertRaises(guard.GuardError):
            guard.Github(REPO, "token", Opener([b"x" * 11]))._request("https://api.github.com/path", limit=10)

    def test_create_only_upload_targets_exact_own_id_with_no_redirect_or_delete(self):
        opener = Opener([b'{"id":123}'])
        client = guard.Github(REPO, "token", opener)
        self.assertEqual(client.upload(99, guard.ASSET_NAME, b"{}", "application/json"), {"id": 123})
        request = opener.requests[0]
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.full_url, f"https://uploads.github.com/repos/{REPO}/releases/99/assets?name=release-version.json")
        self.assertEqual(request.get_header("Authorization"), "Bearer token")
        self.assertEqual(request.data, b"{}")

    def test_workflow_keeps_signing_overrides_serialization_and_dispatch_only(self):
        workflow = (SCRIPT.parents[1] / "workflows" / "android.yml").read_text()
        self.assertIn("group: routely-signed-release", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn('default: ""', workflow)
        self.assertNotIn('default: "1"', workflow)
        self.assertIn('"-PversionName=$RELEASE_VERSION_NAME" "-PversionCode=$RELEASE_VERSION_CODE"', workflow)
        self.assertIn("r0adkll/sign-android-release@v1", workflow)
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertEqual(workflow.count("if: github.event_name == 'workflow_dispatch'"), 2)
        self.assertIn("overwrite_files: false", workflow)
        self.assertNotIn("softprops/action-gh-release", workflow)


if __name__ == "__main__":
    unittest.main()
