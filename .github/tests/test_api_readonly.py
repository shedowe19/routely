import importlib.util
import http.client
import io
import json
import pathlib
import unittest
import urllib.error

MODULE = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "check_api_readonly.py"
SPEC = importlib.util.spec_from_file_location("check_api_readonly", MODULE)
api = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(api)


class Reply(io.BytesIO):
    status = 200


class Opener:
    def __init__(self, payloads):
        self.payloads = iter(payloads)
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        payload = next(self.payloads)
        if isinstance(payload, Exception):
            raise payload
        return Reply(json.dumps({"data": payload}).encode())


class ApiReadOnlyTest(unittest.TestCase):
    def test_full_check_uses_sixteen_gets_and_only_prints_anonymous_results(self):
        station = {"id": 80, "name": "Test station"}
        status = {"id": 731, "user": {"username": "private_username"},
                  "checkin": {"trip": 42, "origin": {"station": station}, "destination": {"station": station}}}
        opener = Opener([
            {"username": "private_username"}, [status], [status], {}, [], 0,
            [station], [], {"username": "private_username"}, [status], [], status, {"type": "FeatureCollection"},
            {"42": [{"station": station}]},
            [{"tripId": "private_trip", "line": {"name": "RE 1"}, "plannedWhen": "2026-11-03T10:00:00Z",
              "when": None, "direction": "Test", "station": station, "operator": None}],
            {"stopovers": [{"station": station}]},
        ])
        result = api.run(api.ReadOnlyCheck("https://example.test", "private_token", opener))
        self.assertEqual((16, 0, 0), (result["passed"], result["failed"], result["skipped"]))
        self.assertTrue(all(r.get_method() == "GET" and r.data is None for r in opener.requests))
        self.assertTrue(all(r.get_header("User-agent").startswith("Routely-API-Check/") for r in opener.requests))
        output = json.dumps(result)
        for value in ("private_username", "private_token", "private_trip", "731", "example.test"):
            self.assertNotIn(value, output)

    def test_missing_samples_are_explicitly_skipped(self):
        opener = Opener([{"username": "test"}, [], [], {}, [], 0, [], [], {"username": "test"}, [], []])
        result = api.run(api.ReadOnlyCheck("https://example.test", "test", opener))
        self.assertEqual((11, 0, 5), (result["passed"], result["failed"], result["skipped"]))

    def test_invalid_server_is_rejected_before_credentials_are_sent(self):
        for value in ("http://example.test", "https://name:pass@example.test", "https://example.test?token=x",
                      "https://example.test/#fragment", "https://example.test:bad", "https://[bad",
                      "https://example.test\\other", "https://example.test\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                api.ReadOnlyCheck(value, "test")
        self.assertEqual("https://example.test/app/api/v1/", api.server_base("https://example.test/app/"))

    def test_no_redirect_can_forward_authorization_to_another_origin(self):
        self.assertIsNone(api.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.test"))
        opener = Opener([urllib.error.HTTPError("https://private.test", 302, "private text", {}, None)])
        check = api.ReadOnlyCheck("https://example.test", "test", opener)
        self.assertIsNone(check.get("auth/user", "auth/user", lambda value: True))
        self.assertEqual(302, check.results[0]["http"])
        self.assertNotIn("private", json.dumps(check.results))

    def test_contract_and_network_errors_do_not_expose_response_or_url(self):
        for payload in ("private response text", urllib.error.URLError("private token and URL"),
                        http.client.BadStatusLine("private response data"),
                        http.client.IncompleteRead(b"private response body")):
            check = api.ReadOnlyCheck("https://example.test", "test", Opener([payload]))
            self.assertIsNone(check.get("auth/user", "auth/user", lambda value: isinstance(value, dict)))
            self.assertEqual("failed", check.results[0]["result"])
            self.assertNotIn("private", json.dumps(check.results))

    def test_status_contract_requires_new_checkin_user_and_nested_station(self):
        self.assertFalse(api.status_list([{"id": 1, "train": {}, "userDetails": {}}]))
        self.assertFalse(api.stopover_list([{"id": 80, "name": "legacy"}]))
        self.assertFalse(api.positive_id(True))
        self.assertTrue(api.stopover_list([{"station": {"id": 80, "name": "Test"}}]))

    def test_empty_objects_are_contract_failures_instead_of_missing_samples(self):
        for validator, payload in ((api.named_user, {}), (api.status_value, {}),
                                   (api.departure_list, [{}])):
            check = api.ReadOnlyCheck("https://example.test", "test", Opener([payload]))
            self.assertIsNone(check.get("sample", "sample", validator))
            self.assertEqual("failed", check.results[0]["result"])

    def test_departure_requires_current_fields_but_an_empty_board_is_valid(self):
        self.assertTrue(api.departure_list([]))
        self.assertFalse(api.departure_list([{"tripId": "trip", "line": {"name": "RE 1"},
                                              "destination": "legacy", "delay": 0}]))

    def test_token_is_required_and_cannot_inject_http_headers(self):
        for token in (None, "", "token\r\nInjected: header"):
            with self.subTest(token=token), self.assertRaises(ValueError):
                api.ReadOnlyCheck("https://example.test", token)


if __name__ == "__main__":
    unittest.main()
