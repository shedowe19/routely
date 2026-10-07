import 'package:routely/data/feed_cache.dart';
import 'package:routely/data/models.dart';

/// Widget lifecycles run in FakeAsync; cache I/O belongs to the real-file data
/// tests. This adapter keeps the same version/revision rules entirely in RAM.
class MemoryFeedCache extends FeedCache {
  final Map<String, Json> _values = {};
  final Map<String, int> _revisions = {};

  @override
  Future<void> write(
    String partition,
    String kind,
    Json value,
    bool Function() isValid,
  ) async {
    if (!isValid()) throw StateError('Stale test cache write');
    _values['$partition-$kind'] = Map.of(value);
  }

  @override
  Future<Json?> read(String partition, String kind, int revision) async {
    final value = _values['$partition-$kind'];
    if (value == null ||
        value['version'] != 1 ||
        value['cacheRevision'] != revision ||
        _revisions[partition] != null && _revisions[partition] != revision) {
      return null;
    }
    return Map.of(value);
  }

  @override
  Future<void> invalidate(String partition, int revision) async {
    _revisions[partition] = revision;
    _values.removeWhere((key, _) => key.startsWith('$partition-'));
  }
}
