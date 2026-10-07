/// JSON models shared by the Flutter client, its cache and platform adapters.
///
/// Every model keeps an immutable snapshot of the complete response. Unknown
/// fields therefore survive a cache round trip without changing the API data.
library;

typedef Json = Map<String, dynamic>;

abstract class _JsonModel {
  _JsonModel(Json json) : raw = _freezeMap(json);

  final Json raw;

  /// Returns an independent JSON value that callers may serialize or edit.
  Json toJson() => _copyMap(raw);
}

class User extends _JsonModel {
  User.fromJson(super.json);

  int? get id => _integer(raw['id']);
  String? get uuid => _text(raw['uuid']);
  String get username => _nonBlank(raw['username']) ?? '';
  String? get displayName =>
      _text(_first(raw, ['displayName', 'display_name']));
  String? get profilePicture =>
      _text(_first(raw, ['profilePicture', 'profile_picture', 'avatar']));
  String? get bio => _text(raw['bio']);
  bool? get privateProfile =>
      _boolean(_first(raw, ['privateProfile', 'private_profile']));
  int? get totalDistance =>
      _integer(_first(raw, ['totalDistance', 'total_distance']));
  int? get totalDuration =>
      _integer(_first(raw, ['totalDuration', 'total_duration']));
  int? get points => _integer(raw['points']);
  bool? get pointsEnabled =>
      _boolean(_first(raw, ['pointsEnabled', 'points_enabled']));
  bool? get following => _boolean(raw['following']);
  bool? get followPending =>
      _boolean(_first(raw, ['followPending', 'follow_pending']));
  bool? get followedBy => _boolean(_first(raw, ['followedBy', 'followed_by']));
  bool? get muted => _boolean(raw['muted']);
  bool? get blocked => _boolean(raw['blocked']);
  bool? get userInvisibleToMe =>
      _boolean(_first(raw, ['userInvisibleToMe', 'user_invisible_to_me']));
  String? get mastodonUrl =>
      _text(_first(raw, ['mastodonUrl', 'mastodon_url']));
  Json? get mastodon => _object(raw['mastodon']);
  User copyWith({bool? following, bool? followPending}) => User.fromJson({
    ...toJson(),
    'following': ?following,
    'followPending': ?followPending,
  });
}

class Status extends _JsonModel {
  Status.fromJson(super.json);

  int get id => _integer(raw['id']) ?? 0;
  String? get body => _text(raw['body']);
  String? get createdAt => _text(_first(raw, ['createdAt', 'created_at']));
  int get likes => _integer(_first(raw, ['likes', 'likes_count'])) ?? 0;
  bool get liked => _boolean(raw['liked']) ?? false;
  bool get isLikable =>
      _boolean(_first(raw, ['isLikable', 'is_likable'])) ?? false;
  int get visibility => _integer(raw['visibility']) ?? 0;
  int get business => _integer(raw['business']) ?? 0;
  int? get earnedPoints => _integer(raw['earnedPoints']);
  User? get user => _model(_first(raw, ['user', 'userDetails']), User.fromJson);
  Checkin? get checkin =>
      _model(_first(raw, ['checkin', 'train']), Checkin.fromJson);
  Json? get event => _object(raw['event']);
  List<Json> get tags => _objects(raw['tags']);
  Status copyWith({bool? liked, int? likes}) =>
      Status.fromJson({...toJson(), 'liked': ?liked, 'likes': ?likes});
}

class Checkin extends _JsonModel {
  Checkin.fromJson(super.json);

