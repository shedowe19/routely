import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:timezone/data/latest.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;
import 'package:unorm_dart/unorm_dart.dart' as unorm;

import '../data/models.dart' as api;
import 'models.dart';

class SevPoint {
  const SevPoint({
    required this.id,
    required this.latitude,
    required this.longitude,
    this.label,
    this.version,
  });
  final String id;
  final double latitude, longitude;
  final String? label, version;
  @override
  bool operator ==(Object other) =>
      other is SevPoint &&
      id == other.id &&
      latitude == other.latitude &&
      longitude == other.longitude &&
      label == other.label &&
      version == other.version;
  @override
  int get hashCode => Object.hash(id, latitude, longitude, label, version);
  Map<String, Object?> toJson() => {
    'id': id,
    'latitude': latitude,
    'longitude': longitude,
    'label': label,
    'version': version,
  };
  factory SevPoint.fromJson(Map<String, dynamic> j) => SevPoint(
    id: j['id'] as String,
    latitude: (j['latitude'] as num).toDouble(),
    longitude: (j['longitude'] as num).toDouble(),
    label: j['label'] as String?,
    version: j['version'] as String?,
  );
}

class SevMap {
  SevMap({
    required this.slug,
    required this.sourceUrl,
    required this.stationLatitude,
    required this.stationLongitude,
    required this.points,
    required this.notes,
    required this.fetchedAtMillis,
  });
  final String slug, sourceUrl;
  final double stationLatitude, stationLongitude;
  final List<SevPoint> points;
  final List<String> notes;
  final int fetchedAtMillis;
  Map<String, Object?> toJson() => {
    'slug': slug,
    'sourceUrl': sourceUrl,
    'stationLatitude': stationLatitude,
    'stationLongitude': stationLongitude,
    'points': points.map((p) => p.toJson()).toList(),
    'notes': notes,
    'fetchedAtMillis': fetchedAtMillis,
  };
  factory SevMap.fromJson(Map<String, dynamic> j) => SevMap(
    slug: j['slug'] as String,
    sourceUrl: j['sourceUrl'] as String,
    stationLatitude: (j['stationLatitude'] as num).toDouble(),
    stationLongitude: (j['stationLongitude'] as num).toDouble(),
    fetchedAtMillis: j['fetchedAtMillis'] as int,
    notes: (j['notes'] as List).cast<String>(),
    points: (j['points'] as List)
        .map((p) => SevPoint.fromJson(Map<String, dynamic>.from(p as Map)))
        .toList(),
  );
}

class SevStopInfo {
  const SevStopInfo({
    required this.sourceUrl,
    this.label,
    required this.guidance,
    this.latitude,
    this.longitude,
    this.reason,
  });
  final String sourceUrl, guidance;
  final String? label, reason;
  final double? latitude, longitude;
  bool get hasCoordinates => latitude != null && longitude != null;
}

/// Reads JSON embedded in Next flight records; JavaScript is never evaluated.
class BahnhofSevParser {
  static const maxHtmlBytes = 8 * 1024 * 1024,
      _type = 'RAIL_REPLACEMENT_TRANSPORT';
  static bool isValidSlug(String slug) =>
      slug.length <= 120 &&
      RegExp(r'^[a-z0-9]+(?:-[a-z0-9]+)*$').hasMatch(slug);
  static SevMap? parse(String html, String expectedSlug, int fetchedAtMillis) {
    if (!isValidSlug(expectedSlug) ||
        fetchedAtMillis < 0 ||
        html.length > maxHtmlBytes) {
      return null;
    }
    try {
      return _parse(html, expectedSlug, fetchedAtMillis);
    } catch (_) {
      return null;
    }
  }

