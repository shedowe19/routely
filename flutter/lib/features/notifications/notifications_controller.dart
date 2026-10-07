import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../../data/models.dart';

class NotificationsState {
  const NotificationsState({
    this.notifications = const [],
    this.isLoading = false,
    this.isRefreshing = false,
    this.hasMore = false,
    this.nextPage = 1,
    this.unreadCount = 0,
    this.error,
  });

  final List<AppNotification> notifications;
  final bool isLoading;
  final bool isRefreshing;
  final bool hasMore;
  final int nextPage;
  final int unreadCount;
  final String? error;

  NotificationsState copyWith({
    List<AppNotification>? notifications,
    bool? isLoading,
    bool? isRefreshing,
    bool? hasMore,
    int? nextPage,
    int? unreadCount,
    String? error,
    bool clearError = false,
  }) => NotificationsState(
    notifications: notifications ?? this.notifications,
    isLoading: isLoading ?? this.isLoading,
    isRefreshing: isRefreshing ?? this.isRefreshing,
    hasMore: hasMore ?? this.hasMore,
    nextPage: nextPage ?? this.nextPage,
    unreadCount: unreadCount ?? this.unreadCount,
    error: clearError ? null : error ?? this.error,
  );
}

/// Account-scoped notification requests and optimistic read ordering.
///
/// A GET started before a successful PUT can still complete afterwards. Read
/// overlays stay attached to concrete IDs until a newer server snapshot confirms
/// them. Mark-all never applies an overlay to previously unseen notifications.
class NotificationsController extends ChangeNotifier {
  NotificationsController({
    required Future<Page<AppNotification>> Function(int page) loadPage,
    required Future<int> Function() unreadCount,
    required Future<void> Function(String id) markRead,
    required Future<void> Function() markAllRead,
    Object? Function()? sessionRevision,
    this.onUnreadCountChanged,
    bool pollUnreadCount = true,
    Duration pollInterval = const Duration(seconds: 60),
  }) : _loadPage = loadPage,
       _unreadCount = unreadCount,
       _markRead = markRead,
       _markAllRead = markAllRead,
       _sessionRevision = sessionRevision,
       _initialSessionRevision = sessionRevision?.call() {
    if (pollUnreadCount) {
      unawaited(refreshUnreadCount());
      _poll = Timer.periodic(pollInterval, (_) {
        if (_active) unawaited(refreshUnreadCount());
      });
    }
  }

  static const _localReadAt = 'routely-local-read';
  final Future<Page<AppNotification>> Function(int page) _loadPage;
  final Future<int> Function() _unreadCount;
  final Future<void> Function(String id) _markRead;
  final Future<void> Function() _markAllRead;
  final Object? Function()? _sessionRevision;
  final Object? _initialSessionRevision;
  final ValueChanged<int>? onUnreadCountChanged;

  NotificationsState _state = const NotificationsState();
  NotificationsState get state => _state;
  final Set<String> _pendingReads = {};
  final Set<String> _pendingAllReads = {};
  final Map<String, int> _confirmedReads = {};
  bool _markingAll = false;
  bool get isMarkingAll => _markingAll;
  bool get canMarkAll =>
      _state.unreadCount > 0 && !_markingAll && _pendingReads.isEmpty;
  bool isMarkingRead(String id) => _pendingReads.contains(id);
  int _generation = 0;
  int _readRevision = 0;
  int _countRequest = 0;
  Future<void> _countTail = Future<void>.value();
  Timer? _poll;
  bool _disposed = false;
  bool get _active =>
      !_disposed &&
      (_sessionRevision == null ||
          _sessionRevision() == _initialSessionRevision);

  void _publish(NotificationsState next) {
    if (!_active) return;
    final countChanged = next.unreadCount != _state.unreadCount;
    _state = next;
    notifyListeners();
    if (countChanged && _active) onUnreadCountChanged?.call(next.unreadCount);
  }

  Future<void> loadNotifications({bool refresh = false}) async {
    if (!_active) return;
    if (!refresh && (_state.isLoading || _state.isRefreshing)) return;
    if (refresh && _state.isRefreshing) return;
    final page = refresh ? 1 : _state.nextPage;
    final request = ++_generation;
    final readRevision = _readRevision;
    _publish(
      _state.copyWith(
        isLoading: !refresh,
        isRefreshing: refresh,
        clearError: true,
      ),
    );
    try {
      final response = await _loadPage(page);
      if (!_active || request != _generation) return;
      final fetched = response.data
          .where((notification) => notification.id.trim().isNotEmpty)
          .map((notification) {
            final confirmed = _confirmedReads[notification.id];
            if (notification.isRead &&
                confirmed != null &&
                readRevision > confirmed) {
              _confirmedReads.remove(notification.id);
            }
            if (!notification.isRead &&
                (_pendingReads.contains(notification.id) ||
                    _pendingAllReads.contains(notification.id) ||
                    _confirmedReads.containsKey(notification.id))) {
              return notification.withReadAt(_localReadAt);
            }
            return notification;
          });
      final merged = refresh || page == 1
          ? fetched
          : <AppNotification>[..._state.notifications, ...fetched];
      final seen = <String>{};
      final notifications = List<AppNotification>.unmodifiable(
        merged.where((notification) => seen.add(notification.id)),
      );
      _publish(
        _state.copyWith(
          notifications: notifications,
          isLoading: false,
          isRefreshing: false,
          hasMore: response.hasNext,
          nextPage: math.max(page, response.currentPage) + 1,
          unreadCount: _markingAll
              ? math.max(
                  _state.unreadCount,
                  notifications
                      .where((notification) => !notification.isRead)
                      .length,
                )
              : _state.unreadCount,
          clearError: true,
        ),
      );
    } catch (error) {
      if (!_active || request != _generation) return;
      _publish(
        _state.copyWith(
          isLoading: false,
          isRefreshing: false,
          error: error.toString(),
        ),
      );
    }
    if (_active && request == _generation) await refreshUnreadCount();
  }

