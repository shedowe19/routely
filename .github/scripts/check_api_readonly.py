"""Opt-in Träwelling GET contract check. Never print tokens, URLs or response data."""

import argparse
import http.client
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def server_base(value):
    parts = urllib.parse.urlsplit(value)
    if (parts.scheme != "https" or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.query or parts.fragment
            or any(c.isspace() or ord(c) < 32 for c in value) or "\\" in value):
        raise ValueError("invalid_server")
    _ = parts.port  # Validate the port before attaching credentials.
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc,
                                   parts.path.rstrip("/") + "/api/v1/", "", ""))


def positive_id(value):
    return type(value) is int and 0 < value <= 2147483647


def named_user(value):
    return isinstance(value, dict) and isinstance(value.get("username"), str) and bool(value["username"].strip())


def station_value(value):
    return (isinstance(value, dict) and positive_id(value.get("id"))
            and isinstance(value.get("name"), str) and bool(value["name"].strip()))


def status_value(value):
    return (isinstance(value, dict) and positive_id(value.get("id")) and named_user(value.get("user"))
            and isinstance(value.get("checkin"), dict)
            and all(isinstance(value["checkin"].get(k), dict)
                    and station_value(value["checkin"][k].get("station")) for k in ("origin", "destination")))


def status_list(value):
    return isinstance(value, list) and all(status_value(s) for s in value)


def departure_list(value):
    return isinstance(value, list) and all(
        isinstance(d, dict) and isinstance(d.get("tripId"), str) and bool(d["tripId"])
        and isinstance(d.get("line"), dict) and isinstance(d["line"].get("name"), str)
        and bool(d["line"]["name"].strip()) and isinstance(d.get("plannedWhen"), str)
        and isinstance(d.get("direction"), str) and station_value(d.get("station"))
        and "when" in d and (d["when"] is None or isinstance(d["when"], str))
        and "operator" in d and (d["operator"] is None or isinstance(d["operator"], dict)) for d in value)


def stopover_list(value):
    return isinstance(value, list) and all(
        isinstance(s, dict) and isinstance(s.get("station"), dict)
        and station_value(s["station"]) for s in value)


class ReadOnlyCheck:
    def __init__(self, server, token, opener=None):
        self.base = server_base(server)
        if not token or any(c.isspace() or ord(c) < 32 for c in token):
            raise ValueError("invalid_token")
        self.token = token
        self.opener = opener or urllib.request.build_opener(NoRedirect())
        self.results = []

    def get(self, label, path, valid):
        record = {"endpoint": label, "result": "failed"}
        self.results.append(record)
        try:
            # All callers supply fixed paths with individually encoded resource values.
            if path.startswith("/") or "://" in path or "\\" in path:
                raise ValueError("invalid_path")
            request = urllib.request.Request(self.base + path, method="GET", headers={
                "Authorization": "Bearer " + self.token, "Accept": "application/json",
                "User-Agent": "Routely-API-Check/1.0 (+https://github.com/shedowe19/routely)"})
            with self.opener.open(request, timeout=20) as response:
                record["http"] = response.status
                raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024:
                record["reason"] = "response_too_large"
                return None
            body = json.loads(raw)
            data = body.get("data") if isinstance(body, dict) else None
            if record["http"] != 200 or not valid(data):
                record["reason"] = "response_contract"
                return None
            record["result"] = "passed"
            return data
        except urllib.error.HTTPError as error:
            record.update(http=error.code, reason="http_error")
            error.close()
        except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError):
            record["reason"] = "network_error"
        except (ValueError, TypeError, KeyError):
            record["reason"] = "response_contract"
        return None

    def skip(self, label):
        self.results.append({"endpoint": label, "result": "skipped", "reason": "no_sample"})


def run(check):
    obj = lambda value: isinstance(value, dict)
    array = lambda value: isinstance(value, list)
    auth = check.get("auth/user", "auth/user", named_user)
    check.get("dashboard", "dashboard", status_list)
    check.get("statuses", "statuses", status_list)
    check.get("statistics", "statistics", obj)
    check.get("notifications", "notifications", array)
    check.get("notifications/unread/count", "notifications/unread/count",
              lambda value: type(value) is int and value >= 0)
    stations = check.get("station/autocomplete", "trains/station/autocomplete/Berlin%20Hbf", array)
    check.get("stations", "stations?min_lat=52.52&max_lat=52.53&min_lon=13.36&max_lon=13.38", array)
    statuses = None
    username = auth.get("username") if auth else None
    if isinstance(username, str) and username:
        encoded = urllib.parse.quote(username, safe="")
        check.get("user/profile", "user/" + encoded, named_user)
        statuses = check.get("user/statuses", "user/" + encoded + "/statuses", status_list)
        check.get("user/search", "user/search/" + encoded, array)
    else:
        for label in ("user/profile", "user/statuses", "user/search"):
            check.skip(label)
    if statuses:
        sample = statuses[0]
        check.get("status/detail", "status/" + str(sample["id"]) + "?withIdentifiers=true", status_value)
        check.get("polyline", "polyline/" + str(sample["id"]),
                  lambda value: obj(value) and value.get("type") == "FeatureCollection")
        trip = sample["checkin"].get("trip")
        if positive_id(trip):
            check.get("stopovers", "stopovers/" + str(trip),
                      lambda value: obj(value) and all(stopover_list(v) for v in value.values()))
        else:
            check.skip("stopovers")
    else:
        for label in ("status/detail", "polyline", "stopovers"):
            check.skip(label)
    station = next((s for s in stations or [] if isinstance(s, dict) and positive_id(s.get("id"))), None)
    departures = check.get("station/departures", "station/" + str(station["id"]) + "/departures", departure_list) if station else None
    if not station:
        check.skip("station/departures")
    departure = next((d for d in departures or [] if isinstance(d, dict)
                      and isinstance(d.get("tripId"), str) and d["tripId"]
                      and isinstance(d.get("line"), dict) and isinstance(d["line"].get("name"), str)), None)
    if departure:
        query = urllib.parse.urlencode({"hafasTripId": departure["tripId"], "lineName": departure["line"]["name"]})
        check.get("trains/trip", "trains/trip?" + query,
                  lambda value: obj(value) and stopover_list(value.get("stopovers")))
    else:
        check.skip("trains/trip")
    return {"results": check.results, "failed": sum(r["result"] == "failed" for r in check.results),
            "passed": sum(r["result"] == "passed" for r in check.results),
            "skipped": sum(r["result"] == "skipped" for r in check.results)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", default="https://traewelling.de")
    parser.add_argument("--token-stdin", action="store_true", help="Token über Standardeingabe statt Umgebung lesen")
    args = parser.parse_args()
    try:
        token = sys.stdin.readline().rstrip("\r\n") if args.token_stdin else os.environ.get("TRAEWELLING_API_TOKEN")
        check = ReadOnlyCheck(args.server, token)
    except ValueError:
        print(json.dumps({"error": "HTTPS-Server und TRAEWELLING_API_TOKEN prüfen."}))
        return 2
    result = run(check)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