  Stop? get origin => _model(raw['origin'], Stop.fromJson);
  Stop? get destination => _model(raw['destination'], Stop.fromJson);
  String? get hafasId => _text(_first(raw, ['hafasId', 'hafas_id']));
  String? get lineName => _text(_first(raw, ['lineName', 'line_name']));
  String? get category => _text(raw['category']);
  String? get mode => _text(raw['mode']);
  int? get trip => _integer(raw['trip']);
  String? get tripUuid => _text(_first(raw, ['tripUuid', 'trip_uuid']));
  String? get manualDeparture =>
      _text(_first(raw, ['manualDeparture', 'manual_departure']));
  String? get manualArrival =>
      _text(_first(raw, ['manualArrival', 'manual_arrival']));
  int? get distanceMeters =>
      _integer(_first(raw, ['distance', 'distanceMeters', 'distance_meters']));
  int? get duration => _integer(raw['duration']);
  int? get points => _integer(raw['points']);
  String? get number => _text(raw['number']);
  int? get journeyNumber =>
      _integer(_first(raw, ['journeyNumber', 'journey_number']));
  String? get routeColor => _text(_first(raw, ['routeColor', 'route_color']));
  String? get routeTextColor =>
      _text(_first(raw, ['routeTextColor', 'route_text_color']));
  Json? get operator => _object(raw['operator']);
  String? get operatorName => _text(operator?['name']);
}

class Station extends _JsonModel {
  Station.fromJson(super.json);

  int? get id => _integer(raw['id']);
  String? get uuid => _text(raw['uuid']);
  String? get name => _text(raw['name']);
  double? get latitude => _number(raw['latitude']);
  double? get longitude => _number(raw['longitude']);
  List<Json> get identifiers => _objects(raw['identifiers']);

  String? identifier(String type) {
    for (final entry in identifiers) {
      if (entry['type'] == type) return _text(entry['identifier']);
    }
    return null;
  }

  int? get ibnr => _integer(identifier('de_db_ibnr'));
  String? get rilIdentifier => identifier('de_db_ril100');
}

/// A visit to a station. [id] belongs to the visit, never to the station.
class Stop extends _JsonModel {
  Stop.fromJson(super.json);

  int? get id => _integer(raw['id']);
  String? get uuid => _text(raw['uuid']);
  Station? get station => _model(raw['station'], Station.fromJson);
  int? get stationId => station?.id;
  String? get stationName => station?.name ?? _text(raw['name']);
  String? get arrivalPlanned =>
      _text(_first(raw, ['arrivalPlanned', 'arrival_planned']));
  String? get arrivalReal =>
      _text(_first(raw, ['arrivalReal', 'arrival_real']));
  String? get departurePlanned =>
      _text(_first(raw, ['departurePlanned', 'departure_planned']));
  String? get departureReal =>
      _text(_first(raw, ['departureReal', 'departure_real']));

  String? get effectiveArrival => arrivalReal ?? arrivalPlanned;
  String? get effectiveDeparture => departureReal ?? departurePlanned;
  String? get platform => _text(raw['platform']);
  String? get arrivalPlatformPlanned => _text(
    _first(raw, ['arrivalPlatformPlanned', 'arrival_platform_planned']),
  );
  String? get arrivalPlatformReal =>
      _text(_first(raw, ['arrivalPlatformReal', 'arrival_platform_real']));
  String? get departurePlatformPlanned => _text(
    _first(raw, ['departurePlatformPlanned', 'departure_platform_planned']),
  );
  String? get departurePlatformReal =>
      _text(_first(raw, ['departurePlatformReal', 'departure_platform_real']));
  bool get cancelled => _boolean(raw['cancelled']) ?? false;
  bool? get isArrivalDelayed =>
      _boolean(_first(raw, ['isArrivalDelayed', 'is_arrival_delayed']));
  bool? get isDepartureDelayed =>
      _boolean(_first(raw, ['isDepartureDelayed', 'is_departure_delayed']));
  String? stationIdentifier(String type) => station?.identifier(type);

  /// UUIDs distinguish repeated visits on circular routes. With no UUID,
  /// matching requires a station identity and a shared planned instant.
  bool matchesStopover(Stop? other) {
    if (other == null) return false;
    if (_nonBlank(uuid) != null && _nonBlank(other.uuid) != null) {
      return uuid == other.uuid;
    }
    if (stationId == null || stationId != other.stationId) return false;
    return _sameInstant(departurePlanned, other.departurePlanned) ||
        _sameInstant(arrivalPlanned, other.arrivalPlanned);
  }
}

