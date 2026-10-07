import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';
import 'routely_api.dart';
import 'feed_cache.dart';

/// One secure record keeps server/token/user/generation atomic across restarts.
abstract interface class SecureSessionStorage {
  Future<String?> read();
  Future<void> write(String? value);
}

class DeviceSecureSessionStorage implements SecureSessionStorage {
  DeviceSecureSessionStorage([FlutterSecureStorage? storage])
    : _storage =
          storage ??
          const FlutterSecureStorage(
            iOptions: IOSOptions(
              accessibility: KeychainAccessibility.first_unlock_this_device,
            ),
          );
  final FlutterSecureStorage _storage;
  static const _key = 'routely.session.v1';
  @override
  Future<String?> read() => _storage.read(key: _key);
  @override
  Future<void> write(String? value) => value == null
      ? _storage.delete(key: _key)
      : _storage.write(key: _key, value: value);
}

/// Per-device presentation preferences. No token or user information is stored here.
class AppSettings {
  AppSettings([Json values = const {}])
    : _values = Map<String, dynamic>.unmodifiable({...defaults, ...values});
  static const Json defaults = {
    'app_theme': 'LIGHT',
    'tts_enabled': false,
    'gps_tracking_enabled': true,
    'ride_recognition_enabled': false,
    'announcement_radius_meters': 0,
    'trip_change_alerts_enabled': true,
    'trip_change_speech_enabled': true,
    'live_progress_enabled': true,
    'lock_screen_details_enabled': true,
    'tts_rate': 0.5,
    'tts_pitch': 1.0,
  };
  final Json _values;
  String get theme =>
      ['LIGHT', 'DARK', 'SYSTEM', 'AMOLED'].contains(_values['app_theme'])
      ? _values['app_theme'] as String
      : 'LIGHT';
  bool get ttsEnabled => _values['tts_enabled'] == true;
  bool get gpsTrackingEnabled => _values['gps_tracking_enabled'] != false;
  bool get rideRecognitionEnabled =>
      _values['ride_recognition_enabled'] == true;
  bool get tripChangeAlertsEnabled =>
      _values['trip_change_alerts_enabled'] != false;
  bool get tripChangeSpeechEnabled =>
      _values['trip_change_speech_enabled'] != false;
  bool get liveProgressEnabled => _values['live_progress_enabled'] != false;
  bool get lockScreenDetailsEnabled =>
      _values['lock_screen_details_enabled'] != false;
  int get announcementRadiusMeters =>
      const {
        0,
        300,
        500,
        1000,
        2000,
      }.contains(_values['announcement_radius_meters'])
      ? _values['announcement_radius_meters'] as int
      : 0;
  String? get ttsEngine =>
      _values['tts_engine'] is String ? _values['tts_engine'] as String : null;
  String? get ttsLanguage => _values['tts_language'] is String
      ? _values['tts_language'] as String
      : null;
  String? get ttsVoice =>
      _values['tts_voice'] is String ? _values['tts_voice'] as String : null;
  double get ttsRate =>
      (_values['tts_rate'] is num ? _values['tts_rate'] as num : 0.5)
          .toDouble()
          .clamp(0.0, 1.0);
  double get ttsPitch =>
      (_values['tts_pitch'] is num ? _values['tts_pitch'] as num : 1.0)
          .toDouble()
          .clamp(0.5, 2.0);
  dynamic operator [](String key) => _values[key];
  Json toJson() => Map.of(_values);
  AppSettings copyWithValue(String key, dynamic value) =>
      AppSettings({..._values, key: value});
}

enum StatusMutationKind { like, updated, deleted, invalidated, created }

class StatusMutation {
  const StatusMutation({
    required this.kind,
    required this.statusId,
    required this.sessionRevision,
    this.status,
    this.liked,
  });
  final StatusMutationKind kind;
  final int statusId;
  final String sessionRevision;
  final Status? status;
  final bool? liked;
}

