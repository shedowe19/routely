import 'dart:async';

import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// Gives response cancellation an explicit test-zone future. The stock
/// Stream.value response uses a shared completed cancellation future that can
/// escape WidgetTester's FakeAsync zone when RoutelyApi applies Stream.timeout.
http.Client widgetHttpClient(MockClientHandler handler) =>
    MockClient.streaming((request, body) async {
      final bodyBytes = await body.toBytes();
      final forwarded = http.Request(request.method, request.url)
        ..persistentConnection = request.persistentConnection
        ..followRedirects = request.followRedirects
        ..maxRedirects = request.maxRedirects
        ..headers.addAll(request.headers)
        ..bodyBytes = bodyBytes
        ..finalize();
      final response = await handler(forwarded);
      late final StreamController<List<int>> stream;
      stream = StreamController<List<int>>(
        onListen: () {
          stream.add(response.bodyBytes);
          unawaited(stream.close());
        },
        onCancel: () async {},
      );
      return http.StreamedResponse(
        stream.stream,
        response.statusCode,
        headers: response.headers,
        contentLength: response.contentLength,
        request: response.request,
        isRedirect: response.isRedirect,
        persistentConnection: response.persistentConnection,
        reasonPhrase: response.reasonPhrase,
      );
    });