  static SevMap? _parse(String html, String slug, int fetched) {
    final chunks = StringBuffer(),
        scripts = RegExp(
          r'<script\b[^>]*>(.*?)</script\s*>',
          caseSensitive: false,
          dotAll: true,
        );
    final push = RegExp(r'self\.__next_f\.push\(\s*');
    for (final script in scripts.allMatches(html)) {
      final content = script.group(1)!;
      for (final match in push.allMatches(content)) {
        final end = _arrayEnd(content, match.end);
        if (end == null || !content.substring(end).trimLeft().startsWith(')')) {
          continue;
        }
        final value = _json(content.substring(match.end, end));
        if (value is List &&
            value.length >= 2 &&
            value[0] is num &&
            value[0] == 1 &&
            value[1] is String) {
          chunks.write(value[1]);
        }
      }
    }
    Map<String, dynamic>? candidate;
    String? candidateCanonical;
    var visited = 0;
    for (final line in const LineSplitter().convert(chunks.toString())) {
      final record = RegExp(r'^[0-9a-fA-F]+:(.*)$').firstMatch(line);
      if (record == null) continue;
      final root = _json(record.group(1)!);
      if (root == null) continue;
      final pending = <dynamic>[root];
      while (pending.isNotEmpty) {
        if (++visited > 100000) return null;
        final node = pending.removeLast();
        if (node is Map<String, dynamic>) {
          if (node['slug'] == slug && node['poi'] is Map) {
            final canonical = _canonical(node);
            if (candidate != null && candidateCanonical != canonical) {
              return null;
            }
            candidate = node;
            candidateCanonical = canonical;
          }
          pending.addAll(node.values);
        } else if (node is List) {
          pending.addAll(node);
        }
      }
    }
    if (candidate == null) return null;
    final location = candidate['location'];
    if (location is! Map) return null;
    final latitude = _number(location['latitude']),
        longitude = _number(location['longitude']);
    if (latitude == null ||
        longitude == null ||
        !validCoordinates(latitude, longitude)) {
      return null;
    }
    final poi = candidate['poi'] as Map, features = poi[_type] ?? [];
    if (features is! List) return null;
    final points = <String, SevPoint>{};
    for (final feature in features) {
      if (feature is! Map) return null;
      final properties = feature['properties'], geometry = feature['geometry'];
      if (properties is! Map ||
          geometry is! Map ||
          feature['type'] != 'Feature' ||
          properties['type'] != _type ||
          geometry['type'] != 'Point') {
        return null;
      }
      final coordinates = geometry['coordinates'];
      if (coordinates is! List || coordinates.length != 2) return null;
      final lon = _number(coordinates[0]),
          lat = _number(coordinates[1]),
          id = _clean(feature['id']),
          propertyId = _clean(properties['id']);
      if (lon == null ||
          lat == null ||
          !validCoordinates(lat, lon) ||
          id == null ||
          (propertyId != null && propertyId != id)) {
        return null;
      }
      final point = SevPoint(
        id: id,
        latitude: lat,
        longitude: lon,
        label: _lines(properties['name']),
        version: _clean(properties['version']),
      );
      if (points.containsKey(id) && points[id] != point) return null;
      points[id] = point;
    }
    final notes = candidate['notes'];
    if (notes != null && notes is! Map) return null;
    final notices = notes is Map ? notes[_type] : null;
    if (notices != null && notices is! List) return null;
    final bodies = <String>[];
    for (final note in (notices as List? ?? [])) {
      if (note is! Map) return null;
      final body = _lines(note['body']);
      if (body != null && !bodies.contains(body)) bodies.add(body);
    }
    return SevMap(
      slug: slug,
      sourceUrl: 'https://www.bahnhof.de/$slug/karte',
      stationLatitude: latitude,
      stationLongitude: longitude,
      points: points.values.toList(),
      notes: bodies,
      fetchedAtMillis: fetched,
    );
  }

  static dynamic _json(String value) {
    try {
      return jsonDecode(value);
    } catch (_) {
      return null;
    }
  }

  static String _canonical(dynamic value) {
    if (value is Map) {
      final keys = value.keys.cast<String>().toList()..sort();
      return '{${keys.map((k) => '${jsonEncode(k)}:${_canonical(value[k])}').join(',')}}';
    }
    if (value is List) return '[${value.map(_canonical).join(',')}]';
    return jsonEncode(value);
  }