/// Session-owned app state, ordered status writes, and private feed snapshots.
class AppStore extends ChangeNotifier {
  AppStore({
    http.Client? client,
    SecureSessionStorage? secureStorage,
    SharedPreferences? preferences,
    Object? cacheDirectory,
    FeedCache? feedCache,
  }) : _client = client ?? http.Client(),
       _ownsClient = client == null,
       _secure = secureStorage ?? DeviceSecureSessionStorage(),
       _prefs = preferences,
       _cache = feedCache ?? FeedCache(cacheDirectory);
  final http.Client _client;
  final bool _ownsClient;
  final SecureSessionStorage _secure;
  SharedPreferences? _prefs;
  final FeedCache _cache;
  AuthSession? _session;
  User? _user;
  AppSettings _settings = AppSettings();
  int? _activeStatusId;
  Json? _trackingState;
  bool _initialized = false;
  bool _readOnly = false;
  bool _disposed = false;
  int _authOperation = 0;
  int _contentRevision = 0;
  final Map<int, int> _statusRevisions = {};
  String? _initializationError;
  Future<void> _persistenceQueue = Future.value();
  final Map<String, Future<void>> _writeQueues = {};
  final StreamController<StatusMutation> _mutations =
      StreamController.broadcast(sync: true);
  final Set<String> _invalidCachePartitions = {};

  AuthSession? get session => _session;
  String get sessionRevision => _session?.revision ?? '';
  User? get user => _user;
  AppSettings get settings => _settings;
  bool get initialized => _initialized;
  bool get authenticated => _session != null;
  bool get readOnly => _readOnly;
  void _requireWritable() {
    if (_readOnly) {
      throw StateError(
        'Diese Hintergrundinstanz darf keine Kontodaten ändern.',
      );
    }
  }

  String? get initializationError => _initializationError;
  int get contentRevision => _contentRevision;

  /// Route edits have their own persisted stamp; likes/other feeds cannot retire
  /// a running trip, while an edit to this exact status invalidates old work.
  int statusRevision(int statusId) => _statusRevisions[statusId] ?? 0;
  int? get activeStatusId => _activeStatusId;
  Json? get trackingState =>
      _trackingState == null ? null : Map.of(_trackingState!);
  Stream<StatusMutation> get mutations => _mutations.stream;
  RoutelyApi get api => _apiFor(_authenticated());
  AuthSession _authenticated() =>
      _session ?? (throw const ApiException('Bitte melde dich zuerst an.'));
  bool isCurrent(AuthSession session) => !_disposed && _session == session;
  void requireCurrent(AuthSession session) {
    if (!isCurrent(session)) throw const StaleRequestException();
  }

  RoutelyApi _apiFor(AuthSession session) => RoutelyApi(
    session: session,
    client: _client,
    isCurrent: isCurrent,
    isFresh: _isFresh,
  );

  Future<bool> _isFresh(AuthSession expected) async {
    if (!isCurrent(expected)) return false;
    String? raw;
    try {
      raw = await _secure.read();
    } catch (_) {
      throw const ApiException(
        'Die gespeicherte Anmeldung kann gerade nicht gelesen werden.',
      );
    }
    bool matches = false;
    if (raw != null) {
      try {
        final record = jsonDecode(raw) as Map;
        matches =
            record['revision'] == expected.revision &&
            record['server'] == expected.serverUrl &&
            record['token'] == expected.accessToken;
      } catch (_) {
        /* A malformed persisted credential cannot authorize a request. */
      }
    }
    if (!matches) await synchronizeSession();
    return matches && isCurrent(expected);
  }

  /// Call on foreground resume. Background engines read the same secure generation
  /// before and after each network call; only the UI engine writes this record.
  Future<void> synchronizeSession() => _persist(() async {
    _prefs ??= await SharedPreferences.getInstance();
    await _prefs!.reload();
    final storedSettings = _prefs!.getString('routely.settings.v1');
    if (storedSettings != null) {
      try {
        _settings = AppSettings(
          Map<String, dynamic>.from(jsonDecode(storedSettings) as Map),
        );
      } catch (_) {
        /* Keep valid presentation preferences. */
      }
    }
    final raw = await _secure.read();
    AuthSession? next;
    User? nextUser;
    Json? record;
    if (raw != null) {
      try {
        record = Map<String, dynamic>.from(jsonDecode(raw) as Map);
        nextUser = User.fromJson(
          Map<String, dynamic>.from(record['user'] as Map),
        );
        final token = (record['token'] as String?)?.trim();
        if (record['version'] != 1 ||
            nextUser.username.isEmpty ||
            token == null ||
            token.isEmpty ||
            record['revision'] is! String) {
          throw const FormatException();
        }
        next = AuthSession(
          serverUrl: normalizeServerUrl(record['server'] as String),
          accessToken: token,
          revision: record['revision'] as String,
        );
      } catch (_) {
        throw const ApiException(
          'Die gespeicherte Anmeldung ist unvollständig. Bitte melde dich erneut an.',
        );
      }
    }
    final changed = next != _session;
    if (changed) {
      ++_authOperation;
      ++_contentRevision;
    }
    _session = next;
    _user = nextUser;
    _statusRevisions
      ..clear()
      ..addAll(_readStatusRevisions(record?['statusRevisions']));
    _activeStatusId = record?['activeStatusId'] as int?;
    _trackingState = record?['trackingState'] is Map
        ? Map<String, dynamic>.from(record!['trackingState'] as Map)
        : null;
    _settings = _settings.copyWithValue(
      'ride_recognition_enabled',
      record?['recognitionEnabled'] == true,
    );
    if (next != null) _contentRevision = record?['cacheRevision'] as int? ?? 0;
    if (!_disposed) notifyListeners();
  });