/// Merge only adjacent representations of the same planned station visit.
/// Distinct stopover UUIDs and repeated visits at other times stay separate.
List<Stop> deduplicateStops(List<Stop> stops) {
  final result = <Stop>[];
  for (final stop in stops) {
    final previous = result.isEmpty ? null : result.last;
    final hasPlannedTime =
        stop.arrivalPlanned != null || stop.departurePlanned != null;
    final sameStation =
        stop.stationId != null && stop.stationId == previous?.stationId;
    final compatibleIdentity =
        stop.uuid == null ||
        previous?.uuid == null ||
        stop.uuid == previous?.uuid;
    final sameArrival = _equalOptionalInstant(
      stop.arrivalPlanned,
      previous?.arrivalPlanned,
    );
    final sameDeparture = _equalOptionalInstant(
      stop.departurePlanned,
      previous?.departurePlanned,
    );
    if (previous != null &&
        hasPlannedTime &&
        sameStation &&
        compatibleIdentity &&
        sameArrival &&
        sameDeparture) {
      final previousHasPlatform = _hasPlatform(previous);
      final stopHasPlatform = _hasPlatform(stop);
      if ((!previousHasPlatform && stopHasPlatform) ||
          (previousHasPlatform == stopHasPlatform &&
              (stop.stationName?.length ?? 0x7fffffff) <
                  (previous.stationName?.length ?? 0x7fffffff))) {
        result[result.length - 1] = stop;
      }
    } else {
      result.add(stop);
    }
  }
  return List<Stop>.unmodifiable(result);
}

class Departure extends _JsonModel {
  Departure.fromJson(super.json);

  String get tripId => _text(_first(raw, ['tripId', 'trip_id'])) ?? '';
  Json? get line => _object(raw['line']);
  String get lineName =>
      _text(line?['name']) ??
      _text(_first(raw, ['lineName', 'line_name'])) ??
      'Fahrt';
  String? get direction => _text(raw['direction']);
  String? get plannedWhen =>
      _text(_first(raw, ['plannedWhen', 'planned_when']));
  String? get realWhen => _text(_first(raw, ['when', 'realWhen', 'real_when']));
  String? get effectiveWhen => realWhen ?? plannedWhen;
  String? get platform => _text(raw['platform']);
  String? get plannedPlatform =>
      _text(_first(raw, ['plannedPlatform', 'planned_platform']));
  bool get cancelled => _boolean(raw['cancelled']) ?? false;
  Station? get station => _model(raw['station'], Station.fromJson);
  String? get category => _text(line?['product']);
  String? get mode => _text(line?['mode']);
  String? get number => _text(line?['fahrtNr']);

  int? get delayMinutes {
    final planned = DateTime.tryParse(plannedWhen ?? '');
    final real = DateTime.tryParse(realWhen ?? '');
    return planned == null || real == null
        ? null
        : real.difference(planned).inMinutes;
  }
}

class Trip extends _JsonModel {
  Trip.fromJson(super.json);

  int? get id => _integer(raw['id']);
  String? get uuid => _text(raw['uuid']);
  String? get tripId => _text(_first(raw, ['tripId', 'trip_id']));
  String? get lineName => _text(_first(raw, ['lineName', 'line_name']));
  String? get category => _text(raw['category']);
  Json? get operator => _object(raw['operator']);
  String? get operatorName => _text(operator?['name']);
  List<Stop> get stopovers => deduplicateStops(
    _objects(raw['stopovers']).map(Stop.fromJson).toList(growable: false),
  );
}

class AppNotification extends _JsonModel {
  AppNotification({
    required String id,
    String? type,
    String? lead,
    String? leadFormatted,
    String? notice,
    String? noticeFormatted,
    String? link,
    String? readAt,
    String? createdAt,
    String? createdAtForHumans,
  }) : super({
         'id': id,
         'type': ?type,
         'lead': ?lead,
         'leadFormatted': ?leadFormatted,
         'notice': ?notice,
         'noticeFormatted': ?noticeFormatted,
         'link': ?link,
         'readAt': readAt,
         'createdAt': ?createdAt,
         'createdAtForHumans': ?createdAtForHumans,
       });