  static int? _arrayEnd(String script, int start) {
    if (start >= script.length || script[start] != '[') return null;
    var depth = 0, quoted = false, escaped = false;
    for (var i = start; i < script.length; i++) {
      final char = script[i];
      if (quoted) {
        if (escaped) {
          escaped = false;
        } else if (char == '\\') {
          escaped = true;
        } else if (char == '"') {
          quoted = false;
        }
      } else if (char == '"') {
        quoted = true;
      } else if (char == '[' || char == '{') {
        depth++;
      } else if (char == ']' || char == '}') {
        if (--depth == 0) return i + 1;
      }
    }
    return null;
  }

  static double? _number(dynamic value) =>
      value is num && value.toDouble().isFinite ? value.toDouble() : null;
  static String? _clean(dynamic value) {
    if (value is! String) return null;
    final clean = value.trim();
    if (clean == r'$undefined') return null;
    final result = clean.replaceAll(RegExp(r'\s+'), ' ');
    return result.isEmpty ? null : result;
  }

  static String? _lines(dynamic value) {
    if (value is! String || value.trim() == r'$undefined') return null;
    final result = const LineSplitter()
        .convert(value.trim())
        .map((l) => l.trim().replaceAll(RegExp(r'[\t ]+'), ' '))
        .join('\n')
        .trim();
    return result.isEmpty ? null : result;
  }
}

class _Direction {
  const _Direction(this.place, [this.until]);
  final String place;
  final DateTime? until;
}

class _DateRange {
  const _DateRange(this.start, this.end);
  final DateTime start, end;
  String get key => '${start.toIso8601String()}:${end.toIso8601String()}';
}

class SevStopResolver {
  static bool isReplacementBus(api.Checkin checkin) =>
      [
        checkin.category,
        checkin.mode,
      ].any((v) => v?.trim().toLowerCase() == 'bus') &&
      RegExp(
        r'^(?:bus\s+)?(?:re|rb)\s*\d+[a-z]?(?:\s+.*)?$',
        caseSensitive: false,
      ).hasMatch(checkin.lineName?.trim() ?? '');
  static String? stationSlug(api.Station station) {
    final name = station.name?.trim();
    if (name == null || name.isEmpty) return null;
    final slug = _normalise(
      name,
    ).replaceAll(RegExp('[^a-z0-9]+'), '-').replaceAll(RegExp('^-+|-+\$'), '');
    return slug.isNotEmpty && slug.length <= 160 ? slug : null;
  }