  String _nonce() {
    final random = Random.secure();
    return List.generate(
      24,
      (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0'),
    ).join();
  }

  Future<T> _persist<T>(Future<T> Function() operation) {
    final completer = Completer<T>();
    _persistenceQueue = _persistenceQueue.then((_) async {
      try {
        completer.complete(await operation());
      } catch (error, stack) {
        completer.completeError(error, stack);
      }
    });
    return completer.future;
  }

  Json _record({
    required AuthSession session,
    required User user,
    int? activeStatusId,
    Json? trackingState,
    bool recognitionEnabled = false,
    int? cacheRevision,
    Map<int, int>? statusRevisions,
  }) => {
    'version': 1,
    'server': session.serverUrl,
    'token': session.accessToken,
    'revision': session.revision,
    'user': user.toJson(),
    'activeStatusId': activeStatusId,
    'trackingState': trackingState,
    'recognitionEnabled': recognitionEnabled,
    'cacheRevision': cacheRevision ?? _contentRevision,
    'statusRevisions': {
      for (final entry in (statusRevisions ?? _statusRevisions).entries)
        '${entry.key}': entry.value,
    },
  };

  Future<void> initialize({bool validate = true, bool readOnly = false}) async {
    if (_initialized) return;
    _readOnly = readOnly;
    final operation = ++_authOperation;
    try {
      _prefs ??= await SharedPreferences.getInstance();
      final settingJson = _prefs!.getString('routely.settings.v1');
      if (settingJson != null) {
        try {
          _settings = AppSettings(
            Map<String, dynamic>.from(jsonDecode(settingJson) as Map),
          );
        } catch (_) {
          _settings = AppSettings();
        }
      }
      final raw = await _secure.read();
      if (operation != _authOperation || _disposed) return;
      if (raw != null) {
        try {
          final record = Map<String, dynamic>.from(jsonDecode(raw) as Map);
          final token = (record['token'] as String?)?.trim();
          final user = User.fromJson(
            Map<String, dynamic>.from(record['user'] as Map),
          );
          if (record['version'] != 1 ||
              token == null ||
              token.isEmpty ||
              user.username.trim().isEmpty) {
            throw const FormatException();
          }
          _session = AuthSession(
            serverUrl: normalizeServerUrl(record['server'] as String),
            accessToken: token,
            revision: record['revision'] as String,
          );
          _user = user;
          _contentRevision = record['cacheRevision'] as int? ?? 0;
          _statusRevisions
            ..clear()
            ..addAll(_readStatusRevisions(record['statusRevisions']));
          _activeStatusId = record['activeStatusId'] as int?;
          _trackingState = record['trackingState'] is Map
              ? Map<String, dynamic>.from(record['trackingState'] as Map)
              : null;
          _settings = _settings.copyWithValue(
            'ride_recognition_enabled',
            record['recognitionEnabled'] == true,
          );
        } catch (_) {
          if (!readOnly) await _secure.write(null);
          _session = null;
          _user = null;
          _activeStatusId = null;
          _trackingState = null;
          _statusRevisions.clear();
        }
      }
    } catch (_) {
      _initializationError =
          'Die gespeicherte Anmeldung konnte nicht geladen werden. Bitte versuche es erneut.';
    } finally {
      if (operation == _authOperation && !_disposed) {
        _initialized = true;
        notifyListeners();
      }
    }
    if (_session != null && validate && !readOnly) {
      try {
        await validateSession();
      } on StaleRequestException {
        /* A newer account owns the UI. */
      } on ApiException catch (error) {
        // Temporary failures retain the validated local session and offline feed.
        _initializationError = error.message;
        if (!_disposed) notifyListeners();
      }
    }
  }

  Future<void> login(String serverUrl, String token) async {
    _requireWritable();
    final server = normalizeServerUrl(serverUrl);
    final accessToken = token.trim();
    if (accessToken.isEmpty) {
      throw const FormatException('Bitte Access-Token eingeben.');
    }
    final operation = ++_authOperation;
    final candidate = AuthSession(
      serverUrl: server,
      accessToken: accessToken,
      revision: _nonce(),
    );
    final client = RoutelyApi(
      session: candidate,
      client: _client,
      isCurrent: (_) => operation == _authOperation && !_disposed,
    );
    late User user;
    try {
      user = await client.authUser();
    } finally {
      client.close();
    }
    if (user.username.trim().isEmpty) {
      throw const ApiException('Leere oder unvollständige Nutzerantwort.');
    }
    await _persist(() async {
      if (operation != _authOperation || _disposed) {
        throw const StaleRequestException();
      }
      final previousRecord = _session == null
          ? null
          : jsonEncode(
              _record(
                session: _session!,
                user: _user!,
                activeStatusId: _activeStatusId,
                trackingState: _trackingState,
                recognitionEnabled: _settings.rideRecognitionEnabled,
              ),
            );
      await _secure.write(
        jsonEncode(
          _record(
            session: candidate,
            user: user,
            cacheRevision: _contentRevision + 1,
            statusRevisions: const {},
          ),
        ),
      );
      if (operation != _authOperation || _disposed) {
        await _secure.write(previousRecord);
        throw const StaleRequestException();
      }
      _session = candidate;
      _user = user;
      _activeStatusId = null;
      _trackingState = null;
      _statusRevisions.clear();
      _settings = _settings.copyWithValue('ride_recognition_enabled', false);
      _contentRevision++;
      _initializationError = null;
      _initialized = true;
      notifyListeners();
    });
  }

  Future<void> validateSession() async {
    _requireWritable();
    final expected = _authenticated();
    final client = _apiFor(expected);
    try {
      final user = await client.authUser();
      if (user.username.trim().isEmpty) {
        throw const ApiException('Leere oder unvollständige Nutzerantwort.');
      }
      await _persist(() async {
        requireCurrent(expected);
        await _secure.write(
          jsonEncode(
            _record(
              session: expected,
              user: user,
              activeStatusId: _activeStatusId,
              trackingState: _trackingState,
              recognitionEnabled: _settings.rideRecognitionEnabled,
            ),
          ),
        );
        requireCurrent(expected);
        _user = user;
        if (!_disposed) notifyListeners();
      });
    } on ApiException catch (error) {
      requireCurrent(expected);
      if (error.statusCode == 401 || error.statusCode == 403) {
        await _clearIfCurrent(expected);
      }
      rethrow;
    } finally {
      client.close();
    }
  }

  Future<void> _clearIfCurrent(AuthSession expected) => _persist(() async {
    if (!isCurrent(expected)) return;
    await _secure.write(null);
    if (!isCurrent(expected)) return;
    _session = null;
    _user = null;
    _activeStatusId = null;
    _trackingState = null;
    _statusRevisions.clear();
    _settings = _settings.copyWithValue('ride_recognition_enabled', false);
    _contentRevision++;
    notifyListeners();
  });

  Future<void> logout() async {
    _requireWritable();
    ++_authOperation;
    final expected = _session;
    if (expected == null) return;
    await _clearIfCurrent(expected);
    final client = RoutelyApi(session: expected, client: _client);
    try {
      await client.logout();
    } catch (_) {
      /* The local deletion already committed. */
    } finally {
      client.close();
    }
  }

  /// Migration bridge provides its snapshot once; credentials are revalidated.
  /// Native route/GPS snapshots are deliberately not trusted across runtimes.
  Future<void> importLegacySession(
    String serverUrl,
    String accessToken, {
    Json? userJson,
    Json? settings,
    int? activeStatusId,
  }) async {
    if (_session != null) return;
    await login(serverUrl, accessToken);
    if (settings != null) {
      for (final entry in settings.entries) {
        if (entry.key == 'ride_recognition_enabled' ||
            !AppSettings.defaults.containsKey(entry.key) &&
                !entry.key.startsWith('tts_')) {
          continue;
        }
        await setSetting(entry.key, entry.value);
      }
    }
    if (activeStatusId != null && activeStatusId > 0) {
      final expected = _authenticated();
      final active = await status(activeStatusId);
      requireCurrent(expected);
      if (active.user?.username == _user?.username && active.checkin != null) {
        await setActiveStatus(active.id, expectedSession: expected);
      }
    }
  }

  Future<void> setSetting(String key, dynamic value) async {
    _requireWritable();
    if (!AppSettings.defaults.containsKey(key) &&
        !['tts_engine', 'tts_language', 'tts_voice'].contains(key)) {
      throw ArgumentError('Unbekannte Einstellung: $key');
    }
    if (key == 'ride_recognition_enabled') {
      final expected = _authenticated();
      await _persist(() async {
        requireCurrent(expected);
        await _secure.write(
          jsonEncode(
            _record(
              session: expected,
              user: _user!,
              activeStatusId: _activeStatusId,
              trackingState: _trackingState,
              recognitionEnabled: value == true,
            ),
          ),
        );
        requireCurrent(expected);
        _settings = _settings.copyWithValue(key, value == true);
      });
    } else {
      await _persist(() async {
        final next = _settings.copyWithValue(key, value);
        _prefs ??= await SharedPreferences.getInstance();
        final persisted = next.toJson()..remove('ride_recognition_enabled');
        if (!await _prefs!.setString(
          'routely.settings.v1',
          jsonEncode(persisted),
        )) {
          throw StateError('Einstellungen konnten nicht gespeichert werden.');
        }
        _settings = next;
      });
    }
    if (!_disposed) notifyListeners();
  }

  Future<void> setActiveStatus(
    int? id, {
    AuthSession? expectedSession,
    int? expectedActiveStatusId,
    bool Function()? isStillCurrent,
  }) async {
    _requireWritable();
    final expected = expectedSession ?? _authenticated();
    requireCurrent(expected);
    if (id != null && id <= 0) throw ArgumentError.value(id, 'id');
    await _persist(() async {
      requireCurrent(expected);
      if (expectedActiveStatusId != null &&
              _activeStatusId != expectedActiveStatusId ||
          isStillCurrent?.call() == false) {
        throw const StaleRequestException();
      }
      final tracking = id == _activeStatusId ? _trackingState : null;
      await _secure.write(
        jsonEncode(
          _record(
            session: expected,
            user: _user!,
            activeStatusId: id,
            trackingState: tracking,
            recognitionEnabled: _settings.rideRecognitionEnabled,
          ),
        ),
      );
      requireCurrent(expected);
      if (expectedActiveStatusId != null &&
              _activeStatusId != expectedActiveStatusId ||
          isStillCurrent?.call() == false) {
        // A new runtime operation can retire this write while secure storage
        // is awaiting the platform. Restore the still-current session state
        // before releasing the persistence queue to later operations.
        await _secure.write(
          jsonEncode(
            _record(
              session: expected,
              user: _user!,
              activeStatusId: _activeStatusId,
              trackingState: _trackingState,
              recognitionEnabled: _settings.rideRecognitionEnabled,
            ),
          ),
        );
        requireCurrent(expected);
        throw const StaleRequestException();
      }
      _activeStatusId = id;
      _trackingState = tracking;
      notifyListeners();
    });
  }

  Future<void> saveTrackingState(
    Json state, {
    AuthSession? expectedSession,
    int? statusId,
  }) async {
    _requireWritable();
    final expected = expectedSession ?? _authenticated();
    requireCurrent(expected);
    final id = statusId ?? _activeStatusId;
    final revision = _contentRevision;
    if (id == null || id != _activeStatusId) {
      throw const StaleRequestException();
    }
    await _persist(() async {
      requireCurrent(expected);
      if (id != _activeStatusId || revision != _contentRevision) {
        throw const StaleRequestException();
      }
      await _secure.write(
        jsonEncode(
          _record(
            session: expected,
            user: _user!,
            activeStatusId: id,
            trackingState: state,
            recognitionEnabled: _settings.rideRecognitionEnabled,
          ),
        ),
      );
      requireCurrent(expected);
      if (id != _activeStatusId || revision != _contentRevision) {
        throw const StaleRequestException();
      }
      _trackingState = Map.of(state);
    });
  }

  String _partition(AuthSession session) => sha256
      .convert(
        utf8.encode(
          '${session.serverUrl}\u0000${session.accessToken}\u0000${session.revision}',
        ),
      )
      .toString();
  Future<Page<Status>> _feed(
    String kind,
    int page,
    Future<Page<Status>> Function(RoutelyApi) fetch,
  ) async {
    final expected = _authenticated();
    final revision = _contentRevision;
    void guard() {
      requireCurrent(expected);
      if (_contentRevision != revision) throw const StaleRequestException();
    }

    final client = _apiFor(expected);
    try {
      final result = await fetch(client);
      guard();
      if (page == 1 && !_readOnly) {
        // Cache failure never hides a valid response. Cache files contain no token.
        try {
          await _persist(() async {
            guard();
            await _cache.write(_partition(expected), kind, {
              'version': 1,
              'cacheRevision': revision,
              'data': result.data.map((e) => e.toJson()).toList(),
            }, () => isCurrent(expected) && _contentRevision == revision);
            guard();
            _invalidCachePartitions.remove(_partition(expected));
          });
        } on StaleRequestException {
          rethrow;
        } catch (_) {
          /* Valid fresh response still usable. */
        }
      }
      return result;
    } on ApiException catch (error, originalTrace) {
      guard();
      if (page != 1 || !error.temporary) rethrow;
      try {
        return await _persist(() async {
          guard();
          if (_invalidCachePartitions.contains(_partition(expected))) {
            Error.throwWithStackTrace(error, originalTrace);
          }
          final json = await _cache.read(_partition(expected), kind, revision);
          guard();
          if (json == null) Error.throwWithStackTrace(error, originalTrace);
          json['offline'] = true;
          final result = Page<Status>.fromJson(json, Status.fromJson);
          if (result.data.isEmpty) {
            Error.throwWithStackTrace(error, originalTrace);
          }
          return result;
        });
      } on StaleRequestException {
        rethrow;
      } catch (_) {
        Error.throwWithStackTrace(error, originalTrace);
      }
    } finally {
      client.close();
    }
  }

  Future<Page<Status>> dashboard({int page = 1}) =>
      _feed('dashboard', page, (api) => api.dashboard(page: page));
  Future<Page<Status>> globalFeed({int page = 1}) =>
      _feed('global', page, (api) => api.globalFeed(page: page));
  Future<Page<Status>> userStatuses(String username, {int page = 1}) => _feed(
    'user-${sha256.convert(utf8.encode(username))}',
    page,
    (api) => api.userStatuses(username, page: page),
  );

  Future<Status> status(int id) async {
    final expected = _authenticated();
    final revision = _contentRevision;
    final client = _apiFor(expected);
    try {
      final result = await client.status(id);
      requireCurrent(expected);
      if (revision != _contentRevision) throw const StaleRequestException();
      return result;
    } on ApiException {
      requireCurrent(expected);
      if (revision != _contentRevision) throw const StaleRequestException();
      rethrow;
    } finally {
      client.close();
    }
  }

  Future<T> _ordered<T>(
    AuthSession expected,
    int id,
    Future<T> Function() operation,
  ) async {
    _requireWritable();
    final key = '${expected.revision}:$id';
    final previous = _writeQueues[key] ?? Future.value();
    final done = Completer<void>();
    _writeQueues[key] = done.future;
    try {
      await previous;
      requireCurrent(expected);
      return await operation();
    } finally {
      done.complete();
      if (identical(_writeQueues[key], done.future)) _writeQueues.remove(key);
    }
  }

  Future<void> _committed(AuthSession expected, StatusMutation event) async {
    _invalidCachePartitions.add(_partition(expected));
    // Revision advances before disk work, so in-flight reads cannot win a race.
    if (isCurrent(expected)) {
      _contentRevision++;
      if (event.statusId > 0 &&
          [
            StatusMutationKind.updated,
            StatusMutationKind.invalidated,
            StatusMutationKind.deleted,
          ].contains(event.kind)) {
        _statusRevisions.remove(event.statusId);
        _statusRevisions[event.statusId] = _contentRevision;
        while (_statusRevisions.length > 64) {
          _statusRevisions.remove(
            _statusRevisions.keys.firstWhere((id) => id != _activeStatusId),
          );
        }
      }
      if (event.kind != StatusMutationKind.like &&
          event.statusId == _activeStatusId) {
        _trackingState = null;
        if (event.kind == StatusMutationKind.deleted) _activeStatusId = null;
      }
    }
    final committedRevision = _contentRevision;
    await _persist(() async {
      // Remove original partition even when logout/login happened during commit.
      try {
        await _cache.invalidate(_partition(expected), committedRevision);
      } catch (_) {
        /* Content revision blocks same-process snapshots. */
      }
      if (isCurrent(expected)) {
        try {
          await _secure.write(
            jsonEncode(
              _record(
                session: expected,
                user: _user!,
                activeStatusId: _activeStatusId,
                trackingState: _trackingState,
                recognitionEnabled: _settings.rideRecognitionEnabled,
              ),
            ),
          );
        } catch (_) {
          /* A server commit must never become a retryable mutation. */
        }
      }
    });
    requireCurrent(expected);
    _mutations.add(event);
    notifyListeners();
  }

  Future<void> setLiked(
    int id,
    bool liked, {
    AuthSession? expectedSession,
  }) async {
    final expected = expectedSession ?? _authenticated();
    return _ordered(expected, id, () async {
      final client = _apiFor(expected);
      try {
        await client.setLiked(id, liked);
        await _committed(
          expected,
          StatusMutation(
            kind: StatusMutationKind.like,
            statusId: id,
            sessionRevision: expected.revision,
            liked: liked,
          ),
        );
      } finally {
        client.close();
      }
    });
  }

  Future<Status> updateStatus(
    int id,
    UpdateStatusRequest request, {
    AuthSession? expectedSession,
  }) async {
    final expected = expectedSession ?? _authenticated();
    return _ordered(expected, id, () async {
      final client = _apiFor(expected);
      try {
        final status = await client.updateStatus(id, request);
        await _committed(
          expected,
          StatusMutation(
            kind: StatusMutationKind.updated,
            statusId: id,
            sessionRevision: expected.revision,
            status: status,
          ),
        );
        return status;
      } on AcceptedMutationException {
        await _committed(
          expected,
          StatusMutation(
            kind: StatusMutationKind.invalidated,
            statusId: id,
            sessionRevision: expected.revision,
          ),
        );
        rethrow;
      } finally {
        client.close();
      }
    });
  }

  Future<void> deleteStatus(int id, {AuthSession? expectedSession}) async {
    final expected = expectedSession ?? _authenticated();
    return _ordered(expected, id, () async {
      final client = _apiFor(expected);
      try {
        await client.deleteStatus(id);
        await _committed(
          expected,
          StatusMutation(
            kind: StatusMutationKind.deleted,
            statusId: id,
            sessionRevision: expected.revision,
          ),
        );
      } finally {
        client.close();
      }
    });
  }

  Future<Status> checkIn(
    CheckInRequest request, {
    AuthSession? expectedSession,
  }) async {
    final expected = expectedSession ?? _authenticated();
    return _ordered(expected, 0, () async {
      final client = _apiFor(expected);
      try {
        final status = await client.checkIn(request);
        await _committed(
          expected,
          StatusMutation(
            kind: StatusMutationKind.created,
            statusId: status.id,
            sessionRevision: expected.revision,
            status: status,
          ),
        );
        return status;
      } on AcceptedMutationException {
        await _committed(
          expected,
          StatusMutation(
            kind: StatusMutationKind.invalidated,
            statusId: 0,
            sessionRevision: expected.revision,
          ),
        );
        rethrow;
      } finally {
        client.close();
      }
    });
  }

  @override
  void dispose() {
    _disposed = true;
    ++_authOperation;
    _mutations.close();
    if (_ownsClient) _client.close();
    super.dispose();
  }
}

Map<int, int> _readStatusRevisions(dynamic value) {
  final result = <int, int>{};
  if (value is! Map) return result;
  for (final entry in value.entries) {
    final id = int.tryParse(entry.key.toString()), revision = entry.value;
    if (id != null && id > 0 && revision is int && revision >= 0) {
      result[id] = revision;
      if (result.length > 64) result.remove(result.keys.first);
    }
  }
  return result;
}
