import 'package:flutter/material.dart';

abstract final class RoutelyColors {
  static const indigo = Color(0xff1a237e);
  static const purple = Color(0xff534bae);
  static const teal = Color(0xff00897b);
  static const mint = Color(0xffb2dfdb);
  static const amber = Color(0xffff8f00);
  static const amberLight = Color(0xfffff8e1);
  static const success = Color(0xff2e7d32);
  static const warning = Color(0xffe65100);
  static const error = Color(0xffc62828);

  static Color transport(String? category) => switch (category) {
    'nationalExpress' || 'national' => const Color(0xff9b1b30),
    'regionalExp' || 'regional' => const Color(0xff0064b0),
    'suburban' => const Color(0xff408335),
    'subway' => const Color(0xff0054a6),
    'tram' => const Color(0xffce1417),
    'bus' => const Color(0xffa5107f),
    'ferry' => const Color(0xff009fe3),
    _ => const Color(0xff546e7a),
  };
}

ThemeData routelyTheme(String mode, Brightness systemBrightness) {
  final dark =
      mode == 'DARK' ||
      mode == 'AMOLED' ||
      (mode == 'SYSTEM' && systemBrightness == Brightness.dark);
  final amoled = mode == 'AMOLED';
  final scheme =
      ColorScheme.fromSeed(
        seedColor: RoutelyColors.indigo,
        brightness: dark ? Brightness.dark : Brightness.light,
      ).copyWith(
        primary: dark ? RoutelyColors.purple : RoutelyColors.indigo,
        onPrimary: Colors.white,
        secondary: RoutelyColors.teal,
        tertiary: RoutelyColors.amber,
        error: dark ? const Color(0xffef9a9a) : RoutelyColors.error,
        surface: dark
            ? (amoled ? Colors.black : const Color(0xff1e1e1e))
            : Colors.white,
        onSurface: dark ? const Color(0xffe0e0e0) : const Color(0xff1c1b1f),
        surfaceContainerHighest: dark
            ? (amoled ? const Color(0xff121212) : const Color(0xff333333))
            : const Color(0xffe7e0ec),
      );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: dark
        ? (amoled ? Colors.black : const Color(0xff121212))
        : const Color(0xfffafbff),
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.primary,
      foregroundColor: Colors.white,
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      margin: const EdgeInsets.symmetric(vertical: 6),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    ),
    inputDecorationTheme: InputDecorationTheme(
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(minimumSize: const Size(48, 48)),
    ),
  );
}
