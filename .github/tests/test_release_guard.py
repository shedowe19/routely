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
RUN_ID = 42
RUN_ATTEMPT = 1
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
        if "/git/matching-refs/tags/" in path:
            prefix = urllib.parse.unquote(path.split("/git/matching-refs/tags/", 1)[1])
            return [{"ref": "refs/tags/" + tag, "object": copy.deepcopy(obj)}
                    for tag, obj in self.refs.items() if tag.startswith(prefix)]
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
            value = self.annotated[sha]
            return copy.deepcopy(value) if "object" in value else {"sha": sha, "object": copy.deepcopy(value)}
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
            self.refs[tag] = {"type": "tag" if payload["sha"] in self.annotated else "commit", "sha": payload["sha"]}
            return {"ref": payload["ref"], "object": self.refs[tag]}
        if path.endswith("/git/tags") and method == "POST":
            sha = hashlib.sha1(json.dumps(payload, sort_keys=True).encode()).hexdigest()
            value = {"sha": sha, "tag": payload["tag"], "message": payload["message"],
                     "object": {"type": payload["type"], "sha": payload["object"]}}
            self.annotated[sha] = value
            return copy.deepcopy(value)
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

    def claim(self, selected=None, run_id=RUN_ID, attempt=RUN_ATTEMPT):
        selected = selected or guard.plan(REPO, "1.9.0", 14, SHA)
        claimed = guard.claim_version(self.client, self.floor, selected, run_id, attempt)
        self.client.calls.clear()
        return claimed

    def test_legacy_bootstrap_auto_selects_14_never_one(self):
        self.assertEqual(self.preflight()["version_code"], 14)
        self.assertEqual(self.writes(), [])

    def test_failed_build_claim_remains_consumed_without_version_tag_or_release(self):
        selected = self.preflight()
        self.claim(selected)
        self.assertNotIn(selected["tag_name"], self.client.refs)
        self.assertEqual(self.preflight(name="1.9.1")["version_code"], 15)
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1", code="14")
        self.assertEqual(self.writes(), [])

    def test_claimed_published_code_survives_release_withdrawal_and_deletion(self):
        for withdrawal in ("draft", "deleted", "release-and-version-tag-deleted"):
            with self.subTest(withdrawal=withdrawal):
                self.client = FakeGithub()
                self.claim(guard.plan(REPO, "2.0.0", 18, SHA))
                release = self.client.published(metadata("2.0.0", 18), 180)
                if withdrawal == "draft":
                    release["draft"] = True
                else:
                    self.client.releases.remove(release)
                if withdrawal == "release-and-version-tag-deleted":
                    del self.client.refs["v2.0.0"]
                self.assertEqual(self.preflight(name="2.0.1")["version_code"], 19)
                self.assertEqual(self.writes(), [])

    def test_preledger_withdrawn_or_deleted_release_tag_fails_closed(self):
        for withdrawal in ("draft", "deleted"):
            with self.subTest(withdrawal=withdrawal):
                self.client = FakeGithub()
                release = self.client.published(metadata("2.0.0", 18), 180)
                if withdrawal == "draft":
                    release["draft"] = True
                else:
                    self.client.releases.remove(release)
                with self.assertRaises(guard.GuardError):
                    self.preflight(name="2.0.1")
                self.assertEqual(self.writes(), [])

    def test_claim_is_exact_commit_and_workflow_bound_create_only(self):
        claimed = self.claim()
        self.assertEqual(claimed, dict(guard.plan(REPO, "1.9.0", 14, SHA),
                                       workflow_run_id=RUN_ID, workflow_run_attempt=RUN_ATTEMPT))
        self.assertEqual(guard.ref_commit(self.client, guard.claim_tag(14)), SHA)
        for run_id, attempt in ((RUN_ID + 1, RUN_ATTEMPT), (RUN_ID, RUN_ATTEMPT + 1)):
            with self.subTest(run_id=run_id, attempt=attempt), self.assertRaises(guard.GuardError):
                guard.reserve(self.client, self.floor, metadata(), "notes", run_id, attempt)
        self.assertEqual(self.writes(), [])

    def test_pending_draft_cannot_be_published_by_another_run_or_attempt(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.client.calls.clear()
        for run_id, attempt in ((RUN_ID + 1, RUN_ATTEMPT), (RUN_ID, RUN_ATTEMPT + 1)):
            with self.subTest(run_id=run_id, attempt=attempt), self.assertRaises(guard.GuardError):
                guard.publish(self.client, rid, metadata(), self.apk, self.floor, run_id, attempt)
        self.assertEqual(self.writes(), [])

    def test_missing_or_deleted_own_claim_stops_reservation_and_upload(self):
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.assertEqual(self.writes(), [])
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        del self.client.refs[guard.claim_tag(14)]
        self.client.calls.clear()
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertEqual(self.writes(), [])

    def test_simultaneous_same_code_claim_is_rejected_atomically(self):
        selected = self.preflight()
        claimed = dict(selected, workflow_run_id=RUN_ID + 1, workflow_run_attempt=1)
        def race(method, path, payload):
            if method == "POST" and path.endswith("/git/refs"):
                tag_sha = "c" * 40
                self.client.annotated[tag_sha] = {"sha": tag_sha, "tag": guard.claim_tag(14),
                    "message": json.dumps(claimed), "object": {"type": "commit", "sha": SHA}}
                self.client.refs[guard.claim_tag(14)] = {"type": "tag", "sha": tag_sha}
        self.client.fail = race
        with self.assertRaises(guard.GuardError):
            guard.claim_version(self.client, self.floor, selected, RUN_ID, RUN_ATTEMPT)
        self.client.fail = None
        self.assertEqual(guard.version_claims(self.client)[14], claimed)
        self.assertFalse(any(call[0] in ("PATCH", "DELETE", "UPLOAD") for call in self.writes()))
        self.assertFalse(any(call[1].endswith("/releases") for call in self.writes()))
        self.assertEqual(self.preflight(name="1.9.1")["version_code"], 15)

    def test_higher_code_claim_during_build_stops_older_claim_from_publication(self):
        self.claim()
        self.claim(guard.plan(REPO, "1.9.1", 15, SHA), RUN_ID + 1, 1)
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.assertEqual(self.writes(), [])

    def test_claim_is_not_reused_by_rerun_or_new_workflow(self):
        selected = self.preflight()
        self.claim(selected)
        for run_id, attempt in ((RUN_ID, RUN_ATTEMPT + 1), (RUN_ID + 1, RUN_ATTEMPT)):
            with self.subTest(run_id=run_id, attempt=attempt), self.assertRaises(guard.GuardError):
                guard.claim_version(self.client, self.floor, selected, run_id, attempt)
        self.assertEqual(self.writes(), [])
        with self.assertRaises(guard.GuardError):
            self.preflight()
        # Neither implicit nor explicit higher codes may create a duplicate
        # named claim after a failed build; the next run uses a fresh name.
        with self.assertRaises(guard.GuardError):
            guard.claim_version(self.client, self.floor, guard.plan(REPO, "1.9.0", 15, SHA), RUN_ID, RUN_ATTEMPT + 1)
        self.assertEqual(self.writes(), [])
        self.assertEqual(self.preflight(name="1.9.1")["version_code"], 15)

    def test_malformed_claim_payloads_targets_and_names_fail_closed(self):
        self.claim()
        initial_refs = copy.deepcopy(self.client.refs)
        initial_tags = copy.deepcopy(self.client.annotated)
        tag_sha = self.client.refs[guard.claim_tag(14)]["sha"]
        changes = [
            lambda tag: tag.update(tag="routely-version-code/15"),
            lambda tag: tag.update(sha=OTHER_SHA),
            lambda tag: tag.update(object={"type": "commit", "sha": OTHER_SHA}),
            lambda tag: tag.update(object={"type": "tag", "sha": SHA}),
            lambda tag: tag.update(message="{"),
            lambda tag: tag.update(message="\ud800"),
            lambda tag: tag.update(message="x" * (guard.MAX_METADATA + 1)),
        ]
        for field, value in (("schema_version", True), ("version_code", 15), ("repository", "other/repo"),
                             ("workflow_run_id", 0), ("workflow_run_attempt", True), ("extra", "invalid")):
            def change(tag, field=field, value=value):
                claim = json.loads(tag["message"])
                claim[field] = value
                tag["message"] = json.dumps(claim)
            changes.append(change)
        for index, change in enumerate(changes):
            self.client.refs = copy.deepcopy(initial_refs)
            self.client.annotated = copy.deepcopy(initial_tags)
            change(self.client.annotated[tag_sha])
            with self.subTest(change=index), self.assertRaises(guard.GuardError):
                self.preflight(name="1.9.1")
        self.client.refs = copy.deepcopy(initial_refs)
        self.client.annotated = copy.deepcopy(initial_tags)
        self.client.refs[guard.claim_tag(14)]["type"] = "commit"
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")
        self.client.refs = copy.deepcopy(initial_refs)
        self.client.refs["routely-version-code/014"] = self.client.refs.pop(guard.claim_tag(14))
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")
        self.assertEqual(self.writes(), [])

    def test_duplicate_claimed_version_identity_and_published_code_conflict_stop(self):
        self.claim()
        claim = dict(guard.plan(REPO, "1.9.0", 15, SHA), workflow_run_id=43, workflow_run_attempt=1)
        tag_sha = "c" * 40
        self.client.annotated[tag_sha] = {"sha": tag_sha, "tag": guard.claim_tag(15),
            "message": json.dumps(claim), "object": {"type": "commit", "sha": SHA}}
        self.client.refs[guard.claim_tag(15)] = {"type": "tag", "sha": tag_sha}
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")
        del self.client.refs[guard.claim_tag(15)]
        self.client.published(metadata("1.9.2", 14), 102)
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")

    def test_claimed_version_tag_cannot_move_to_another_commit(self):
        self.claim()
        self.client.refs["v1.9.0"] = {"type": "commit", "sha": OTHER_SHA}
        with self.assertRaises(guard.GuardError):
            self.preflight(name="1.9.1")

    def test_matching_refs_wrong_prefix_duplicates_and_excess_are_rejected(self):
        valid = {"ref": "refs/tags/routely-version-code/14", "object": {"type": "tag", "sha": SHA}}
        for values in ([dict(valid, ref="refs/heads/main")], [valid, valid], [valid] * (guard.MAX_REFS + 1), {}):
            with mock.patch.object(self.client, "json", return_value=values), self.assertRaises(guard.GuardError):
                guard.matching_refs(self.client, guard.CLAIM_PREFIX)

    def test_invalid_workflow_identity_and_plan_types_never_claim(self):
        selected = self.preflight()
        self.client.calls.clear()
        for run_id, attempt in ((0, 1), (42, False), ("01", "1"), ("42\n", "1"), (2**63, 1)):
            with self.subTest(run_id=run_id, attempt=attempt), self.assertRaises(guard.GuardError):
                guard.claim_version(self.client, self.floor, selected, run_id, attempt)
        bad = dict(selected, schema_version=True)
        with self.assertRaises(guard.GuardError):
            guard.claim_version(self.client, self.floor, bad, RUN_ID, RUN_ATTEMPT)
        self.assertEqual(self.client.calls, [])

    def test_cli_preflight_claims_before_build_plan_and_environment_are_written(self):
        output = Path(self.temp.name) / "plan.json"
        env_file = Path(self.temp.name) / "env"
        env = {"GITHUB_REPOSITORY": REPO, "GITHUB_TOKEN": "offline-token", "GITHUB_EVENT_NAME": "workflow_dispatch",
               "GITHUB_SHA": SHA, "GITHUB_RUN_ID": str(RUN_ID), "GITHUB_RUN_ATTEMPT": str(RUN_ATTEMPT),
               "RELEASE_VERSION_NAME": "1.9.0", "RELEASE_VERSION_CODE": "", "GITHUB_ENV": str(env_file)}
        with mock.patch.dict(os.environ, env), mock.patch.object(guard, "Github", return_value=self.client), \
             mock.patch.object(guard.sys, "argv", ["guard", "preflight", "--floor", str(self.floor), "--metadata", str(output)]), \
             mock.patch.object(guard.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, stdout=SHA + "\n")), \
             mock.patch("sys.stdout", new=io.StringIO()):
            guard.main()
        self.assertEqual(json.loads(output.read_text()), guard.plan(REPO, "1.9.0", 14, SHA))
        self.assertIn("RELEASE_VERSION_CODE=14\n", env_file.read_text())
        self.assertEqual(guard.version_claims(self.client)[14]["workflow_run_id"], RUN_ID)

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
                self.claim()
                rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
                original_upload = self.client.upload
                def upload(*args):
                    result = original_upload(*args)
                    if args[1].endswith(".apk"):
                        result["digest"] = digest
                    return result
                self.client.upload = upload
                with self.assertRaises(guard.GuardError):
                    guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
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
        self.claim(selected)
        self.client.published(metadata("1.9.1", 14))
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(code=selected["version_code"]), "notes", RUN_ID, RUN_ATTEMPT)
        self.assertEqual(self.writes(), [])

    def test_tag_race_is_rejected_atomically_before_draft_mutation(self):
        self.claim()
        def race(method, path, payload):
            if method == "POST" and path.endswith("/git/refs"):
                self.client.refs["v1.9.0"] = {"type": "commit", "sha": OTHER_SHA}
        self.client.fail = race
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.assertEqual([call[1] for call in self.writes()], [f"/repos/{REPO}/git/refs"])

    def test_draft_race_does_not_update_existing_release(self):
        self.claim()
        def race(method, path, payload):
            if method == "POST" and path.endswith("/releases"):
                self.client.releases.append(dict(id=999, tag_name="v1.9.0", draft=False, assets=[]))
        self.client.fail = race
        with self.assertRaises(guard.GuardError):
            guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))

    def test_exact_commit_bound_create_only_payload_and_complete_publish(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "literal\nnotes", RUN_ID, RUN_ATTEMPT)
        create = next(call[2] for call in self.writes() if call[1] == f"/repos/{REPO}/releases")
        self.assertEqual(create["target_commitish"], SHA)
        self.assertTrue(create["draft"])
        self.assertEqual(create["body"], "literal\nnotes")
        guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        patches = [call for call in self.writes() if call[0] == "PATCH"]
        self.assertEqual(patches, [("PATCH", f"/repos/{REPO}/releases/{rid}", {"draft": False, "target_commitish": SHA})])
        self.assertEqual([call[2] for call in self.writes() if call[0] == "UPLOAD"], ["routely-v1.9.0.apk", guard.ASSET_NAME])
        self.assertEqual(guard.released_floor(self.client, 13, {"v1.8.7": 101}), 14)

    def test_changed_tag_stops_before_asset_upload(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.client.refs["v1.9.0"]["sha"] = OTHER_SHA
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertEqual(len(self.writes()), before)

    def test_new_published_code_after_reservation_stops_before_upload(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.client.published(metadata("1.9.1", 15), 106)
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertEqual(len(self.writes()), before)

    def test_new_published_code_during_upload_prevents_final_publish(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        original_upload = self.client.upload
        def upload(*args):
            result = original_upload(*args)
            if args[1] == guard.ASSET_NAME:
                self.client.published(metadata("1.9.1", 15), 106)
            return result
        self.client.upload = upload
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))

    def test_foreign_release_or_existing_assets_stop_before_overwrite(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        release = next(item for item in self.client.releases if item["id"] == rid)
        release["assets"] = [dict(id=555, name="foreign.apk", size=10, state="uploaded")]
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertEqual(len(self.writes()), before)
        release["assets"] = []
        release["tag_name"] = "vforeign"
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)

    def test_partial_asset_failure_never_publishes_or_retries_overwrites(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        def fail(method, path, payload):
            if method == "UPLOAD" and payload == guard.ASSET_NAME:
                raise guard.GuardError("upload failed")
        self.client.fail = fail
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertFalse(any(call[0] == "PATCH" for call in self.writes()))
        self.assertTrue(next(item for item in self.client.releases if item["id"] == rid)["draft"])
        self.client.fail = None
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
        self.assertEqual(len(self.writes()), before)

    def test_changed_signed_apk_never_uploads(self):
        self.claim()
        rid = guard.reserve(self.client, self.floor, metadata(), "notes", RUN_ID, RUN_ATTEMPT)
        self.apk.write_bytes(b"changed APK")
        before = len(self.writes())
        with self.assertRaises(guard.GuardError):
            guard.publish(self.client, rid, metadata(), self.apk, self.floor, RUN_ID, RUN_ATTEMPT)
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
