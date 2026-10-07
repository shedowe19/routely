import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:routely/data/models.dart';
import 'package:routely/features/notifications/notifications_controller.dart';

void main() {
  test('pagination filters unusable IDs and deduplicates pages', () async {
    final requested = <int>[];
    final gateway = _Gateway()
      ..list = (page) async {
        requested.add(page);
        return _page(
          page == 1 ? ['a', 'b', ''] : ['b', 'c'],
          hasNext: page == 1,
          currentPage: page,
        );
      };
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    await controller.refresh();
    await controller.loadMore();
    await controller.loadMore();
    expect(requested, [1, 2]);
    expect(_ids(controller), ['a', 'b', 'c']);
    expect(controller.state.hasMore, isFalse);
  });

  test('a successful read survives an older list response', () async {
    final gateway = _Gateway();
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    await controller.refresh();
    final oldGet = Completer<Page<AppNotification>>();
    gateway.list = (_) => oldGet.future;
    final refresh = controller.refresh();
    gateway.count = () async => 0;
    await controller.markAsRead('a');
    oldGet.complete(_page(['a']));
    await refresh;
    expect(controller.state.notifications.single.isRead, isTrue);
    expect(controller.state.unreadCount, 0);
    await controller.markAsRead('a');
    await controller.markAsRead('unknown');
    expect(gateway.readCalls, ['a']);
  });

  test(
    'failed single read restores only its own row and badge share',
    () async {
      final gateway = _Gateway();
      gateway.list = (_) async => _page(['a', 'b']);
      gateway.count = () async => 5;
      final controller = _controller(gateway);
      addTearDown(controller.dispose);
      await controller.refresh();
      final readA = Completer<void>();
      final readB = Completer<void>();
      gateway.read = (id) => id == 'a' ? readA.future : readB.future;
      gateway.count = () async => throw StateError('count unavailable');
      final first = controller.markAsRead('a');
      final second = controller.markAsRead('b');
      expect(controller.state.unreadCount, 3);
      readA.completeError(StateError('PUT failed'));
      await first;
      expect(controller.state.notifications.first.isRead, isFalse);
      expect(controller.state.notifications.last.isRead, isTrue);
      expect(controller.state.unreadCount, 4);
      readB.complete();
      await second;
      expect(controller.state.unreadCount, 4);
      expect(controller.state.error, contains('PUT failed'));
    },
  );

  test('unknown and already read IDs never consume the badge', () async {
    final gateway = _Gateway();
    gateway.list = (_) async => Page(
      data: [AppNotification(id: 'a', readAt: 'server-read')],
    );
    gateway.count = () async => 5;
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    await controller.refresh();
    await controller.markAsRead('missing');
    await controller.markAsRead('a');
    await controller.markAsRead('');
    expect(controller.state.unreadCount, 5);
    expect(gateway.readCalls, isEmpty);
  });

  test('zero badge stays zero when a single read is rolled back', () async {
    final gateway = _Gateway()..count = () async => 0;
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    await controller.refresh();
    gateway.read = (_) async => throw StateError('PUT failed');
    gateway.count = () async => throw StateError('count unavailable');
    await controller.markAsRead('a');
    expect(controller.state.unreadCount, 0);
    expect(controller.state.notifications.single.isRead, isFalse);
  });

  test(
    'mark-all reloads unseen old rows from the committed server snapshot',
    () async {
      var rows = _page(['a', 'previously-unloaded']).data;
      var first = true;
      final reply = Completer<void>();
      final gateway = _Gateway()
        ..list = (_) async {
          if (first) {
            first = false;
            return _page(['a']);
          }
          return Page(data: rows);
        }
        ..count = (() async => rows.where((row) => !row.isRead).length)
        ..all = () {
          rows = rows.map((row) => row.withReadAt('server-read')).toList();
          return reply.future;
        };
      final controller = _controller(gateway);
      addTearDown(controller.dispose);
      await controller.refresh();
      expect(controller.state.unreadCount, 2);
      final put = controller.markAllAsRead();
      reply.complete();
      await put;
      expect(_ids(controller), ['a', 'previously-unloaded']);
      expect(
        controller.state.notifications.map((row) => row.readAt),
        everyElement('server-read'),
      );
      rows = [...rows, AppNotification(id: 'new')];
      await controller.refresh();
      expect(controller.state.notifications.last.isRead, isFalse);
      expect(controller.state.unreadCount, 1);
    },
  );

  test(
    'new post-commit row stays unread during delayed mark-all response',
    () async {
      var rows = _page(['a']).data;
      final reply = Completer<void>();
      final gateway = _Gateway()
        ..list = ((_) async => Page(data: rows))
        ..count = (() async => rows.where((row) => !row.isRead).length)
        ..all = () {
          rows = rows.map((row) => row.withReadAt('server-read')).toList();
          return reply.future;
        }
        ..read = (id) async {
          rows = rows
              .map((row) => row.id == id ? row.withReadAt('server-read') : row)
              .toList();
        };
      final controller = _controller(gateway);
      addTearDown(controller.dispose);
      await controller.refresh();
      final put = controller.markAllAsRead();
      rows = [...rows, AppNotification(id: 'new-after-commit')];
      await controller.refresh();
      expect(controller.state.notifications.last.isRead, isFalse);
      expect(controller.state.unreadCount, 1);
      reply.complete();
      await put;
      expect(controller.state.notifications.last.isRead, isFalse);
      await controller.markAsRead('new-after-commit');
      expect(gateway.readCalls, ['new-after-commit']);
      expect(controller.state.unreadCount, 0);
    },
  );

  test(
    'a GET started before mark-all may contain a new post-commit row',
    () async {
      var rows = _page(['a']).data;
      final oldGet = Completer<Page<AppNotification>>();
      final reply = Completer<void>();
      final gateway = _Gateway()
        ..count = (() async => rows.where((row) => !row.isRead).length)
        ..all = () {
          rows = rows.map((row) => row.withReadAt('server-read')).toList();
          return reply.future;
        };
      final controller = _controller(gateway);
      addTearDown(controller.dispose);
      await controller.refresh();
      gateway.list = (_) => oldGet.future;
      final refresh = controller.refresh();
      final put = controller.markAllAsRead();
      rows = [...rows, AppNotification(id: 'new-after-commit')];
      oldGet.complete(Page(data: rows));
      await refresh;
      expect(controller.state.notifications.last.isRead, isFalse);
      gateway.list = (_) async => Page(data: rows);
      reply.complete();
      await put;
      expect(controller.state.notifications.last.isRead, isFalse);
      expect(controller.state.unreadCount, 1);
    },
  );

  test(
    'an obsolete list cannot replace the authoritative post-mark-all reload',
    () async {
      final gateway = _Gateway();
      final controller = _controller(gateway);
      addTearDown(controller.dispose);
      await controller.refresh();
      final oldGet = Completer<Page<AppNotification>>();
      final reply = Completer<void>();
      gateway.list = (_) => oldGet.future;
      final refresh = controller.refresh();
      gateway.all = () => reply.future;
      final put = controller.markAllAsRead();
      gateway.list = (_) async => Page(
        data: [
          AppNotification(id: 'a', readAt: 'server-read'),
          AppNotification(id: 'new'),
        ],
      );
      gateway.count = () async => 1;
      reply.complete();
      await put;
      oldGet.complete(_page(['a', 'stale-unseen']));
      await refresh;
      expect(_ids(controller), ['a', 'new']);
      expect(controller.state.notifications.last.isRead, isFalse);
      expect(controller.state.unreadCount, 1);
    },
  );

  test(
    'failed mark-all preserves new unread rows when count also fails',
    () async {
      final gateway = _Gateway();
      final controller = _controller(gateway);
      addTearDown(controller.dispose);
      await controller.refresh();
      final reply = Completer<void>();
      gateway.all = () => reply.future;
      final put = controller.markAllAsRead();
      gateway.list = (_) async => _page(['a', 'new-during-put']);
      gateway.count = () async => throw StateError('count unavailable');
      await controller.refresh();
      expect(controller.state.notifications.first.isRead, isTrue);
      expect(controller.state.notifications.last.isRead, isFalse);
      reply.completeError(StateError('PUT failed'));
      await put;
      expect(
        controller.state.notifications.map((row) => row.isRead),
        everyElement(isFalse),
      );
      expect(controller.state.unreadCount, 2);
      expect(controller.state.error, contains('PUT failed'));
    },
  );

  test('counts serialize and older requested counts cannot publish', () async {
    final oldCount = Completer<int>();
    final latestCount = Completer<int>();
    var calls = 0;
    final gateway = _Gateway()
      ..count = () => ++calls == 1 ? oldCount.future : latestCount.future;
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    final first = controller.refreshUnreadCount();
    await _microtasks();
    final second = controller.refreshUnreadCount();
    expect(calls, 1);
    oldCount.complete(5);
    await first;
    await _microtasks();
    expect(calls, 2);
    expect(controller.state.unreadCount, 0);
    expect(gateway.maxActiveCounts, 1);
    latestCount.complete(6);
    await second;
    expect(controller.state.unreadCount, 6);
  });

  test('a read mutation invalidates an already running unread count', () async {
    final gateway = _Gateway();
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    await controller.refresh();
    final oldCount = Completer<int>();
    final put = Completer<void>();
    gateway.count = () => oldCount.future;
    final count = controller.refreshUnreadCount();
    await _microtasks();
    gateway.read = (_) => put.future;
    final read = controller.markAsRead('a');
    oldCount.complete(5);
    await count;
    expect(controller.state.unreadCount, 0);
    gateway.count = () async => 0;
    put.complete();
    await read;
    expect(controller.state.unreadCount, 0);
  });

  test('a newer refresh fences a still running older initial load', () async {
    final oldGet = Completer<Page<AppNotification>>();
    final gateway = _Gateway()..list = (_) => oldGet.future;
    final controller = _controller(gateway);
    addTearDown(controller.dispose);
    final old = controller.loadNotifications();
    gateway.list = (_) async => _page(['new']);
    await controller.refresh();
    oldGet.complete(_page(['old']));
    await old;
    expect(_ids(controller), ['new']);
  });

  test(
    'late account results and errors do not publish or trigger follow-ups',
    () async {
      var revision = 'account-a';
      final oldGet = Completer<Page<AppNotification>>();
      var countCalls = 0;
      final gateway = _Gateway()
        ..list = ((_) => oldGet.future)
        ..count = () async {
          ++countCalls;
          return 5;
        };
      final controller = _controller(gateway, sessionRevision: () => revision);
      addTearDown(controller.dispose);
      var changes = 0;
      controller.addListener(() => ++changes);
      final old = controller.refresh();
      final before = changes;
      revision = 'account-b';
      oldGet.completeError(StateError('old account error'));
      await old;
      await controller.markAsRead('a');
      await controller.markAllAsRead();
      await controller.refreshUnreadCount();
      expect(controller.state.notifications, isEmpty);
      expect(controller.state.error, isNull);
      expect(changes, before);
      expect(countCalls, 0);
    },
  );

  test(
    'a late old-account read completion cannot publish or refresh counts',
    () async {
      var revision = 'account-a';
      var countCalls = 0;
      final put = Completer<void>();
      final gateway = _Gateway()
        ..count = () async {
          ++countCalls;
          return 1;
        }
        ..read = (_) => put.future;
      final controller = _controller(gateway, sessionRevision: () => revision);
      addTearDown(controller.dispose);
      await controller.refresh();
      var changes = 0;
      controller.addListener(() => ++changes);
      final read = controller.markAsRead('a');
      final before = changes;
      revision = 'account-b';
      put.completeError(StateError('old account PUT error'));
      await read;
      expect(changes, before);
      expect(controller.state.error, isNull);
      expect(countCalls, 1);
    },
  );

  testWidgets('unread polling repeats after 60 seconds and ends on disposal', (
    tester,
  ) async {
    var countCalls = 0;
    final gateway = _Gateway()
      ..count = () async {
        ++countCalls;
        return countCalls;
      };
    final controller = _controller(gateway, poll: true);
    await tester.pump();
    expect(countCalls, 1);
    await tester.pump(const Duration(seconds: 59));
    expect(countCalls, 1);
    await tester.pump(const Duration(seconds: 1));
    expect(countCalls, 2);
    controller.dispose();
    await tester.pump(const Duration(seconds: 60));
    expect(countCalls, 2);
  });
}

