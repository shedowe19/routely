import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../data/app_store.dart';
import '../data/models.dart' as api;
import '../data/routely_api.dart';
import '../tracking/models.dart';
import '../tracking/route_enrichment.dart';
import '../tracking/tracking_route_geometry.dart';

/// Session-local public geometry adapters. The anonymous client never receives
/// the authenticated client or a device position.
class RuntimeRouteGeometry {
  RuntimeRouteGeometry(this.store, {http.Client? anonymousClient})
    : _anonymous = anonymousClient ?? http.Client(),
      _ownsAnonymous = anonymousClient == null {
    _road = RoadRouteStore(fetch: _fetchRoad);
    _transit = TransitRouteStore(fetch: _fetchTransit);
  }
  final AppStore store;
  final http.Client _anonymous;
  final bool _ownsAnonymous;
  late final RoadRouteStore _road;
  late final TransitRouteStore _transit;
  final Set<Completer<void>> _aborts = {};
  bool _closed = false;

  Future<RoadRouteFetchResult> _fetchRoad(
    RoutePoint from,
    RoutePoint to,
  ) async {
    if (_closed) return const RoadRouteFetchResult(null);
    final coordinates =
        '${from.longitude.toStringAsFixed(7)},${from.latitude.toStringAsFixed(7)};${to.longitude.toStringAsFixed(7)},${to.latitude.toStringAsFixed(7)}';
    final uri = Uri.https(
      'routing.openstreetmap.de',
      '/routed-car/route/v1/driving/$coordinates',
      {
        'geometries': 'geojson',
        'overview': 'full',
        'steps': 'false',
        'alternatives': '2',
        'generate_hints': 'false',
      },
    );
    final abort = Completer<void>();
    _aborts.add(abort);
    final timeout = Timer(const Duration(seconds: 20), () {
      if (!abort.isCompleted) abort.complete();
    });
    try {
      final request =
          http.AbortableRequest('GET', uri, abortTrigger: abort.future)
            ..followRedirects = false
            ..headers.addAll({
              'Accept': 'application/json',
              'User-Agent':
                  'Routely-Flutter/1.0 (+https://github.com/shedowe19/routely)',
            });
      final response = await _anonymous
          .send(request)
          .timeout(const Duration(seconds: 20));
      if (_closed || response.statusCode < 200 || response.statusCode >= 300) {
        final transient =
            response.statusCode == 408 ||
            response.statusCode == 429 ||
            (response.statusCode >= 500 && response.statusCode <= 599);
        final retryAfter = roadRetryAfterMillis(
          response.headers['retry-after'],
          DateTime.now().millisecondsSinceEpoch,
        );
        return RoadRouteFetchResult(
          null,
          transientFailure: transient,
          retryAfterMillis:
              retryAfter ?? (response.statusCode == 429 ? 60000 : null),
        );
      }
      final bytes = <int>[];
      await for (final chunk in response.stream.timeout(
        const Duration(seconds: 15),
      )) {
        bytes.addAll(chunk);
        if (bytes.length > RoadRouteParser.maxBodyBytes || _closed) {
          return const RoadRouteFetchResult(null);
        }
      }
      return RoadRouteFetchResult(
        RoadRouteParser.parse(
          utf8.decode(bytes),
          from,
          to,
          DateTime.now().millisecondsSinceEpoch,
        ),
      );
    } catch (_) {
      return const RoadRouteFetchResult(null, transientFailure: true);
    } finally {
      timeout.cancel();
      if (!abort.isCompleted) abort.complete();
      _aborts.remove(abort);
    }
  }

  Future<TransitRouteFetchResult> _fetchTransit(
    TransitRouteRequest request,
  ) async {
    if (_closed || !store.authenticated) {
      return const TransitRouteFetchResult(null, cacheable: false);
    }
    try {
      final data = await store.api.polyline(request.statusId);
      if (_closed) return const TransitRouteFetchResult(null, cacheable: false);
      return TransitRouteFetchResult(
        TransitRouteParser.parse(
          jsonEncode({'data': data}),
          request,
          DateTime.now().millisecondsSinceEpoch,
        ),
      );
    } on ApiException catch (error) {
      return TransitRouteFetchResult(
        null,
        cacheable: ![401, 403, 406].contains(error.statusCode),
      );
    } on StaleRequestException {
      return const TransitRouteFetchResult(null, cacheable: false);
    } catch (_) {
      return const TransitRouteFetchResult(null);
    }
  }