  static String visitKey(api.Stop stop) => stop.uuid?.trim().isNotEmpty == true
      ? stop.uuid!
      : '${stop.stationId ?? 'unknown'}:${stop.arrivalPlanned}:${stop.departurePlanned}';
  static Map<String, SevStopInfo> resolve(
    api.Checkin checkin,
    List<api.Stop> fullRoute,
    Map<String, SevMap> maps,
    int nowMillis,
  ) {
    if (!isReplacementBus(checkin) || nowMillis <= 0) return {};
    final today = _berlinDate(nowMillis), counts = <String, int>{};
    for (final stop in fullRoute) {
      final key = visitKey(stop);
      counts[key] = (counts[key] ?? 0) + 1;
    }
    final result = <String, SevStopInfo>{};
    for (var index = 0; index < fullRoute.length; index++) {
      final stop = fullRoute[index], station = fullRoute[index].station;
      if (stop.cancelled || station == null) continue;
      final slug = stationSlug(station), map = maps[stationSlug(station)];
      if (slug == null || map == null) continue;
      final key = visitKey(stop),
          notes = map.notes
              .map((s) => s.trim())
              .where((s) => s.isNotEmpty)
              .join('\n\n');
      final guidance = notes.isEmpty
          ? 'Die offizielle Bahnhofskarte zeigt die Ersatzhaltestellen.'
          : notes;
      SevStopInfo unknown(String reason) => SevStopInfo(
        sourceUrl: map.sourceUrl,
        guidance: guidance,
        reason: reason,
      );
      final lat = station.latitude, lon = station.longitude;
      if (map.slug != slug ||
          !{
            'https://www.bahnhof.de/$slug/karte',
            'https://bahnhof.de/$slug/karte',
          }.contains(map.sourceUrl) ||
          lat == null ||
          lon == null ||
          !validCoordinates(lat, lon) ||
          !validCoordinates(map.stationLatitude, map.stationLongitude) ||
          distanceMeters(lat, lon, map.stationLatitude, map.stationLongitude) >
              1500) {
        result[key] = unknown(
          'Die SEV-Karte kann diesem Bahnhof nicht sicher zugeordnet werden.',
        );
        continue;
      }
      if ((counts[key] ?? 0) > 1 ||
          ((stop.uuid?.trim().isEmpty ?? true) &&
              (stop.stationId == null ||
                  [
                    stop.arrivalPlanned,
                    stop.departurePlanned,
                  ].every((s) => parseMillis(s) == null)))) {
        result[key] = unknown(
          'Der konkrete Halt ist nicht eindeutig zugeordnet.',
        );
        continue;
      }
      if (map.fetchedAtMillis <= 0 ||
          map.fetchedAtMillis > nowMillis ||
          nowMillis - map.fetchedAtMillis > 86400000) {
        result[key] = unknown(
          'Die SEV-Karte ist nicht aktuell; die Einstiegsposition bleibt unbestätigt.',
        );
        continue;
      }
      final event =
              parseMillis(stop.departurePlanned) ??
              parseMillis(stop.arrivalPlanned),
          eventDate = event == null ? today : _berlinDate(event);
      final validity = _mapValidity(map.notes, map.points, today, eventDate);
      if (validity != null) {
        result[key] = unknown(validity);
        continue;
      }
      final points = map.points.toSet().toList();
      if (points.isEmpty) {
        result[key] = unknown('Die Bahnhofskarte enthält keine SEV-Position.');
        continue;
      }
      if (points.any(
            (p) =>
                p.id.trim().isEmpty ||
                !validCoordinates(p.latitude, p.longitude) ||
                distanceMeters(
                      p.latitude,
                      p.longitude,
                      map.stationLatitude,
                      map.stationLongitude,
                    ) >
                    3000,
          ) ||
          points.map((p) => p.id).toSet().length != points.length) {
        result[key] = unknown(
          'Die Ersatzhaltestellen enthalten widersprüchliche oder ungültige Positionsdaten.',
        );
        continue;
      }
      final chosen =
          points.length == 1 && (points.single.label?.trim().isEmpty ?? true)
          ? points.single
          : _chooseByDirection(
              points,
              fullRoute.skip(index + 1),
              today,
              eventDate,
            );
      result[key] = chosen == null
          ? unknown(
              'Mehrere oder richtungsabhängige Ersatzhaltestellen: Die passende Position ist nicht eindeutig bestätigt.',
            )
          : SevStopInfo(
              sourceUrl: map.sourceUrl,
              label: chosen.label,
              guidance: guidance,
              latitude: chosen.latitude,
              longitude: chosen.longitude,
            );
    }
    return result;
  }