NotificationsController _controller(
  _Gateway gateway, {
  bool poll = false,
  Object? Function()? sessionRevision,
}) => NotificationsController(
  loadPage: (page) => gateway.list(page),
  unreadCount: gateway.getCount,
  markRead: gateway.markRead,
  markAllRead: () => gateway.all(),
  sessionRevision: sessionRevision,
  pollUnreadCount: poll,
);

Page<AppNotification> _page(
  List<String> ids, {
  bool hasNext = false,
  int currentPage = 1,
}) => Page(
  data: ids.map((id) => AppNotification(id: id)).toList(),
  hasNext: hasNext,
  currentPage: currentPage,
);

List<String> _ids(NotificationsController controller) =>
    controller.state.notifications.map((row) => row.id).toList();

Future<void> _microtasks() async {
  for (var i = 0; i < 20; i++) {
    await Future<void>.value();
  }
}

class _Gateway {
  Future<Page<AppNotification>> Function(int) list = (_) async => _page(['a']);
  Future<int> Function() count = () async => 1;
  Future<void> Function(String) read = (_) async {};
  Future<void> Function() all = () async {};
  final readCalls = <String>[];
  var activeCounts = 0;
  var maxActiveCounts = 0;

  Future<int> getCount() async {
    ++activeCounts;
    if (activeCounts > maxActiveCounts) maxActiveCounts = activeCounts;
    try {
      return await count();
    } finally {
      --activeCounts;
    }
  }

  Future<void> markRead(String id) {
    readCalls.add(id);
    return read(id);
  }
}
