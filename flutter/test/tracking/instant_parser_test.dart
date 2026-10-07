import 'package:flutter_test/flutter_test.dart';
import 'package:routely/tracking/models.dart';

void main() {
  test(
    'complete zoned ISO instants preserve equivalent offsets and fractional milliseconds',
    () {
      final utc = parseMillis('2026-10-05T18:14:00Z');
      expect(utc, isNotNull);
      expect(parseMillis('2026-10-05T20:14:00+02:00'), utc);
      expect(parseMillis('2026-10-05t18:14:00z'), utc);
      expect(parseMillis('2026-10-05T18:14:00.123456789Z'), utc! + 123);
      expect(parseMillis('2026-10-05T18:14:30+00:00:30'), utc);
      expect(parseMillis('1969-12-31T23:59:59.999999999Z'), -1);
    },
  );
  test(
    'invalid calendar dates, local/date-only time and malformed offsets never become API events',
    () {
      for (final value in [
        '2026-02-29T18:00:00Z',
        '2026-02-31T18:00:00Z',
        '2026-04-31T18:00:00Z',
        '2026-13-01T18:00:00Z',
        '2026-10-00T18:00:00Z',
        '2026-10-05',
        '2026-10-05T18:00:00',
        '2026-10-05 18:00:00Z',
        '2026-10-05T18:00Z',
        '2026-10-05T25:00:00Z',
        '2026-10-05T18:60:00Z',
        '2026-10-05T18:00:61Z',
        '2026-10-05T18:00:00+18:01',
        '2026-10-05T18:00:00+02:60',
        '2026-10-05T18:00:00.1234567890Z',
      ]) {
        expect(parseMillis(value), isNull, reason: value);
      }
      expect(parseMillis('2024-02-29T18:00:00Z'), isNotNull);
      expect(parseMillis('2000-02-29T18:00:00Z'), isNotNull);
      expect(parseMillis('1900-02-29T18:00:00Z'), isNull);
    },
  );
  test(
    'ISO instant end-of-day and supported leap second retain Android behavior',
    () {
      expect(
        parseMillis('2026-10-05T24:00:00Z'),
        parseMillis('2026-10-06T00:00:00Z'),
      );
      expect(
        parseMillis('2016-12-31T23:59:60Z'),
        parseMillis('2016-12-31T23:59:59Z'),
      );
      expect(parseMillis('2026-10-05T24:00:01Z'), isNull);
      expect(parseMillis('2026-10-05T24:00:00.001Z'), isNull);
      expect(parseMillis('2026-10-05T18:59:60Z'), isNull);
    },
  );
  test(
    'extended ISO years preserve signed epoch arithmetic and reject millisecond overflow',
    () {
      expect(
        parseMillis('+10000-01-01T00:00:00Z'),
        DateTime.utc(10000).millisecondsSinceEpoch,
      );
      expect(
        parseMillis('-0001-01-01T00:00:00Z'),
        DateTime.utc(-1).millisecondsSinceEpoch,
      );
      expect(
        parseMillis('0000-01-01T00:00:00Z'),
        DateTime.utc(0).millisecondsSinceEpoch,
      );
      expect(parseMillis('+1000000000-01-01T00:00:00Z'), isNull);
      expect(parseMillis('-1000000000-01-01T00:00:00Z'), isNull);
      expect(parseMillis('10000-01-01T00:00:00Z'), isNull);
      expect(parseMillis('+2026-01-01T00:00:00Z'), isNull);
      expect(parseMillis('-0000-01-01T00:00:00Z'), isNull);
    },
  );
}
