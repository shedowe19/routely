import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';

class UserSearchController extends ChangeNotifier {
  UserSearchController({
    required this.search,
    required this.sessionRevision,
    this.debounce = const Duration(milliseconds: 350),
  }) : _boundSession = sessionRevision();

  factory UserSearchController.forStore(AppStore store) => UserSearchController(
    search: store.api.searchUsers,
    sessionRevision: () => store.session?.revision,
  );

  final Future<List<User>> Function(String) search;
  final String? Function() sessionRevision;
  final String? _boundSession;
  final Duration debounce;
  String query = '';
  List<User> results = [];
  bool loading = false;
  String? error;
  int _generation = 0;
  Timer? _timer;
  bool _disposed = false;

  bool get _active =>
      !_disposed && _boundSession != null && sessionRevision() == _boundSession;

  void updateQuery(String value) {
    if (!_active) return;
    query = value;
    final searchQuery = value.trim().replaceFirst(RegExp(r'^@'), '');
    final request = ++_generation;
    _timer?.cancel();
    results = [];
    error = null;
    loading = searchQuery.isNotEmpty;
    notifyListeners();
    if (searchQuery.isEmpty) return;
    _timer = Timer(debounce, () => _run(searchQuery, request));
  }

  Future<void> retry() async {
    _timer?.cancel();
    final searchQuery = query.trim().replaceFirst(RegExp(r'^@'), '');
    if (searchQuery.isNotEmpty) await _run(searchQuery, ++_generation);
  }

  Future<void> _run(String value, int generation) async {
    if (!_active || generation != _generation) return;
    loading = true;
    error = null;
    notifyListeners();
    try {
      final found = await search(value);
      if (!_active || generation != _generation) return;
      final seen = <String>{};
      results = found
          .where(
            (user) =>
                user.username.trim().isNotEmpty &&
                seen.add(user.id?.toString() ?? user.username),
          )
          .toList();
    } catch (failure) {
      if (!_active || generation != _generation) return;
      error = failure.toString();
    } finally {
      if (_active && generation == _generation) {
        loading = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    ++_generation;
    _timer?.cancel();
    super.dispose();
  }
}
