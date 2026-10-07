import 'dart:async';

import 'package:flutter/material.dart' hide Page;
import 'package:flutter_test/flutter_test.dart';
import 'package:routely/data/app_store.dart';
import 'package:routely/data/models.dart';
import 'package:routely/data/routely_api.dart';
import 'package:routely/features/feed/feed_controller.dart';
import 'package:routely/features/profile/profile_controller.dart';
import 'package:routely/features/profile/profile_widgets.dart';
import 'package:routely/features/users/user_search_controller.dart';

Status status(int id, {bool liked = false, int likes = 2}) => Status.fromJson({
  'id': id,
  'liked': liked,
  'likes': likes,
  'isLikable': true,
});
User user({
  bool following = false,
  bool pending = false,
  bool private = false,
}) => User.fromJson({
  'id': 12,
  'username': 'reisende',
  'following': following,
  'followPending': pending,
  'privateProfile': private,
});

Future<void> flush() async {
  for (var index = 0; index < 8; index++) {
    await Future<void>.value();
  }
}

void main() {
  test('new refresh wins over a late previous response', () async {
    final old = Completer<Page<Status>>();
    final latest = Completer<Page<Status>>();
    var requests = 0;
    final controller = FeedController(
      loadPage: (_, _) => ++requests == 1 ? old.future : latest.future,
      writeLike: (_, _) async {},
      sessionRevision: () => 'account-1',
    );
    addTearDown(controller.dispose);
    final first = controller.refresh();
    final second = controller.refresh();
    latest.complete(Page(data: [status(2)]));
    await second;
    old.complete(Page(data: [status(1)]));
    await first;
    expect(controller.statuses.map((item) => item.id), [2]);
    expect(controller.refreshing, isFalse);
  });

  test('like completion restarts the selected global tab load', () async {
    final write = Completer<void>();
    final firstGlobal = Completer<Page<Status>>();
    final replacementGlobal = Completer<Page<Status>>();
    final requests = <FeedType>[];
    final controller = FeedController(
      loadPage: (type, _) {
        requests.add(type);
        if (type == FeedType.dashboard) {
          return Future.value(Page(data: [status(1)]));
        }
        return requests.length == 2
            ? firstGlobal.future
            : replacementGlobal.future;
      },
      writeLike: (_, _) => write.future,
      sessionRevision: () => 'account-1',
    );
    addTearDown(controller.dispose);
    await controller.refresh();
    final liking = controller.toggleLike(1);
    final global = controller.switchType(FeedType.global);
    write.complete();
    await liking;
    expect(requests, [FeedType.dashboard, FeedType.global, FeedType.global]);
    firstGlobal.complete(Page(data: [status(1)]));
    await global;
    expect(controller.statuses, isEmpty);
    replacementGlobal.complete(Page(data: [status(2)]));
    await flush();
    expect(controller.type, FeedType.global);
    expect(controller.statuses.map((item) => item.id), [2]);
    expect(controller.refreshing, isFalse);
  });

  test('failed like restores the latest observed baseline', () async {
    final write = Completer<void>();
    var reads = 0;
    final controller = FeedController(
      loadPage: (_, _) async =>
          Page(data: [status(1, likes: ++reads == 1 ? 2 : 10)]),
      writeLike: (_, _) => write.future,
      sessionRevision: () => 'account-1',
    );
    addTearDown(controller.dispose);
    await controller.refresh();
    final liking = controller.toggleLike(1);
    await controller.refresh();
    expect(controller.statuses.single.likes, 11);
    expect(controller.statuses.single.liked, isTrue);
    write.completeError(StateError('offline'));
    await liking;
    expect(controller.statuses.single.likes, 10);
    expect(controller.statuses.single.liked, isFalse);
    expect(controller.error, contains('Like'));
  });

  test(
    'deletion during failed like cannot recreate the deleted card',
    () async {
      final writes = Completer<void>();
      final mutations = StreamController<StatusMutation>.broadcast(sync: true);
      final controller = FeedController(
        loadPage: (_, _) async => Page(data: [status(1)]),
        writeLike: (_, _) => writes.future,
        sessionRevision: () => 'account-1',
        mutations: mutations.stream,
      );
      addTearDown(() async {
        controller.dispose();
        await mutations.close();
      });
      await controller.refresh();
      final liking = controller.toggleLike(1);
      mutations.add(
        const StatusMutation(
          kind: StatusMutationKind.deleted,
          statusId: 1,
          sessionRevision: 'account-1',
        ),
      );
      writes.completeError(StateError('offline'));
      await liking;
      expect(controller.statuses, isEmpty);
    },
  );

  test('response from an old auth generation is discarded', () async {
    var session = 'first';
    final response = Completer<Page<Status>>();
    final controller = FeedController(
      loadPage: (_, _) => response.future,
      writeLike: (_, _) async {},
      sessionRevision: () => session,
    );
    addTearDown(controller.dispose);
    final loading = controller.refresh();
    session = 'second';
    response.complete(Page(data: [status(10)]));
    await loading;
    expect(controller.statuses, isEmpty);
  });

  test('old profile GET preserves a confirmed follow and fresh bio', () async {
    final oldProfile = Completer<User>();
    final write = Completer<void>();
    var reads = 0;
    final controller = ProfileController(
      fetchUser: () => ++reads == 1 ? Future.value(user()) : oldProfile.future,
      fetchStatuses: (_, _) async => Page(data: <Status>[]),
      writeFollow: (_, _) => write.future,
      sessionRevision: () => 'account-1',
    );
    addTearDown(controller.dispose);
    await controller.refresh();
    final refreshing = controller.refresh();
    final following = controller.toggleFollow();
    write.complete();
    await following;
    oldProfile.complete(
      User.fromJson({...user().toJson(), 'bio': 'Frisch vom Server'}),
    );
    await refreshing;
    expect(controller.user!.following, isTrue);
    expect(controller.user!.bio, 'Frisch vom Server');
  });

  test('private follow becomes pending and cannot submit twice', () async {
    var writes = 0;
    final controller = ProfileController(
      fetchUser: () async => user(private: true),
      fetchStatuses: (_, _) async => Page(data: <Status>[]),
      writeFollow: (_, _) async {
        writes++;
      },
      sessionRevision: () => 'account-1',
    );
    addTearDown(controller.dispose);
    await controller.refresh();
    await controller.toggleFollow();
    await controller.toggleFollow();
    expect(controller.user!.followPending, isTrue);
    expect(controller.user!.following, isFalse);
    expect(writes, 1);
  });

  test(
    'explicitly invisible profile clears prior history before publication and skips denied reads',
    () async {
      var invisible = false;
      var historyCalls = 0;
      final controller = ProfileController(
        fetchUser: () async =>
            User.fromJson({...user().toJson(), 'userInvisibleToMe': invisible}),
        fetchStatuses: (_, _) async {
          if (++historyCalls > 1) {
            throw const ApiException('Forbidden', statusCode: 403);
          }
          return Page(data: [status(1)], hasNext: true);
        },
        sessionRevision: () => 'account-1',
      );
      addTearDown(controller.dispose);
      await controller.refresh();
      var publishedPrivateHistory = false;
      controller.addListener(() {
        if (!controller.historyVisible && controller.statuses.isNotEmpty) {
          publishedPrivateHistory = true;
        }
      });
      invisible = true;
      await controller.refresh();
      await controller.loadMore();
      expect(controller.statuses, isEmpty);
      expect(controller.hasMore, isFalse);
      expect(controller.offline, isFalse);
      expect(controller.historyVisible, isFalse);
      expect(controller.loading, isFalse);
      expect(historyCalls, 1);
      expect(publishedPrivateHistory, isFalse);
    },
  );

  test(
    'visibility revocation removes cached history without consulting offline fallback',
    () async {
      var invisible = false;
      var historyCalls = 0;
      final controller = ProfileController(
        fetchUser: () async =>
            User.fromJson({...user().toJson(), 'userInvisibleToMe': invisible}),
        fetchStatuses: (_, _) async {
          historyCalls++;
          return Page(data: [status(1)], hasNext: true, offline: true);
        },
        sessionRevision: () => 'account-1',
      );
      addTearDown(controller.dispose);
      await controller.refresh();
      expect(controller.offline, isTrue);
      invisible = true;
      await controller.refresh();
      expect(controller.statuses, isEmpty);
      expect(controller.offline, isFalse);
      expect(controller.hasMore, isFalse);
      expect(historyCalls, 1);
      invisible = false;
      await controller.refresh();
      expect(controller.historyVisible, isTrue);
      expect(controller.statuses.single.id, 1);
      expect(historyCalls, 2);
    },
  );

  test(
    'invisible profile fences a previously submitted history page',
    () async {
      var invisible = false;
      var historyCalls = 0;
      final oldPage = Completer<Page<Status>>();
      final controller = ProfileController(
        fetchUser: () async =>
            User.fromJson({...user().toJson(), 'userInvisibleToMe': invisible}),
        fetchStatuses: (_, _) {
          if (++historyCalls == 1) {
            return Future.value(Page(data: [status(1)], hasNext: true));
          }
          return oldPage.future;
        },
        sessionRevision: () => 'account-1',
      );
      addTearDown(controller.dispose);
      await controller.refresh();
      final loadingMore = controller.loadMore();
      invisible = true;
      await controller.refresh();
      oldPage.complete(Page(data: [status(2)], hasNext: true));
      await loadingMore;
      await controller.loadMore();
      expect(controller.statuses, isEmpty);
      expect(controller.hasMore, isFalse);
      expect(historyCalls, 2);
    },
  );

  test(
    'profile mutation fences old history and preserves submitted follow',
    () async {
      final mutations = StreamController<StatusMutation>.broadcast(sync: true);
      final history = Completer<Page<Status>>();
      final write = Completer<void>();
      var histories = 0;
      var serverFollowing = false;
      final controller = ProfileController(
        fetchUser: () async => user(following: serverFollowing),
        fetchStatuses: (_, _) => ++histories == 2
            ? history.future
            : Future.value(
                Page(data: histories == 1 ? [status(1)] : <Status>[]),
              ),
        writeFollow: (_, _) async {
          await write.future;
          serverFollowing = true;
        },
        sessionRevision: () => 'account-1',
        mutations: mutations.stream,
      );
      addTearDown(() async {
        controller.dispose();
        await mutations.close();
      });
      await controller.refresh();
      final refresh = controller.refresh();
      await flush();
      final follow = controller.toggleFollow();
      mutations.add(
        const StatusMutation(
          kind: StatusMutationKind.deleted,
          statusId: 1,
          sessionRevision: 'account-1',
        ),
      );
      history.complete(Page(data: [status(1)]));
      await refresh;
      expect(controller.statuses, isEmpty);
      expect(controller.following, isTrue);
      write.complete();
      await follow;
      await flush();
      expect(controller.user!.following, isTrue);
      expect(controller.statuses, isEmpty);
    },
  );

  testWidgets('new query discards a delayed older search response', (
    tester,
  ) async {
    final first = Completer<List<User>>();
    final second = Completer<List<User>>();
    final controller = UserSearchController(
      search: (query) => query == 'old' ? first.future : second.future,
      sessionRevision: () => 'account-1',
    );
    addTearDown(controller.dispose);
    controller.updateQuery('old');
    await tester.pump(const Duration(milliseconds: 350));
    controller.updateQuery('new');
    await tester.pump(const Duration(milliseconds: 350));
    second.complete([
      User.fromJson({'username': 'new'}),
    ]);
    await tester.pump();
    first.complete([
      User.fromJson({'username': 'old'}),
    ]);
    await tester.pump();
    expect(controller.results.single.username, 'new');
  });

  testWidgets('profile chips wrap with large text and respect hidden points', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 900),
              textScaler: TextScaler.linear(2),
            ),
            child: SizedBox(
              width: 320,
              child: SingleChildScrollView(
                child: ProfileHero(
                  user: User.fromJson({
                    'username': 'reisende',
                    'pointsEnabled': false,
                    'totalDistance': 123456789,
                    'totalDuration': 700,
                    'displayName': 'Eine längere Anzeige',
                  }),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    expect(find.text('123.457 km'), findsOneWidget);
    expect(find.text('Punkte'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
