import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:http/http.dart' as http;

import 'models.dart';

/// A credential generation. Its printable representation never contains secrets.
class AuthSession {
  const AuthSession({
    required this.serverUrl,
    required this.accessToken,
    required this.revision,
  });
  final String serverUrl;
  final String accessToken;
  final String revision;
  String get token => accessToken;
  @override
  bool operator ==(Object other) =>
      other is AuthSession &&
      serverUrl == other.serverUrl &&
      accessToken == other.accessToken &&
      revision == other.revision;
  @override
  int get hashCode => Object.hash(serverUrl, accessToken, revision);
  @override
  String toString() => 'AuthSession(revision=$revision)';
}

String normalizeServerUrl(String value) {
  final uri = Uri.tryParse(value.trim());
  if (uri == null ||
      uri.scheme != 'https' ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment) {
    throw const FormatException(
      'Bitte eine gültige HTTPS-Server-URL ohne Zugangsdaten, Query oder Fragment eingeben.',
    );
  }
  final normalized = uri.replace(
    host: uri.host.toLowerCase(),
    path: uri.path.replaceAll(RegExp(r'/+$'), ''),
  );
  return normalized.toString().replaceAll(RegExp(r'/+$'), '');
}

class ApiException implements Exception {
  const ApiException(
    this.message, {
    this.statusCode,
    this.errors,
    this.transient = false,
  });
  final String message;
  final int? statusCode;
  final Json? errors;
  final bool transient;
  bool get temporary =>
      transient ||
      statusCode == 408 ||
      statusCode == 429 ||
      (statusCode ?? 0) >= 500;
  @override
  String toString() => message;
}

class StaleRequestException implements Exception {
  const StaleRequestException();
  @override
  String toString() =>
      'Die Sitzung oder Fahrt wurde inzwischen geändert. Bitte erneut laden.';
}

class CheckInConflictException extends ApiException {
  CheckInConflictException(this.conflicts)
    : super(
        'Diese Fahrt überschneidet sich mit einem bestehenden Check-in.',
        statusCode: 409,
      );
  final List<Status> conflicts;
}

/// Success with missing response must never encourage a duplicate POST/PUT.
class AcceptedMutationException extends ApiException {
  const AcceptedMutationException()
    : super(
        'Die Änderung wurde angenommen, aber die Antwort ist unvollständig. Bitte aktualisiere die Fahrt, bevor du erneut speicherst.',
      );
}

/// All bearer requests are restricted to the chosen HTTPS origin and base path.
/// Redirects are deliberately disabled; errors never include response body/tokens.
class RoutelyApi {
  RoutelyApi({
    required this.session,
    http.Client? client,
    this.isCurrent,
    this.isFresh,
  }) : _client = client ?? http.Client(),
       _ownsClient = client == null;
  final AuthSession session;
  final http.Client _client;
  final bool _ownsClient;
  final bool Function(AuthSession)? isCurrent;
  final Future<bool> Function(AuthSession)? isFresh;

  void _checkCurrent() {
    if (isCurrent != null && !isCurrent!(session)) {
      throw const StaleRequestException();
    }
  }

  Future<void> _checkFresh() async {
    _checkCurrent();
    if (isFresh != null && !await isFresh!(session)) {
      throw const StaleRequestException();
    }
    _checkCurrent();
  }

  Uri _uri(String path, [Map<String, String?> query = const {}]) {
    final base = Uri.parse('${normalizeServerUrl(session.serverUrl)}/');
    final result = base
        .resolve(path)
        .replace(
          queryParameters: query.isEmpty
              ? null
              : Map.fromEntries(
                  query.entries
                      .where((entry) => entry.value != null)
                      .map((entry) => MapEntry(entry.key, entry.value!)),
                ),
        );
    if (result.scheme != 'https' ||
        result.host != base.host ||
        result.port != base.port ||
        !result.path.startsWith(base.path)) {
      throw const FormatException('Ungültiger API-Pfad.');
    }
    return result;
  }

