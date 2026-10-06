#!/usr/bin/env python3
"""Export public bahnhof.de replacement-stop points; never select a trip's stop.

Uses Python 3.10+ and only the standard library. This is a development tool,
not an Android runtime data source or a supported DB API client.
"""

import argparse
import hashlib
import json
import math
import re
import sys
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen


class ScriptContents(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.in_script = False
        self.parts = []
        self.scripts = []

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            self.in_script = True
            self.parts = []

    def handle_data(self, data):
        if self.in_script:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "script" and self.in_script:
            self.scripts.append("".join(self.parts))
            self.in_script = False


def walk(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from walk(item)
    elif isinstance(value, list):
        for item in value:
            yield from walk(item)


def read_map_data(html, station_slug):
    """Decode JSON in streamed Next records without executing page scripts."""
    parser = ScriptContents()
    parser.feed(html)
    decoder = json.JSONDecoder()
    chunks = []
    for script in parser.scripts:
        for match in re.finditer(r"self\.__next_f\.push\(\s*", script):
            try:
                value, end = decoder.raw_decode(script[match.end():])
            except ValueError:
                continue
            if not script[match.end() + end:].lstrip().startswith(")"):
                continue
            if (isinstance(value, list) and len(value) >= 2
                    and value[0] == 1 and isinstance(value[1], str)):
                chunks.append(value[1])
    candidates = []
    for line in "".join(chunks).splitlines():
        match = re.match(r"^[0-9a-fA-F]+:(.*)$", line)
        if match is None:
            continue
        try:
            record = json.loads(match.group(1))
        except ValueError:
            continue
        for obj in walk(record):
            if obj.get("slug") == station_slug and isinstance(obj.get("poi"), dict):
                candidates.append(obj)
    if not candidates:
        raise ValueError(f"No map data for {station_slug}; page format may have changed")
    unique = {json.dumps(c, sort_keys=True): c for c in candidates}
    if len(unique) != 1:
        raise ValueError(f"Ambiguous map data for {station_slug}")
    return next(iter(unique.values()))


def valid_point(coordinates):
    if not isinstance(coordinates, list) or len(coordinates) != 2:
        return False
    lon, lat = coordinates  # GeoJSON is longitude, latitude.
    return (all(isinstance(v, (int, float)) and not isinstance(v, bool)
                and math.isfinite(v) for v in coordinates)
            and -180 <= lon <= 180 and -90 <= lat <= 90)


def clean_text(value):
    if not isinstance(value, str) or value == "$undefined":
        return None
    return " ".join(value.split()) or None


def direction_label(value):
    if not isinstance(value, str) or value == "$undefined":
        return None
    # Separate source lines can carry different direction/date conditions.
    return "\n".join(" ".join(line.split()) for line in value.splitlines()).strip() or None


def extract_stops(map_data):
    features = map_data["poi"].get("RAIL_REPLACEMENT_TRANSPORT", [])
    if not isinstance(features, list):
        raise ValueError("Unexpected replacement-stop collection")
    stops = {}
    for feature in features:
        if not isinstance(feature, dict):
            raise ValueError("Unexpected replacement-stop feature")
        props, geometry = feature.get("properties", {}), feature.get("geometry", {})
        if (not isinstance(props, dict) or not isinstance(geometry, dict)
                or feature.get("type") != "Feature"
                or props.get("type") != "RAIL_REPLACEMENT_TRANSPORT"
                or geometry.get("type") != "Point"
                or not valid_point(geometry.get("coordinates"))):
            raise ValueError("Invalid replacement-stop point; refusing coordinate export")
        feature_id = clean_text(feature.get("id"))
        if feature_id is None:
            raise ValueError("Replacement-stop point without an ID")
        lon, lat = geometry["coordinates"]
        stop = {
            "source_feature_id": feature_id,
            "latitude": lat,
            "longitude": lon,
            "direction_label": direction_label(props.get("name")),
            "feature_version": clean_text(props.get("version")),
            "level": clean_text(props.get("level")),
        }
        if feature_id in stops and stops[feature_id] != stop:
            raise ValueError(f"Conflicting replacement-stop ID {feature_id}")
        stops[feature_id] = stop
    return list(stops.values())


def extract_station(station_slug):
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", station_slug):
        raise ValueError(f"Invalid station slug: {station_slug}")
    url = f"https://www.bahnhof.de/{station_slug}/karte"
    request = Request(url, headers={"User-Agent": "Routely-SEV-Extractor/1.0"})
    with urlopen(request, timeout=30) as response:
        final = urlparse(response.url)
        if final.scheme != "https" or final.hostname not in {"www.bahnhof.de", "bahnhof.de"}:
            raise ValueError("Unexpected source host")
        if "text/html" not in response.headers.get("Content-Type", ""):
            raise ValueError("Unexpected map response content type")
        raw = response.read(10 * 1024 * 1024 + 1)
    if len(raw) > 10 * 1024 * 1024:
        raise ValueError("Map response exceeds 10 MiB")
    data = read_map_data(raw.decode("utf-8"), station_slug)
    notes = data.get("notes", {}).get("RAIL_REPLACEMENT_TRANSPORT", [])
    notices = []
    for note in notes:
        body = note.get("body", "") if isinstance(note, dict) else ""
        # Preserve the notice as text, without guessing validity for each point.
        for line in body.splitlines():
            if re.search(r"Temporäre Ersatzhaltestellen", line, flags=re.IGNORECASE):
                notices.append(clean_text(line))
    return {
        "station_slug": station_slug,
        "source_url": url,
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "temporary_notices": list(dict.fromkeys(notices)),
        "replacement_stops": extract_stops(data),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("station_slugs", nargs="+", help="e.g. essen-hbf")
    parser.add_argument("--output", type=Path, help="Write JSON instead of stdout")
    args = parser.parse_args()
    try:
        stations = [extract_station(slug) for slug in dict.fromkeys(args.station_slugs)]
        result = {"schema_version": 1, "trip_stop_selection": "not_performed", "stations": stations}
        output = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output:
            args.output.write_text(output, encoding="utf-8")
        else:
            sys.stdout.write(output)
    except (OSError, ValueError, TypeError, KeyError) as exc:
        parser.exit(1, f"Extraction failed: {exc}\n")


if __name__ == "__main__":
    main()