  static final _dateRange = RegExp(
    r'(?:vom|von)\s+(\d{1,2})\.(\d{1,2})(?:\.(\d{4}))?\.?\s*(?:bis|–|-)\s*(\d{1,2})\.(\d{1,2})\.(\d{4})(?!\d)',
    caseSensitive: false,
  );
  static final _extraDirectionNotice = RegExp(
    r'(?:^|(?<=[.!?]))\s*bis\s+(?:zum\s+)?(\d{1,2}\.\d{1,2}\.\d{4})(?!\d)\s+fährt\s+ebenfalls\s+Ersatzverkehr\s+Richtung\s+([\p{L}][\p{L}\p{M} -]*)\.(?=\s|$)',
    caseSensitive: false,
    unicode: true,
  );
  static String? _mapValidity(
    List<String> notes,
    List<SevPoint> points,
    DateTime today,
    DateTime eventDate,
  ) {
    final ranges = <_DateRange>[],
        pointDirections = points
            .toSet()
            .map((p) => _directions(p.label))
            .toList();
    for (final note in notes) {
      final notice = note.replaceAllMapped(_extraDirectionNotice, (match) {
        final until = _parseGermanDate(match.group(1)!),
            places = _places(match.group(2)!);
        final confirmed =
            until != null &&
            places.length == 1 &&
            pointDirections
                    .where(
                      (rules) => rules.any(
                        (rule) =>
                            rule.place == places.single && rule.until == until,
                      ),
                    )
                    .length ==
                1;
        return confirmed ? '' : match.group(0)!;
      });
      final matches = _dateRange.allMatches(notice).toList(),
          rest = notice.replaceAll(_dateRange, '');
      if (RegExp(
            r'\b(?:ab(?:\s+dem)?|bis(?:\s+zum)?|vom|von)\s+\d{1,2}\.\d{1,2}(?:\.\d{4})?',
            caseSensitive: false,
          ).hasMatch(rest) ||
          (matches.isEmpty &&
              RegExp(
                'tempor[aä]r|vor[uü]bergehend',
                caseSensitive: false,
              ).hasMatch(notice))) {
        return 'Die Gültigkeit der vorübergehenden Ersatzhaltestellen ist nicht eindeutig angegeben.';
      }
      for (final match in matches) {
        final endYear = int.parse(match.group(6)!),
            startMonth = int.parse(match.group(2)!),
            endMonth = int.parse(match.group(5)!);
        final startYear =
            int.tryParse(match.group(3) ?? '') ??
            endYear - (startMonth > endMonth ? 1 : 0);
        final start = _validDate(
              startYear,
              startMonth,
              int.parse(match.group(1)!),
            ),
            end = _validDate(endYear, endMonth, int.parse(match.group(4)!));
        if (start == null || end == null) {
          return 'Die Gültigkeitsdaten der Ersatzhaltestellen sind nicht auswertbar.';
        }
        if (end.isBefore(start)) {
          return 'Die Gültigkeitsdaten der Ersatzhaltestellen sind widersprüchlich.';
        }
        ranges.add(_DateRange(start, end));
      }
    }
    if (ranges.map((r) => r.key).toSet().length > 1) {
      return 'Mehrere Gültigkeitszeiträume erlauben keine eindeutige Haltestellenzuordnung.';
    }
    if (ranges.isEmpty) return null;
    final range = ranges.first;
    return today.isBefore(range.start) ||
            today.isAfter(range.end) ||
            eventDate.isBefore(range.start) ||
            eventDate.isAfter(range.end)
        ? 'Die angegebenen Ersatzhaltestellen gelten nicht für den aktuellen Fahrtzeitraum.'
        : null;
  }

  static List<_Direction> _directions(String? label) {
    final text = label?.trim();
    if (text == null || text.isEmpty) return [];
    final extra = RegExp(
      r'^Richtung\s+(.+?)\s+bis\s+(?:zum\s+)?(\d{1,2}\.\d{1,2}\.\d{4})\s*:\s*Richtung\s+(.+)$',
      caseSensitive: false,
    ).firstMatch(text);
    if (extra != null) {
      final until = _parseGermanDate(extra.group(2)!);
      return until == null
          ? []
          : [
              ..._places(extra.group(1)!).map((p) => _Direction(p)),
              ..._places(extra.group(3)!).map((p) => _Direction(p, until)),
            ];
    }
    final bound = RegExp(
      r'^Richtung\s+(.+?)\s+bis\s+(?:zum\s+)?(\d{1,2}\.\d{1,2}\.\d{4})$',
      caseSensitive: false,
    ).firstMatch(text);
    if (bound != null) {
      final until = _parseGermanDate(bound.group(2)!);
      return until == null
          ? []
          : _places(bound.group(1)!).map((p) => _Direction(p, until)).toList();
    }
    if (RegExp(
      r'\d|\b(?:bis|ab|vom|von)\b',
      caseSensitive: false,
    ).hasMatch(text)) {
      return [];
    }
    final plain = RegExp(
      r'^Richtung\s+(.+)$',
      caseSensitive: false,
    ).firstMatch(text);
    return plain == null
        ? []
        : _places(plain.group(1)!).map((p) => _Direction(p)).toList();
  }

