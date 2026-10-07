import 'models.dart';

/// Browser snapshots are process-local. Secure tokens use the platform plugin.
class FeedCache {
  FeedCache([Object? directory]);
  final Map<String, Json> _snapshots = {};
  final Map<String, int> _revisions = {};

  Future<void> write(
    String partition,
    String kind,
    Json value,
    bool Function() isValid,
  ) async {
    if (!isValid()) throw StateError('Stale cache write');
    _snapshots['$partition-$kind'] = Map.of(value);
  }

  Future<Json?> read(String partition, String kind, int revision) async {
    final value = _snapshots['$partition-$kind'];
    if (value == null ||
        value['version'] != 1 ||
        value['cacheRevision'] != revision ||
        _revisions[partition] != null && _revisions[partition] != revision) {
      return null;
    }
    return Map.of(value);
  }

  Future<void> invalidate(String partition, int revision) async {
    _revisions[partition] = revision;
    _snapshots.removeWhere((key, _) => key.startsWith('$partition-'));
  }
}
