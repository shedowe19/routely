import 'dart:ui';
import 'package:flutter/material.dart';
import 'app.dart';
import 'data/app_store.dart';
import 'recognition/ride_recognition_runtime.dart';
import 'runtime/tracking_runtime.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final store = AppStore();
  await store.initialize(validate: false);
  runApp(RoutelyApp(store: store));
}

@pragma('vm:entry-point')
Future<void> trackingMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  DartPluginRegistrant.ensureInitialized();
  await runTrackingBackground(recognitionRunner: runRecognitionBackground);
}