  static List<String> _places(String value) => value
      .split(RegExp(r'\s*(?:/|;|\bund\b)\s*', caseSensitive: false))
      .map(_normalise)
      .map((s) => s.replaceAll(RegExp('[^a-z0-9]+'), ' ').trim())
      .where((s) => s.isNotEmpty)
      .toList();
  static SevPoint? _chooseByDirection(
    List<SevPoint> points,
    Iterable<api.Stop> following,
    DateTime today,
    DateTime eventDate,
  ) {
    final rules = {for (final point in points) point: _directions(point.label)};
    if (rules.values.any((v) => v.isEmpty)) return null;
    for (final next in following.where((s) => !s.cancelled)) {
      final name = next.stationName;
      if (name == null) continue;
      final nextName = _normalise(
        name,
      ).replaceAll(RegExp('[^a-z0-9]+'), ' ').trim();
      bool matches(_Direction d) =>
          nextName == d.place || nextName.startsWith('${d.place} ');
      final matching = rules.entries
          .where((r) => r.value.any(matches))
          .toList();
      if (matching.isNotEmpty) {
        final active = matching
            .where(
              (r) => r.value.any(
                (d) =>
                    matches(d) &&
                    (d.until == null ||
                        (!today.isAfter(d.until!) &&
                            !eventDate.isAfter(d.until!))),
              ),
            )
            .toList();
        return active.length == 1 ? active.single.key : null;
      }
    }
    return null;
  }

  static DateTime? _parseGermanDate(String value) {
    final parts = value.split('.').map(int.tryParse).toList();
    return parts.length != 3 || parts.any((p) => p == null)
        ? null
        : _validDate(parts[2]!, parts[1]!, parts[0]!);
  }

  static DateTime? _validDate(int year, int month, int day) {
    if (year < 1 ||
        year > 9999 ||
        month < 1 ||
        month > 12 ||
        day < 1 ||
        day > 31) {
      return null;
    }
    final value = DateTime.utc(year, month, day);
    return value.year == year && value.month == month && value.day == day
        ? value
        : null;
  }

  static final tz.Location _berlin = (() {
    tzdata.initializeTimeZones();
    return tz.getLocation('Europe/Berlin');
  })();
  static DateTime _berlinDate(int millis) {
    final local = tz.TZDateTime.fromMillisecondsSinceEpoch(_berlin, millis);
    return DateTime.utc(local.year, local.month, local.day);
  }

  static String _normalise(String value) {
    final text = value
        .toLowerCase()
        .replaceAll('ä', 'ae')
        .replaceAll('ö', 'oe')
        .replaceAll('ü', 'ue')
        .replaceAll('ß', 'ss');
    return unorm.nfd(text).replaceAll(RegExp(r'\p{M}+', unicode: true), '');
  }
}

/// Anonymous client with bounded bytes, explicit host-checked redirects and RAM cache.
class BahnhofSevRepository {
  BahnhofSevRepository({http.Client? client, int Function()? nowMillis})
    : _client = client ?? http.Client(),
      _ownsClient = client == null,
      _nowMillis = nowMillis ?? (() => DateTime.now().millisecondsSinceEpoch);
  final http.Client _client;
  final bool _ownsClient;
  final int Function() _nowMillis;
  final Stopwatch _clock = Stopwatch()..start();
  final Map<String, (SevMap?, int)> _cache = {};
  final Map<String, Future<SevMap?>> _inFlight = {};
  final Set<Completer<void>> _abortTriggers = {};
  bool _closed = false;
  Future<SevMap?> getMap(String slug) async {
    if (_closed || !BahnhofSevParser.isValidSlug(slug)) return null;
    final cached = _cache[slug];
    if (cached != null &&
        _clock.elapsedMilliseconds - cached.$2 <
            (cached.$1 == null ? 900000 : 21600000)) {
      return cached.$1;
    }
    _cache.remove(slug);
    final pending = _inFlight[slug];
    if (pending != null) return pending;
    final task = _download(
      slug,
    ).timeout(const Duration(seconds: 20), onTimeout: () => null);
    _inFlight[slug] = task;
    try {
      final result = await task;
      if (_closed) return null;
      _cache[slug] = (result, _clock.elapsedMilliseconds);
      while (_cache.length > 64) {
        _cache.remove(_cache.keys.first);
      }
      return result;
    } catch (_) {
      if (!_closed) _cache[slug] = (null, _clock.elapsedMilliseconds);
      return null;
    } finally {
      if (identical(_inFlight[slug], task)) _inFlight.remove(slug);
    }
  }