  Future<void> refresh() => loadNotifications(refresh: true);

  Future<void> loadMore() async {
    if (_state.hasMore && !_state.isLoading && !_state.isRefreshing) {
      await loadNotifications();
    }
  }

  Future<void> markAsRead(String id) async {
    if (!_active ||
        id.trim().isEmpty ||
        _markingAll ||
        _pendingReads.contains(id)) {
      return;
    }
    if (!_state.notifications.any(
      (notification) => notification.id == id && !notification.isRead,
    )) {
      return;
    }
    final previousCount = _state.unreadCount;
    final newCount = math.max(0, previousCount - 1);
    final unreadDelta = previousCount - newCount;
    _pendingReads.add(id);
    ++_readRevision;
    _publish(
      _state.copyWith(
        notifications: _state.notifications
            .map(
              (notification) => notification.id == id && !notification.isRead
                  ? notification.withReadAt(_localReadAt)
                  : notification,
            )
            .toList(growable: false),
        unreadCount: newCount,
        clearError: true,
      ),
    );
    try {
      await _markRead(id);
      if (!_active) return;
      _confirmedReads[id] = _readRevision;
    } catch (error) {
      if (!_active) return;
      _publish(
        _state.copyWith(
          notifications: _state.notifications
              .map(
                (notification) =>
                    notification.id == id && notification.readAt == _localReadAt
                    ? notification.withReadAt(null)
                    : notification,
              )
              .toList(growable: false),
          unreadCount: _state.unreadCount + unreadDelta,
          error: 'Meldung konnte nicht als gelesen markiert werden: $error',
        ),
      );
    } finally {
      _pendingReads.remove(id);
      ++_readRevision;
      if (_active) notifyListeners();
    }
    if (_active) await refreshUnreadCount();
  }

  Future<void> markAllAsRead() async {
    if (!_active || !canMarkAll) return;
    final previous = _state;
    _pendingAllReads.addAll(previous.notifications.map((row) => row.id));
    _markingAll = true;
    ++_readRevision;
    _publish(
      _state.copyWith(
        notifications: _state.notifications
            .map(
              (notification) => notification.isRead
                  ? notification
                  : notification.withReadAt(_localReadAt),
            )
            .toList(growable: false),
        unreadCount: 0,
        clearError: true,
      ),
    );
    var succeeded = false;
    try {
      await _markAllRead();
      if (!_active) return;
      succeeded = true;
      for (final id in _pendingAllReads) {
        _confirmedReads[id] = _readRevision;
      }
    } catch (error) {
      if (!_active) return;
      final previousReadTimes = {
        for (final notification in previous.notifications)
          notification.id: notification.readAt,
      };
      final restored = _state.notifications
          .map(
            (notification) => notification.readAt == _localReadAt
                ? notification.withReadAt(previousReadTimes[notification.id])
                : notification,
          )
          .toList(growable: false);
      _publish(
        _state.copyWith(
          notifications: restored,
          unreadCount: math.max(
            previous.unreadCount,
            restored.where((notification) => !notification.isRead).length,
          ),
          error: 'Meldungen konnten nicht als gelesen markiert werden: $error',
        ),
      );
    } finally {
      _markingAll = false;
      _pendingAllReads.clear();
      ++_readRevision;
      if (_active) notifyListeners();
    }
    if (!_active) return;
    if (succeeded) {
      // Fence older GETs and fetch the actual post-PUT snapshot. Previously
      // unseen rows may have been created after the server committed mark-all.
      _publish(_state.copyWith(isLoading: false, isRefreshing: false));
      await refresh();
    } else {
      await refreshUnreadCount();
    }
  }

  /// Serialized requests additionally carry a generation and read revision.
  /// A poll started before a mutation cannot restore an obsolete badge count.
  Future<void> refreshUnreadCount() {
    if (!_active) return Future<void>.value();
    final request = ++_countRequest;
    final revision = _readRevision;
    final completion = _countTail.then((_) async {
      if (!_active ||
          request != _countRequest ||
          revision != _readRevision ||
          _markingAll ||
          _pendingReads.isNotEmpty) {
        return;
      }
      try {
        final count = await _unreadCount();
        if (_active &&
            request == _countRequest &&
            revision == _readRevision &&
            !_markingAll &&
            _pendingReads.isEmpty) {
          _publish(_state.copyWith(unreadCount: math.max(0, count)));
        }
      } catch (_) {
        // Keep the last known count; a failed GET is not a successful zero.
      }
    });
    _countTail = completion;
    return completion;
  }

  void clearError() => _publish(_state.copyWith(clearError: true));

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    ++_countRequest;
    _poll?.cancel();
    super.dispose();
  }
}
