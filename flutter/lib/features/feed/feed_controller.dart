import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';

enum FeedType { dashboard, global }

/// A feed request keeps its tab and page owner when a submitted write finishes.
class _Load {
  const _Load(this.type, this.page, this.replace);
  final FeedType type;
  final int page;
  final bool replace;
}

class _LikeIntent {
  _LikeIntent(this.liked, this.baselineLiked, this.baselineLikes, this.likes);
  final bool liked;
  bool baselineLiked;
  int baselineLikes;
  int likes;
  int? confirmedAt;
}

class FeedController extends ChangeNotifier {
  FeedController({
    required this.loadPage,
    required this.writeLike,
    required this.sessionRevision,
    Stream<StatusMutation>? mutations,
  }) : _boundSession = sessionRevision() {
    _mutations = mutations?.listen(_onMutation);
  }

  factory FeedController.forStore(AppStore store) => FeedController(
    loadPage: (type, page) => type == FeedType.dashboard
        ? store.dashboard(page: page)
        : store.globalFeed(page: page),
    writeLike: store.setLiked,
    sessionRevision: () => store.session?.revision,
    mutations: store.mutations,
  );

  final Future<Page<Status>> Function(FeedType, int) loadPage;
  final Future<void> Function(int, bool) writeLike;
  final String? Function() sessionRevision;
  final String? _boundSession;
  StreamSubscription<StatusMutation>? _mutations;
  final Map<int, _LikeIntent> _likes = {};
  List<Status> statuses = [];
  FeedType type = FeedType.dashboard;
  bool loading = false;
  bool refreshing = false;
  bool hasMore = false;
  bool offline = false;
  String? error;
  int _nextPage = 1;
  int _generation = 0;
  int _likeRevision = 0;
  bool _disposed = false;
  bool _needsVerification = false;
  _Load? _pending;

  bool get _active =>
      !_disposed && _boundSession != null && sessionRevision() == _boundSession;
  bool isLikePending(int id) =>
      _likes[id]?.confirmedAt == null && _likes.containsKey(id);

  Future<void> refresh() => _start(_Load(type, 1, true));

  Future<void> loadMore() async {
    if (!_active || loading || refreshing || !hasMore || error != null) return;
    await _start(_Load(type, _nextPage, false));
  }

  Future<void> switchType(FeedType value) async {
    if (type == value || !_active) return;
    ++_generation;
    _pending = null;
    _likes.removeWhere((_, intent) => intent.confirmedAt != null);
    type = value;
    statuses = [];
    hasMore = false;
    offline = false;
    error = null;
    _nextPage = 1;
    await refresh();
  }

  Future<void> _start(_Load request) async {
    if (!_active || request.type != type) return;
    final generation = ++_generation;
    final likeRevision = _likeRevision;
    _pending = request;
    loading = !request.replace;
    refreshing = request.replace;
    error = null;
    notifyListeners();
    try {
      final response = await loadPage(request.type, request.page);
      if (!_active || generation != _generation) return;
      _pending = null;
      final fetched = response.data.map((status) {
        final intent = _likes[status.id];
        if (intent?.confirmedAt != null &&
            intent!.confirmedAt! <= likeRevision) {
          _likes.remove(status.id);
          return status;
        }
        return _overlay(status);
      }).toList();
      statuses = _dedupe(request.replace ? fetched : [...statuses, ...fetched]);
      hasMore = response.hasNext;
      offline = response.offline;
      _nextPage = response.currentPage + 1;
      _needsVerification = false;
      final visibleIds = statuses.map((status) => status.id).toSet();
      _likes.removeWhere(
        (id, intent) => intent.confirmedAt != null && !visibleIds.contains(id),
      );
    } catch (failure) {
      if (!_active || generation != _generation) return;
      _pending = null;
      error = failure.toString();
    } finally {
      if (_active && generation == _generation) {
        loading = refreshing = false;
        notifyListeners();
      }
    }
  }

  Future<void> toggleLike(int id) async {
    if (!_active || isLikePending(id)) return;
    final matches = statuses.where((status) => status.id == id);
    if (matches.isEmpty || !matches.first.isLikable) return;
    final status = matches.first;
    final intent = _LikeIntent(
      !status.liked,
      status.liked,
      status.likes,
      _adjustedLikes(status, !status.liked),
    );
    _likes[id] = intent;
    ++_likeRevision;
    statuses = statuses
        .map((item) => item.id == id ? _overlay(item, baseline: false) : item)
        .toList();
    error = null;
    notifyListeners();
    try {
      await writeLike(id, intent.liked);
      if (!_active || !identical(_likes[id], intent)) return;
      intent.confirmedAt = ++_likeRevision;
      final interrupted = _pending;
      ++_generation;
      _pending = null;
      loading = refreshing = false;
      notifyListeners();
      if (_needsVerification) {
        unawaited(refresh());
      } else if (interrupted != null) {
        unawaited(_start(interrupted));
      }
    } catch (failure) {
      if (!_active || !identical(_likes[id], intent)) return;
      _likes.remove(id);
      statuses = statuses
          .map(
            (item) => item.id == id
                ? item.copyWith(
                    liked: intent.baselineLiked,
                    likes: intent.baselineLikes,
                  )
                : item,
          )
          .toList();
      error = 'Like konnte nicht geändert werden: $failure';
      notifyListeners();
    }
  }

  void _onMutation(StatusMutation mutation) {
    if (!_active || mutation.sessionRevision != _boundSession) return;
    final interrupted = _pending;
    ++_generation;
    _pending = null;
    loading = refreshing = false;
    final kind = mutation.kind;
    if (kind == StatusMutationKind.deleted ||
        kind == StatusMutationKind.invalidated) {
      _likes.remove(mutation.statusId);
      statuses = statuses
          .where((item) => item.id != mutation.statusId)
          .toList();
      _needsVerification |= kind == StatusMutationKind.invalidated;
    } else if (kind == StatusMutationKind.updated && mutation.status != null) {
      statuses = statuses
          .map(
            (item) => item.id == mutation.statusId
                ? _overlay(mutation.status!)
                : item,
          )
          .toList();
    } else if (kind == StatusMutationKind.like && mutation.liked != null) {
      statuses = statuses.map((item) {
        if (item.id != mutation.statusId) return item;
        if (_likes.containsKey(item.id)) return _overlay(item, baseline: false);
        return item.copyWith(
          liked: mutation.liked,
          likes: _adjustedLikes(item, mutation.liked!),
        );
      }).toList();
    }
    notifyListeners();
    if (_needsVerification || kind == StatusMutationKind.created) {
      unawaited(refresh());
    } else if (interrupted != null) {
      unawaited(_start(interrupted));
    }
  }

  Status _overlay(Status status, {bool baseline = true}) {
    final intent = _likes[status.id];
    if (intent == null) return status;
    if (intent.confirmedAt == null && baseline) {
      intent.baselineLiked = status.liked;
      intent.baselineLikes = status.likes;
      intent.likes = _adjustedLikes(status, intent.liked);
    }
    return status.copyWith(liked: intent.liked, likes: intent.likes);
  }

  int _adjustedLikes(Status status, bool liked) => status.liked == liked
      ? status.likes
      : liked
      ? status.likes + 1
      : (status.likes - 1).clamp(0, 1 << 31).toInt();
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
