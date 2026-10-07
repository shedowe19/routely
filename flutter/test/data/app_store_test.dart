import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';
import 'package:shared_preferences/shared_preferences.dart';

class MemorySecureStorage implements SecureSessionStorage {
  String? value;
  bool failWrite = false;
  Future<void> Function(String?)? beforeWrite;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String? next) async {
    if (failWrite) {
      throw const FileSystemException('simulated persistence failure');
    }
    await beforeWrite?.call(next);
    value = next;
  }
}

http.Response userResponse(String username) => http.Response(
  jsonEncode({
    'data': {'id': username == 'a' ? 1 : 2, 'username': username},
  }),
  200,
);
http.Response feedResponse(int id) => http.Response(
  jsonEncode({
    'data': [
      {
        'id': id,
        'body': 'cached',
        'user': {'username': 'a'},
      },
    ],
  }),
  200,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory directory;
  late SharedPreferences preferences;
  late MemorySecureStorage secure;
  final stores = <AppStore>[];

  Future<AppStore> create(http.Client client) async {
    final store = AppStore(
      client: client,
      secureStorage: secure,
      preferences: preferences,
      cacheDirectory: directory,
    );
    stores.add(store);
    return store;
  }

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    preferences = await SharedPreferences.getInstance();
    secure = MemorySecureStorage();
    directory = await Directory.systemTemp.createTemp('routely-data-test-');
  });
  tearDown(() async {
    for (final store in stores) {
      store.dispose();
    }
    stores.clear();
    await directory.delete(recursive: true);
  });

  test(
    'an active-status guard invalidated during secure write restores the current record',
    () async {
      final store = await create(MockClient((_) async => userResponse('a')));
      await store.login('https://example.com', 'a');
      await store.setActiveStatus(17);
      final started = Completer<void>(), release = Completer<void>();
      var current = true, writes = 0;
      secure.beforeWrite = (_) async {
        if (++writes == 1) {
          started.complete();
          await release.future;
        }
      };
      final clearing = store.setActiveStatus(
        null,
        expectedActiveStatusId: 17,
        isStillCurrent: () => current,
      );
      final stale = expectLater(
        clearing,
        throwsA(isA<StaleRequestException>()),
      );
      await started.future;
      current = false;
      release.complete();
      await stale;

      expect(store.activeStatusId, 17);
      expect((jsonDecode(secure.value!) as Map)['activeStatusId'], 17);
      expect(writes, 2);
    },
  );

  test(
    'login persists only validated complete user and atomic secret record',
    () async {
      final store = await create(
        MockClient(
          (request) async =>
              request.headers['Authorization'] == 'Bearer invalid'
              ? http.Response('{"data":{"id":3}}', 200)
              : userResponse('a'),
        ),
      );
      await expectLater(
        store.login('https://example.com', 'invalid'),
        throwsA(isA<ApiException>()),
      );
      expect(store.authenticated, isFalse);
      expect(secure.value, isNull);
      await store.login('https://example.com/base', ' valid ');
      final json = jsonDecode(secure.value!) as Map;
      expect(json['server'], 'https://example.com/base');
      expect(json['token'], 'valid');
      expect(json['user']['username'], 'a');
      expect(preferences.getKeys(), isNot(contains('token')));
    },
  );

  test('late login response cannot supersede newer successful login', () async {
    final pending = Completer<http.Response>();
    final started = Completer<void>();
    final store = await create(
      MockClient((request) {
        if (request.headers['Authorization'] == 'Bearer a') {
          started.complete();
          return pending.future;
        }
        return Future.value(userResponse('b'));
      }),
    );
    final old = store.login('https://example.com', 'a');
    final expectation = expectLater(old, throwsA(isA<StaleRequestException>()));
    await started.future;
    await store.login('https://example.com', 'b');
    pending.complete(userResponse('a'));
    await expectation;
    expect(store.user!.username, 'b');
    expect((jsonDecode(secure.value!) as Map)['token'], 'b');
  });

  test(
    'logout clears local state before remote response and A/logout/A has new generation',
    () async {
      final remoteLogout = Completer<http.Response>();
      final started = Completer<void>();
      final store = await create(
        MockClient((request) {
          if (request.url.path.endsWith('auth/logout')) {
            started.complete();
            return remoteLogout.future;
          }
          return Future.value(userResponse('a'));
        }),
      );
      await store.login('https://example.com', 'a');
      final first = store.sessionRevision;
      await store.setActiveStatus(17);
      await store.setSetting('ride_recognition_enabled', true);
      final logout = store.logout();
      await started.future;
      expect(store.authenticated, isFalse);
      expect(secure.value, isNull);
      await store.login('https://example.com', 'a');
      remoteLogout.complete(http.Response('', 204));
      await logout;
      expect(store.sessionRevision, isNot(first));
      expect(store.authenticated, isTrue);
      expect(store.activeStatusId, isNull);
      expect(store.settings.rideRecognitionEnabled, isFalse);
    },
  );

  test(
    'failed local logout keeps session rather than reporting success',
    () async {
      final store = await create(MockClient((_) async => userResponse('a')));
      await store.login('https://example.com', 'a');
      secure.failWrite = true;
      await expectLater(store.logout(), throwsA(isA<FileSystemException>()));
      expect(store.authenticated, isTrue);
      expect(secure.value, isNotNull);
    },
  );

  test(
    'offline cache is session isolated, marked offline, never used for 401 or malformed feed',
    () async {
      int code = 200;
      bool malformed = false;
      final store = await create(
        MockClient((request) async {
          if (request.url.path.endsWith('auth/user')) return userResponse('a');
          return malformed
              ? http.Response('{}', 200)
              : code == 200
              ? feedResponse(17)
              : http.Response('{}', code);
        }),
      );
      await store.login('https://example.com', 'a');
      expect((await store.dashboard()).offline, isFalse);
      final files = await directory
          .list()
          .where((entry) => entry is File)
          .toList();
      expect(
        await (files.single as File).readAsString(),
        isNot(contains('Bearer')),
      );
      code = 503;
      final cached = await store.dashboard();
      expect(cached.data.single.id, 17);
      expect(cached.offline, isTrue);
      code = 401;
      await expectLater(store.dashboard(), throwsA(isA<ApiException>()));
      code = 503;
      await store.login('https://example.com', 'b');
      await expectLater(store.dashboard(), throwsA(isA<ApiException>()));
      code = 200;
      malformed = true;
      await expectLater(store.dashboard(), throwsA(isA<ApiException>()));
    },
  );

  test(
    'status revision persists exact route edits across engines and ignores likes',
    () async {
      final client = MockClient((request) async {
        if (request.url.path.endsWith('auth/user')) return userResponse('a');
        if (request.url.path.endsWith('/like')) return http.Response('', 204);
        return http.Response('{"data":{"id":17,"body":"new"}}', 200);
      });
      final store = await create(client);
      await store.login('https://example.com', 'a');
      await store.setActiveStatus(17);
      final background = await create(client);
      await background.initialize(validate: false, readOnly: true);
      expect(store.statusRevision(17), 0);
      await store.setLiked(17, true);
      await store.setLiked(18, true);
      expect(store.statusRevision(17), 0);
      await store.updateStatus(17, UpdateStatusRequest(body: 'new'));
      final stamp = store.statusRevision(17);
      expect(stamp, greaterThan(0));
      expect(store.statusRevision(18), 0);
      await background.synchronizeSession();
      expect(background.statusRevision(17), stamp);
      expect(
        (jsonDecode(secure.value!) as Map)['statusRevisions']['17'],
        stamp,
      );
      await store.login('https://example.com', 'b');
      expect(store.statusRevision(17), 0);
      await background.synchronizeSession();
      expect(background.statusRevision(17), 0);
    },
  );

  test(
    'a queued stale stop cannot erase a newer active status or operation',
    () async {
      final store = await create(MockClient((_) async => userResponse('a')));
      await store.login('https://example.com', 'a');
      await store.setActiveStatus(17);
      final replacement = store.setActiveStatus(18);
      final stale = store.setActiveStatus(null, expectedActiveStatusId: 17);
      final assertion = expectLater(
        stale,
        throwsA(isA<StaleRequestException>()),
      );
      await replacement;
      await assertion;
      expect(store.activeStatusId, 18);
      await expectLater(
        store.setActiveStatus(
          null,
          expectedActiveStatusId: 18,
          isStillCurrent: () => false,
        ),
        throwsA(isA<StaleRequestException>()),
      );
      expect((jsonDecode(secure.value!) as Map)['activeStatusId'], 18);
    },
  );

  test(
    'late feed GET after a committed correction cannot publish stale status',
    () async {
      final pending = Completer<http.Response>();
      final started = Completer<void>();
      final store = await create(
        MockClient((request) {
          if (request.url.path.endsWith('auth/user')) {
            return Future.value(userResponse('a'));
          }
          if (request.method == 'PUT') {
            return Future.value(
              http.Response('{"data":{"id":17,"body":"new"}}', 200),
            );
          }
          started.complete();
          return pending.future;
        }),
      );
      await store.login('https://example.com', 'a');
      final result = store.dashboard();
      final expectation = expectLater(
        result,
        throwsA(isA<StaleRequestException>()),
      );
      await started.future;
      await store.updateStatus(17, UpdateStatusRequest(body: 'new'));
      pending.complete(feedResponse(17));
      await expectation;
      expect(
        await directory
            .list()
            .where((entry) => entry.path.endsWith('-dashboard.json'))
            .isEmpty,
        isTrue,
      );
    },
  );

  test(
    'status writes are ordered and queued writes are rejected after logout',
    () async {
      final first = Completer<http.Response>();
      final started = Completer<void>();
      int mutations = 0;
      final store = await create(
        MockClient((request) {
          if (request.url.path.endsWith('auth/user')) {
            return Future.value(userResponse('a'));
          }
          if (request.url.path.endsWith('auth/logout')) {
            return Future.value(http.Response('', 204));
          }
          mutations++;
          if (mutations == 1) {
            started.complete();
            return first.future;
          }
          return Future.value(http.Response('', 204));
        }),
      );
      await store.login('https://example.com', 'a');
      final one = store.setLiked(17, true);
      final oneExpectation = expectLater(
        one,
        throwsA(isA<StaleRequestException>()),
      );
      await started.future;
      final two = store.setLiked(17, false);
      final twoExpectation = expectLater(
        two,
        throwsA(isA<StaleRequestException>()),
      );
      expect(mutations, 1);
      await store.logout();
      first.complete(http.Response('', 204));
      await oneExpectation;
      await twoExpectation;
      expect(mutations, 1);
    },
  );

  test(
    'committed correction removes tracking snapshot and emits confirmed event',
    () async {
      final store = await create(
        MockClient(
          (request) async => request.method == 'PUT'
              ? http.Response('{"data":{"id":17,"body":"new"}}', 200)
              : userResponse('a'),
        ),
      );
      await store.login('https://example.com', 'a');
      await store.setActiveStatus(17);
      await store.saveTrackingState({'route': 'old'});
      final event = store.mutations.first;
      await store.updateStatus(17, UpdateStatusRequest(body: 'new'));
      expect((await event).kind, StatusMutationKind.updated);
      expect(store.activeStatusId, 17);
      expect(store.trackingState, isNull);
      expect((jsonDecode(secure.value!) as Map)['trackingState'], isNull);
    },
  );

  test(
    'validation clears only current authorization failure and retains session for temporary errors',
    () async {
      int status = 200;
      final store = await create(
        MockClient(
          (_) async =>
              status == 200 ? userResponse('a') : http.Response('{}', status),
        ),
      );
      await store.login('https://example.com', 'a');
      status = 503;
      await expectLater(store.validateSession(), throwsA(isA<ApiException>()));
      expect(store.authenticated, isTrue);
      status = 401;
      await expectLater(store.validateSession(), throwsA(isA<ApiException>()));
      expect(store.authenticated, isFalse);
      expect(secure.value, isNull);
    },
  );

  test(
    'secure persisted session restores preferences and tracking without credential leakage',
    () async {
      final first = await create(MockClient((_) async => userResponse('a')));
      await first.login('https://example.com', 'a');
      await first.setSetting('app_theme', 'DARK');
      await first.setActiveStatus(17);
      await first.saveTrackingState({'route': 'old'});
      final second = await create(MockClient((_) async => userResponse('a')));
      await second.initialize();
      expect(second.sessionRevision, first.sessionRevision);
      expect(second.settings.theme, 'DARK');
      expect(second.activeStatusId, 17);
      expect(second.trackingState!['route'], 'old');
      expect(
        preferences.getString('routely.settings.v1'),
        isNot(contains('token')),
      );
    },
  );

  test(
    'read-only background session notices external logout before publishing response',
    () async {
      final ui = await create(MockClient((_) async => userResponse('a')));
      await ui.login('https://example.com', 'a');
      final pending = Completer<http.Response>();
      final started = Completer<void>();
      final background = await create(
        MockClient((_) {
          started.complete();
          return pending.future;
        }),
      );
      await background.initialize(validate: false, readOnly: true);
      final first = background.sessionRevision;
      final result = background.api.status(17);
      final expectation = expectLater(
        result,
        throwsA(isA<StaleRequestException>()),
      );
      await started.future;
      secure.value = null;
      pending.complete(http.Response('{"data":{"id":17}}', 200));
      await expectation;
      expect(background.sessionRevision, isNot(first));
      expect(background.authenticated, isFalse);
    },
  );

  test('read-only initialization makes no API or secure mutation', () async {
    final ui = await create(MockClient((_) async => userResponse('a')));
    await ui.login('https://example.com', 'a');
    final snapshot = secure.value;
    secure.failWrite = true;
    final background = await create(
      MockClient((_) async => throw StateError('unexpected API')),
    );
    await background.initialize(validate: false, readOnly: true);
    expect(background.authenticated, isTrue);
    expect(secure.value, snapshot);
  });
}
