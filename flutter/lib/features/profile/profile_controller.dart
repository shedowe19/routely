import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';

/// Own and public profiles share history ordering, while retaining separate owners.
class ProfileController extends ChangeNotifier {
  ProfileController({
    required this.fetchUser,
    required this.fetchStatuses,
    required this.sessionRevision,
    this.fetchStatistics,
    this.writeFollow,
    Stream<StatusMutation>? mutations,
  }) : _boundSession = sessionRevision() {
    _mutations = mutations?.listen(_onMutation);
  }

  factory ProfileController.forStore(AppStore store, {String? username}) =>
      ProfileController(
        fetchUser: () => username == null
            ? store.api.authUser()
            : store.api.userProfile(username),
        fetchStatuses: (name, page) => store.userStatuses(name, page: page),
        fetchStatistics: username == null ? store.api.statistics : null,
        writeFollow: username == null ? null : store.api.setFollowing,
        sessionRevision: () => store.session?.revision,
        mutations: store.mutations,
      );

  final Future<User> Function() fetchUser;
  final Future<Page<Status>> Function(String, int) fetchStatuses;
  final Future<Statistics> Function()? fetchStatistics;
  final Future<void> Function(int, bool)? writeFollow;
  final String? Function() sessionRevision;
  final String? _boundSession;
  StreamSubscription<StatusMutation>? _mutations;
  User? user;
  Statistics? statistics;
  List<Status> statuses = [];
  bool loading = false;
  bool following = false;
  bool hasMore = false;
  bool offline = false;
  String? error;
  String? _followError;
  int _generation = 0;
  int _nextPage = 1;
  int _relationshipRevision = 0;
  (int, bool, bool, int)? _confirmedRelationship;
  bool _queuedRefresh = false;
  bool _disposed = false;

  bool get _active =>
      !_disposed && _boundSession != null && sessionRevision() == _boundSession;
  bool get historyVisible => user?.userInvisibleToMe != true;

  Future<void> refresh() async {
    if (!_active) return;
    if (following) {
      _queuedRefresh = true;
      return;
    }
    final generation = ++_generation;
    final relationshipRevision = _relationshipRevision;
    loading = true;
    error = _followError;
    notifyListeners();
    try {
      final fetched = await fetchUser();
      if (!_active || generation != _generation) return;
      final confirmed = _confirmedRelationship;
      if (confirmed != null &&
          fetched.id == confirmed.$1 &&
          confirmed.$4 > relationshipRevision) {
        user = fetched.copyWith(
          following: confirmed.$2,
          followPending: confirmed.$3,
        );
      } else {
        user = fetched;
        if (confirmed != null && confirmed.$4 <= relationshipRevision) {
          _confirmedRelationship = null;
        }
      }
      if (!historyVisible) {
        statuses = [];
        statistics = null;
        hasMore = false;
        offline = false;
        _nextPage = 1;
        return;
      }
      notifyListeners();
      String? partError;
      if (fetchStatistics != null) {
        try {
          final stats = await fetchStatistics!();
          if (!_active || generation != _generation) return;
          statistics = stats;
        } catch (failure) {
          if (!_active || generation != _generation) return;
          partError = 'Statistiken konnten nicht geladen werden: $failure';
        }
      }
      try {
        final history = await fetchStatuses(fetched.username, 1);
        if (!_active || generation != _generation) return;
        statuses = _dedupe(history.data);
        hasMore = history.hasNext;
        offline = history.offline;
        _nextPage = history.currentPage + 1;
      } catch (failure) {
        if (!_active || generation != _generation) return;
        partError = [
          partError,
          'Fahrten konnten nicht geladen werden: $failure',
        ].whereType<String>().join('\n');
      }
      error = [_followError, partError].whereType<String>().join('\n');
      if (error?.isEmpty == true) error = null;
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error = 'Profil konnte nicht geladen werden: $failure';
    } finally {
      if (_active && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> loadMore() async {
    if (!_active ||
        loading ||
        following ||
        !hasMore ||
        error != null ||
        user == null ||
        !historyVisible) {
      return;
    }
    final generation = ++_generation;
    final name = user!.username;
    final page = _nextPage;
    loading = true;
    notifyListeners();
    try {
      final history = await fetchStatuses(name, page);
      if (!_active || generation != _generation) return;
      statuses = _dedupe([...statuses, ...history.data]);
      hasMore = history.hasNext;
      offline = history.offline;
      _nextPage = history.currentPage + 1;
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error = 'Weitere Fahrten konnten nicht geladen werden: $failure';
    } finally {
      if (_active && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  Future<void> toggleFollow() async {
    final selected = user;
    if (!_active ||
        following ||
        writeFollow == null ||
        selected == null ||
        selected.id == null ||
        selected.followPending == true) {
      return;
    }
    final previous = selected.following == true;
    following = true;
    _followError = error = null;
    notifyListeners();
    try {
      await writeFollow!(selected.id!, !previous);
      if (!_active || user?.id != selected.id) return;
      final pending = !previous && selected.privateProfile == true;
      final newFollowing = !previous && !pending;
      _confirmedRelationship = (
        selected.id!,
        newFollowing,
        pending,
        ++_relationshipRevision,
      );
      user = user!.copyWith(following: newFollowing, followPending: pending);
    } catch (failure) {
      if (!_active || user?.id != selected.id) return;
      _followError = error = 'Folgen konnte nicht geändert werden: $failure';
    } finally {
      if (_active) {
        following = false;
        notifyListeners();
        if (_queuedRefresh) {
          _queuedRefresh = false;
          unawaited(refresh());
        }
      }
    }
  }

  void _onMutation(StatusMutation mutation) {
    if (!_active ||
        mutation.sessionRevision != _boundSession ||
        !historyVisible) {
      return;
    }
    final known = statuses.any((status) => status.id == mutation.statusId);
    final verify =
        loading ||
        mutation.kind == StatusMutationKind.created ||
        (known && mutation.kind == StatusMutationKind.invalidated);
    if (!known && !verify) return;
    ++_generation;
    loading = false;
    if (mutation.kind == StatusMutationKind.deleted ||
        mutation.kind == StatusMutationKind.invalidated) {
      statuses = statuses
          .where((status) => status.id != mutation.statusId)
          .toList();
    } else if (mutation.kind == StatusMutationKind.updated &&
        mutation.status != null) {
      statuses = statuses
          .map(
            (status) =>
                status.id == mutation.statusId ? mutation.status! : status,
          )
          .toList();
    } else if (mutation.kind == StatusMutationKind.like &&
        mutation.liked != null) {
      statuses = statuses
          .map(
            (status) => status.id == mutation.statusId
                ? status.copyWith(
                    liked: mutation.liked,
                    likes: status.liked == mutation.liked
                        ? status.likes
                        : (status.likes + (mutation.liked! ? 1 : -1))
                              .clamp(0, 1 << 31)
                              .toInt(),
                  )
                : status,
          )
          .toList();
    }
    notifyListeners();
    if (verify) unawaited(refresh());
  }

  List<Status> _dedupe(List<Status> input) {
    final seen = <int>{};
    return input
        .where((status) => status.id > 0 && seen.add(status.id))
        .toList();
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _mutations?.cancel();
    super.dispose();
  }
}