  Future<Json> _request(
    String method,
    String path, {
    Map<String, String?> query = const {},
    Json? body,
    int maxBytes = 4 * 1024 * 1024,
    Duration timeout = const Duration(seconds: 60),
  }) async {
    await _checkFresh();
    final abort = Completer<void>();
    final request =
        http.AbortableRequest(
            method,
            _uri(path, query),
            abortTrigger: abort.future,
          )
          ..followRedirects = false
          ..headers.addAll({
            'Authorization': 'Bearer ${session.accessToken}',
            'Accept': 'application/json',
            'User-Agent':
                'Routely-Flutter/1.0 (+https://github.com/shedowe19/routely)',
          });
    if (body != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(body);
    }
    final deadline = Timer(timeout, () => abort.complete());
    final statusMutation =
        method == 'POST' && path == 'api/v1/trains/checkin' ||
        method == 'PUT' && path.startsWith('api/v1/status/');
    var mutationAccepted = false;
    try {
      final response = await _client.send(request).timeout(timeout);
      mutationAccepted =
          statusMutation &&
          response.statusCode >= 200 &&
          response.statusCode < 300;
      final bytes = <int>[];
      await for (final chunk in response.stream.timeout(
        const Duration(seconds: 30),
      )) {
        bytes.addAll(chunk);
        if (bytes.length > maxBytes) {
          if (mutationAccepted) throw const AcceptedMutationException();
          throw const ApiException('Die Serverantwort ist zu groß.');
        }
      }
      await _checkFresh();
      Json decoded = {};
      if (bytes.isNotEmpty) {
        try {
          final value = jsonDecode(utf8.decode(bytes));
          if (value is Map<String, dynamic>) {
            decoded = value;
          } else if (response.statusCode < 300) {
            throw const FormatException();
          }
        } catch (_) {
          if (response.statusCode < 300) {
            if (mutationAccepted) {
              throw const AcceptedMutationException();
            }
            throw const ApiException('Unvollständige Serverantwort.');
          }
        }
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        if (response.statusCode == 409 && path == 'api/v1/trains/checkin') {
          final conflicts =
              (decoded['data'] is Map ? decoded['data']['conflicts'] : null)
                  as List?;
          throw CheckInConflictException(
            (conflicts ?? [])
                .whereType<Map>()
                .map((e) => Status.fromJson(Map<String, dynamic>.from(e)))
                .toList(growable: false),
          );
        }
        final message = switch (response.statusCode) {
          401 || 403 =>
            'Die Anmeldung ist nicht mehr gültig. Bitte melde dich erneut an.',
          404 => 'Der angeforderte Eintrag wurde nicht gefunden.',
          409 => 'Die Änderung steht im Konflikt mit vorhandenen Daten.',
          422 => 'Bitte prüfe deine Eingaben. Die Änderung wurde abgelehnt.',
          429 => 'Zu viele Anfragen. Bitte versuche es später erneut.',
          _ => 'Die Anfrage ist fehlgeschlagen (${response.statusCode}).',
        };
        throw ApiException(message, statusCode: response.statusCode);
      }
      return decoded;
    } on StaleRequestException {
      rethrow;
    } on ApiException {
      rethrow;
    } on TimeoutException {
      await _checkFresh();
      if (mutationAccepted) throw const AcceptedMutationException();
      throw const ApiException(
        'Die Anfrage hat zu lange gedauert. Bitte aktualisiere die Daten.',
        transient: true,
      );
    } on http.ClientException {
      await _checkFresh();
      if (mutationAccepted) throw const AcceptedMutationException();
      throw const ApiException(
        'Keine Verbindung zum Server. Bitte prüfe deine Internetverbindung.',
        transient: true,
      );
    } finally {
      deadline.cancel();
    }
  }

  Json _object(Json root) {
    final data = root['data'];
    if (data is Map) return Map<String, dynamic>.from(data);
    throw const ApiException('Unvollständige Serverantwort.');
  }