  AppNotification.fromJson(super.json);

  String get id => _text(raw['id']) ?? '';
  String? get type => _text(raw['type']);
  String? get lead => _text(raw['lead']);
  String? get leadFormatted =>
      _text(_first(raw, ['leadFormatted', 'lead_formatted']));
  String? get notice => _text(raw['notice']);
  String? get noticeFormatted =>
      _text(_first(raw, ['noticeFormatted', 'notice_formatted']));
  String? get link => _text(raw['link']);
  String? get readAt => _text(_first(raw, ['readAt', 'read_at']));
  String? get createdAt => _text(_first(raw, ['createdAt', 'created_at']));
  String? get createdAtForHumans =>
      _text(_first(raw, ['createdAtForHumans', 'created_at_for_humans']));
  bool get unread => readAt == null;
  bool get isRead => !unread;

  AppNotification withReadAt(String? value) =>
      AppNotification.fromJson({...toJson(), 'readAt': value});

  AppNotification copyWith({String? readAt, bool clearReadAt = false}) =>
      withReadAt(clearReadAt ? null : readAt ?? this.readAt);
}

class Page<T> extends _JsonModel {
  Page({
    required List<T> data,
    bool hasNext = false,
    int currentPage = 1,
    int? lastPage,
    bool offline = false,
  }) : data = List<T>.unmodifiable(data),
       super({
         'data': data.map(_encodeModel).toList(),
         'hasNext': hasNext,
         'offline': offline,
         'meta': {'current_page': currentPage, 'last_page': ?lastPage},
       });

  Page.fromJson(super.json, T Function(Json) decode)
    : data = List<T>.unmodifiable(_objects(json['data']).map(decode));

  final List<T> data;
  bool get offline => raw['offline'] == true;

  int get currentPage =>
      _integer(
        _first(_object(raw['meta']) ?? {}, ['current_page', 'currentPage']),
      ) ??
      1;
  int? get lastPage =>
      _integer(_first(_object(raw['meta']) ?? {}, ['last_page', 'lastPage']));
  bool get hasNext {
    final explicitNext = _boolean(raw['hasNext']);
    if (explicitNext != null) return explicitNext;
    final links = _object(raw['links']);
    if (links != null && links.containsKey('next')) {
      return _nonBlank(links['next']) != null;
    }
    final last = lastPage;
    return last != null && currentPage < last;
  }
}

dynamic _encodeModel(dynamic value) =>
    value is _JsonModel ? value.toJson() : _copy(value);

class Statistics extends _JsonModel {
  Statistics.fromJson(super.json);

  List<StatEntry> get categories => _entries('categories');
  List<StatEntry> get operators => _entries('operators');
  List<StatEntry> get purpose => _entries('purpose');
  List<StatDay> get time =>
      List<StatDay>.unmodifiable(_objects(raw['time']).map(StatDay.fromJson));

  List<StatEntry> _entries(String field) => List<StatEntry>.unmodifiable(
    _objects(raw[field]).map(StatEntry.fromJson),
  );
}

class StatEntry extends _JsonModel {
  StatEntry.fromJson(super.json);

  String? get name => _text(raw['name']);
  int get count => _integer(raw['count']) ?? 0;
  int get duration => _integer(raw['duration']) ?? 0;
}

class StatDay extends _JsonModel {
  StatDay.fromJson(super.json);

  String? get date => _text(raw['date']);
  int get count => _integer(raw['count']) ?? 0;
  int get duration => _integer(raw['duration']) ?? 0;
}

class CheckInRequest {
  const CheckInRequest({
    required this.tripId,
    required this.lineName,
    required this.startStationId,
    required this.destinationStationId,
    required this.departure,
    required this.arrival,
    this.body,
    this.business = 0,
    this.visibility = 0,
  });

  final String tripId;
  final String lineName;
  final int startStationId;
  final int destinationStationId;
  final String departure;
  final String arrival;
  final String? body;
  final int business;
  final int visibility;