  Future<SevMap?> _download(String slug) async {
    final abort = Completer<void>();
    _abortTriggers.add(abort);
    final deadline = Timer(const Duration(seconds: 20), () {
      if (!abort.isCompleted) abort.complete();
    });
    try {
      var url = Uri.https('www.bahnhof.de', '/$slug/karte');
      for (var attempt = 0; attempt < 4; attempt++) {
        if (_closed || abort.isCompleted || !_allowed(url)) return null;
        final request = http.AbortableRequest(
          'GET',
          url,
          abortTrigger: abort.future,
        )..followRedirects = false;
        request.headers.addAll({
          'User-Agent': 'Routely-SEV/1.0 (Flutter)',
          'Accept': 'text/html',
        });
        final response = await _client.send(request);
        if ({301, 302, 303, 307, 308}.contains(response.statusCode)) {
          final location = response.headers['location'];
          await response.stream.drain<void>();
          if (attempt == 3 || location == null) return null;
          url = url.resolve(location);
          if (!_allowed(url)) return null;
          continue;
        }
        if (response.statusCode < 200 ||
            response.statusCode >= 300 ||
            response.headers['content-type']
                    ?.split(';')
                    .first
                    .trim()
                    .toLowerCase() !=
                'text/html' ||
            (response.contentLength ?? 0) > BahnhofSevParser.maxHtmlBytes) {
          await response.stream.drain<void>();
          return null;
        }
        final bytes = <int>[];
        await for (final chunk in response.stream) {
          if (bytes.length + chunk.length > BahnhofSevParser.maxHtmlBytes) {
            return null;
          }
          bytes.addAll(chunk);
        }
        return BahnhofSevParser.parse(utf8.decode(bytes), slug, _nowMillis());
      }
      return null;
    } finally {
      deadline.cancel();
      if (!abort.isCompleted) abort.complete();
      _abortTriggers.remove(abort);
    }
  }

  static bool _allowed(Uri uri) =>
      uri.scheme == 'https' &&
      {'www.bahnhof.de', 'bahnhof.de'}.contains(uri.host) &&
      uri.port == 443 &&
      uri.userInfo.isEmpty;
  void close() {
    _closed = true;
    for (final abort in _abortTriggers) {
      if (!abort.isCompleted) abort.complete();
    }
    if (_ownsClient) _client.close();
    _cache.clear();
  }
}

class SevJourneyEnricher {
  SevJourneyEnricher(this.repository);
  final BahnhofSevRepository repository;
  int _generation = 0;
  static List<String> stationSlugs(
    api.Checkin checkin,
    List<api.Stop> fullRoute,
  ) {
    if (!SevStopResolver.isReplacementBus(checkin)) return [];
    final origins = <int>[], destinations = <int>[];
    for (var i = 0; i < fullRoute.length; i++) {
      if (fullRoute[i].matchesStopover(checkin.origin)) origins.add(i);
      if (fullRoute[i].matchesStopover(checkin.destination)) {
        destinations.add(i);
      }
    }
    if (origins.length != 1 ||
        destinations.length != 1 ||
        destinations.single < origins.single) {
      return [];
    }
    final slugs = <String>{};
    for (final stop in fullRoute.sublist(
      origins.single,
      destinations.single + 1,
    )) {
      if (stop.cancelled || stop.station == null) continue;
      final slug = SevStopResolver.stationSlug(stop.station!);
      if (slug != null) slugs.add(slug);
    }
    return slugs.take(64).toList();
  }

  void cancel() => _generation++;
  Future<Map<String, SevMap>> loadMaps(
    api.Checkin checkin,
    List<api.Stop> fullRoute,
  ) async {
    final generation = ++_generation,
        slugs = stationSlugs(checkin, fullRoute),
        maps = <String, SevMap>{};
    if (slugs.isEmpty) return maps;
    var cursor = 0;
    Future<void> worker() async {
      while (cursor < slugs.length && generation == _generation) {
        final slug = slugs[cursor++], map = await repository.getMap(slug);
        if (generation == _generation && map != null) maps[slug] = map;
      }
    }

    try {
      await Future.wait(
        List.generate(3, (_) => worker()),
      ).timeout(const Duration(seconds: 45));
    } on TimeoutException {
      if (generation == _generation) _generation++;
    }
    return Map.unmodifiable(maps);
  }
}