  List<T> _list<T>(Json root, T Function(Json) decode) {
    final data = root['data'];
    if (data is! List) {
      throw const ApiException('Unvollständige Serverantwort.');
    }
    return data
        .whereType<Map>()
        .map((e) => decode(Map<String, dynamic>.from(e)))
        .toList(growable: false);
  }

  Page<T> _page<T>(Json root, T Function(Json) decode) {
    if (root['data'] is! List) {
      throw const ApiException('Unvollständige Serverantwort.');
    }
    return Page<T>.fromJson(root, decode);
  }

  String _segment(String value) => Uri.encodeComponent(value);

  Future<User> authUser() async =>
      User.fromJson(_object(await _request('GET', 'api/v1/auth/user')));
  Future<void> logout() async {
    await _request('POST', 'api/v1/auth/logout');
  }

  Future<Page<Status>> dashboard({int page = 1}) async => _page(
    await _request('GET', 'api/v1/dashboard', query: {'page': '$page'}),
    Status.fromJson,
  );
  Future<Page<Status>> globalFeed({int page = 1}) async => _page(
    await _request('GET', 'api/v1/statuses', query: {'page': '$page'}),
    Status.fromJson,
  );
  Future<Status> status(int id) async {
    final result = Status.fromJson(
      _object(await _request('GET', 'api/v1/status/$id')),
    );
    if (result.id != id) {
      throw const ApiException(
        'Die Serverantwort gehört zu einer anderen Fahrt.',
      );
    }
    return result;
  }

  Future<void> deleteStatus(int id) async {
    await _request('DELETE', 'api/v1/status/$id');
  }

  Future<Status> updateStatus(int id, UpdateStatusRequest request) async {
    final response = await _request(
      'PUT',
      'api/v1/status/$id',
      body: request.toJson(),
    );
    if (response['data'] is! Map || response['data']['id'] != id) {
      throw const AcceptedMutationException();
    }
    return Status.fromJson(_object(response));
  }

  Future<void> setLiked(int id, bool liked) async {
    await _request(liked ? 'POST' : 'DELETE', 'api/v1/status/$id/like');
  }

  Future<List<Station>> searchStations(String query) async => _list(
    await _request(
      'GET',
      'api/v1/trains/station/autocomplete/${_segment(query)}',
    ),
    Station.fromJson,
  );
  Future<List<Station>> stationsInBoundingBox({
    required double minLat,
    required double maxLat,
    required double minLon,
    required double maxLon,
  }) async => _list(
    await _request(
      'GET',
      'api/v1/stations',
      query: {
        'min_lat': '$minLat',
        'max_lat': '$maxLat',
        'min_lon': '$minLon',
        'max_lon': '$maxLon',
      },
    ),
    Station.fromJson,
  );
  Future<List<Station>> nearbyStations(
    double latitude,
    double longitude,
  ) async {
    if (!latitude.isFinite ||
        !longitude.isFinite ||
        latitude.abs() > 90 ||
        longitude.abs() > 180) {
      throw const FormatException('Ungültiger Standort.');
    }
    const latOffset = 0.008983;
    final lonOffset =
        1 / (111.32 * math.max(0.01, math.cos(latitude * math.pi / 180).abs()));
    final stations = await stationsInBoundingBox(
      minLat: math.max(-90, latitude - latOffset),
      maxLat: math.min(90, latitude + latOffset),
      minLon: math.max(-180, longitude - lonOffset),
      maxLon: math.min(180, longitude + lonOffset),
    );
    double distance(Station station) =>
        station.latitude == null || station.longitude == null
        ? double.infinity
        : math.pow(station.latitude! - latitude, 2).toDouble() +
              math
                  .pow(
                    (station.longitude! - longitude) *
                        math.cos(latitude * math.pi / 180),
                    2,
                  )
                  .toDouble();
    stations.sort((a, b) => distance(a).compareTo(distance(b)));
    final seen = <String>{};
    return stations
        .where((station) {
          final key = station.id != null && station.id! > 0
              ? 'id:${station.id}'
              : station.uuid != null && station.uuid!.trim().isNotEmpty
              ? 'uuid:${station.uuid}'
              : null;
          return key == null || seen.add(key);
        })
        .toList(growable: false);
  }

