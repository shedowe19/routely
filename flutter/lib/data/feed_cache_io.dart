import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:path_provider/path_provider.dart';

import 'models.dart';

/// Atomic private snapshots. No credential appears in a path or file body.
class FeedCache {
  FeedCache([Object? directory]) : _directory = directory as Directory?;
  Directory? _directory;

  Future<File> _file(String partition, String kind) async {
    _directory ??= Directory(
      '${(await getApplicationSupportDirectory()).path}/routely-cache-v1',
    );
    return File('${_directory!.path}/$partition-$kind.json');
  }

  Future<void> write(
    String partition,
    String kind,
    Json value,
    bool Function() isValid,
  ) async {
    final file = await _file(partition, kind);
    await file.parent.create(recursive: true);
    final temporary = File(
      '${file.path}.${Random.secure().nextInt(1 << 32)}.tmp',
    );
    try {
      await temporary.writeAsString(jsonEncode(value), flush: true);
      if (!isValid()) throw StateError('Stale cache write');
      await temporary.rename(file.path);
    } finally {
      if (await temporary.exists()) await temporary.delete();
    }
  }

  Future<Json?> read(String partition, String kind, int revision) async {
    final file = await _file(partition, kind);
    if (!await file.exists()) return null;
    final value = Map<String, dynamic>.from(
      jsonDecode(await file.readAsString()) as Map,
    );
    if (value['version'] != 1 || value['cacheRevision'] != revision) {
      return null;
    }
    final marker = await _file(partition, 'revision');
    if (await marker.exists()) {
      final invalidation = jsonDecode(await marker.readAsString()) as Map;
      if (value['cacheRevision'] != invalidation['cacheRevision']) return null;
    }
    return value;
  }

  Future<void> invalidate(String partition, int revision) async {
    final marker = await _file(partition, 'revision');
    await marker.parent.create(recursive: true);
    final temporary = File('${marker.path}.tmp');
    await temporary.writeAsString(
      jsonEncode({'cacheRevision': revision}),
      flush: true,
    );
    await temporary.rename(marker.path);
    await for (final entry in marker.parent.list()) {
      if (entry is File &&
          entry.path != marker.path &&
          entry.path.split('/').last.startsWith('$partition-')) {
        await entry.delete();
      }
    }
  }
}