  Future<List<GpsSegmentGeometry>> load({
    required int statusId,
    required api.Checkin checkin,
    required List<api.Stop> fullStops,
    required List<api.Stop> checkedInStops,
    required List<TrackingStop> trackingStops,
    required int nextIndex,
    Set<String> verifiedReplacementKeys = const {},
  }) async {
    if (_closed) return const [];
    final request = TransitRouteSelection.request(
      statusId: statusId,
      checkin: checkin,
      fullStops: fullStops,
      checkedInStops: checkedInStops,
      trackingKeys: trackingStops
          .map((stop) => stop.key)
          .toList(growable: false),
    );
    if (request != null) {
      return (await _transit.getRoute(request))?.segments ?? const [];
    }
    // Native rail geometry and replacement-road geometry have separate sources.
    if (!([
          checkin.mode,
          checkin.category,
        ].any((v) => v?.toLowerCase() == 'bus') &&
        RegExp(
          r'^(?:bus\s+)?(?:re|rb)\s*\d+',
          caseSensitive: false,
        ).hasMatch(checkin.lineName ?? ''))) {
      return const [];
    }
    final served = trackingStops.where((stop) => !stop.cancelled).toList();
    final nextKey = nextIndex >= 0 && nextIndex < trackingStops.length
        ? trackingStops[nextIndex].key
        : null;
    final current = served
        .indexWhere((stop) => stop.key == nextKey)
        .clamp(0, served.length);
    final segments = <GpsSegmentGeometry>[];
    for (
      var index = (current - 1).clamp(0, served.length);
      index < served.length - 1 && index < current + 2;
      index++
    ) {
      final from = served[index], to = served[index + 1];
      if (!verifiedReplacementKeys.contains(from.key) ||
          !verifiedReplacementKeys.contains(to.key)) {
        continue;
      }
      if (!from.hasCoordinates || !to.hasCoordinates) continue;
      final geometry = await _road.getRoute(
        RoutePoint(from.latitude!, from.longitude!),
        RoutePoint(to.latitude!, to.longitude!),
      );
      if (_closed) return const [];
      if (geometry != null) {
        segments.add(
          GpsSegmentGeometry(
            fromKey: from.key,
            toKey: to.key,
            geometry: geometry,
            source: GpsGeometrySource.roadModel,
          ),
        );
      }
    }
    return segments;
  }

  void close() {
    _closed = true;
    _transit.close();
    for (final abort in _aborts) {
      if (!abort.isCompleted) abort.complete();
    }
    if (_ownsAnonymous) _anonymous.close();
  }
}

/// RFC 9110 seconds or IMF-fixdate, bounded like the native road repository.
int? roadRetryAfterMillis(String? value, int nowMillis) {
  final header = value?.trim();
  if (header == null || header.isEmpty || header.length > 128) return null;
  if (RegExp(r'^\d+$').hasMatch(header)) {
    final seconds = int.tryParse(header);
    if (seconds == null || seconds >= 900) return 900000;
    return (seconds * 1000).clamp(60000, 900000);
  }
  final match = RegExp(
    r'^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{1,2}) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$',
  ).firstMatch(header);
  if (match == null) return null;
  const months = [
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  const weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  final day = int.parse(match[2]!), month = months.indexOf(match[3]!) + 1;
  final year = int.parse(match[4]!), hour = int.parse(match[5]!);
  final minute = int.parse(match[6]!), second = int.parse(match[7]!);
  final date = DateTime.utc(year, month, day, hour, minute, second);
  if (date.year != year ||
      date.month != month ||
      date.day != day ||
      date.hour != hour ||
      date.minute != minute ||
      date.second != second ||
      date.weekday != weekdays.indexOf(match[1]!) + 1) {
    return null;
  }
  return (date.millisecondsSinceEpoch - nowMillis).clamp(60000, 900000);
}