  Future<List<Departure>> departures(
    int stationId, {
    String? when,
    String? travelType,
  }) async => _list(
    await _request(
      'GET',
      'api/v1/station/$stationId/departures',
      query: {'when': when, 'travelType': travelType},
    ),
    Departure.fromJson,
  );
  Future<Trip> trip(String hafasTripId, String lineName) async => Trip.fromJson(
    _object(
      await _request(
        'GET',
        'api/v1/trains/trip',
        query: {'hafasTripId': hafasTripId, 'lineName': lineName},
      ),
    ),
  );
  Future<List<Stop>> stopovers(int tripId) async {
    final data = _object(await _request('GET', 'api/v1/stopovers/$tripId'));
    return deduplicateStops(
      data.values
          .whereType<List>()
          .expand((value) => value)
          .whereType<Map>()
          .map((e) => Stop.fromJson(Map<String, dynamic>.from(e)))
          .toList(growable: false),
    );
  }

  /// Native geometry only: no GPS coordinate, device fix or foreign origin request.
  Future<Json> polyline(int statusId) async => _object(
    await _request(
      'GET',
      'api/v1/polyline/$statusId',
      timeout: const Duration(seconds: 20),
    ),
  );
  Future<Status> checkIn(CheckInRequest request) async {
    final response = await _request(
      'POST',
      'api/v1/trains/checkin',
      body: request.toJson(),
    );
    final data = response['data'];
    if (data is! Map || data['status'] is! Map) {
      throw const AcceptedMutationException();
    }
    final status = Status.fromJson({
      ...Map<String, dynamic>.from(data['status'] as Map),
      if (data['points'] is Map) 'checkInPoints': data['points'],
      if (data['points'] is Map && data['points']['points'] is int)
        'earnedPoints': data['points']['points'],
    });
    if (status.id <= 0) throw const AcceptedMutationException();
    return status;
  }

  Future<Statistics> statistics() async =>
      Statistics.fromJson(_object(await _request('GET', 'api/v1/statistics')));
  Future<User> userProfile(String username) async => User.fromJson(
    _object(await _request('GET', 'api/v1/user/${_segment(username)}')),
  );
  Future<Page<Status>> userStatuses(String username, {int page = 1}) async =>
      _page(
        await _request(
          'GET',
          'api/v1/user/${_segment(username)}/statuses',
          query: {'page': '$page'},
        ),
        Status.fromJson,
      );
  Future<List<User>> searchUsers(String query) async => _list(
    await _request('GET', 'api/v1/user/search/${_segment(query)}'),
    User.fromJson,
  );
  Future<void> setFollowing(int userId, bool following) async {
    await _request(following ? 'POST' : 'DELETE', 'api/v1/user/$userId/follow');
  }

  Future<Page<AppNotification>> notifications({int page = 1}) async => _page(
    await _request('GET', 'api/v1/notifications', query: {'page': '$page'}),
    AppNotification.fromJson,
  );
  Future<int> unreadCount() async {
    final data = (await _request(
      'GET',
      'api/v1/notifications/unread/count',
    ))['data'];
    if (data is! int || data < 0) {
      throw const ApiException('Unvollständige Benachrichtigungsanzahl.');
    }
    return data;
  }

  Future<void> markNotificationRead(String id) async {
    await _request('PUT', 'api/v1/notifications/read/${_segment(id)}');
  }

  Future<void> markAllNotificationsRead() async {
    await _request('PUT', 'api/v1/notifications/read/all');
  }

  void close() {
    if (_ownsClient) _client.close();
  }
}