  Json toJson() => {
    'tripId': tripId,
    'lineName': lineName,
    'start': startStationId,
    'destination': destinationStationId,
    'departure': departure,
    'arrival': arrival,
    if (body != null) 'body': body,
    'business': business,
    'visibility': visibility,
  };
}

class UpdateStatusRequest {
  UpdateStatusRequest({
    this.body,
    this.visibility,
    this.business,
    this.destination,
    this.destinationArrivalPlanned,
    this.departure,
    this.arrival,
  }) {
    if ((destination == null) != (destinationArrivalPlanned == null)) {
      throw ArgumentError(
        'destination and destinationArrivalPlanned must be provided together.',
      );
    }
  }

  final String? body;
  final int? visibility;
  final int? business;
  final int? destination;
  final String? destinationArrivalPlanned;
  final String? departure;
  final String? arrival;

  Json toJson() => {
    if (body != null) 'body': body,
    if (visibility != null) 'visibility': visibility,
    if (business != null) 'business': business,
    if (destination != null) 'destinationId': destination,
    if (destinationArrivalPlanned != null)
      'destinationArrivalPlanned': destinationArrivalPlanned,
    if (departure != null) 'manualDeparture': departure,
    if (arrival != null) 'manualArrival': arrival,
  };
}

dynamic _first(Json json, List<String> names) {
  for (final name in names) {
    // A present modern field, including an explicit null, has precedence.
    if (json.containsKey(name)) return json[name];
  }
  return null;
}

String? _text(dynamic value) => value == null
    ? null
    : value is String || value is num
    ? value.toString()
    : null;

String? _nonBlank(dynamic value) {
  final text = _text(value);
  return text == null || text.trim().isEmpty ? null : text;
}

int? _integer(dynamic value) {
  if (value is int) return value;
  if (value is num && value.isFinite && value == value.truncate()) {
    return value.toInt();
  }
  return value is String ? int.tryParse(value) : null;
}

double? _number(dynamic value) => value is num
    ? value.toDouble()
    : value is String
    ? double.tryParse(value)
    : null;

bool? _boolean(dynamic value) {
  if (value is bool) return value;
  if (value == 1 || value == '1' || value == 'true') return true;
  if (value == 0 || value == '0' || value == 'false') return false;
  return null;
}

Json? _object(dynamic value) => value is Map
    ? Map<String, dynamic>.unmodifiable(
        value.map((key, item) => MapEntry(key.toString(), item)),
      )
    : null;

List<Json> _objects(dynamic value) => value is List
    ? List<Json>.unmodifiable(value.map(_object).whereType<Json>())
    : const [];

T? _model<T>(dynamic value, T Function(Json) decode) {
  final json = _object(value);
  return json == null ? null : decode(json);
}

bool _sameInstant(String? first, String? second) {
  if (first == null || second == null) return false;
  if (first == second) return true;
  final a = DateTime.tryParse(first);
  final b = DateTime.tryParse(second);
  return a != null && b != null && a.isAtSameMomentAs(b);
}

bool _equalOptionalInstant(String? first, String? second) =>
    first == null && second == null || _sameInstant(first, second);

bool _hasPlatform(Stop stop) =>
    stop.platform != null ||
    stop.arrivalPlatformPlanned != null ||
    stop.departurePlatformPlanned != null;

Json _freezeMap(Json source) => Map<String, dynamic>.unmodifiable(
  source.map((key, value) => MapEntry(key, _freeze(value))),
);

dynamic _freeze(dynamic value) {
  if (value is Map) {
    return Map<String, dynamic>.unmodifiable(
      value.map((key, item) => MapEntry(key.toString(), _freeze(item))),
    );
  }
  if (value is List) return List<dynamic>.unmodifiable(value.map(_freeze));
  return value;
}

Json _copyMap(Json source) =>
    source.map((key, value) => MapEntry(key, _copy(value)));

dynamic _copy(dynamic value) {
  if (value is Map) {
    return value.map((key, item) => MapEntry(key.toString(), _copy(item)));
  }
  if (value is List) return value.map(_copy).toList();
  return value;
}
