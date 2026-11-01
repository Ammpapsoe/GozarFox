import 'package:flutter/material.dart';

const dark = Color(0xFF78001A);
const mid = Color(0xFFE4002B);
const soft = Color(0xFFFF5165);
const light = Color(0xFFFFDADF);
const ink = Color(0xFF101217);
const card = Color(0xFF202229);
const text = Color(0xFFF4F5F9);
const dim = Color(0xFF9EA1AF);

Color muted(BuildContext context) =>
    Theme.of(context).colorScheme.onSurfaceVariant;

ThemeData buildTheme({bool lightMode = false}) {
  final base = lightMode
      ? ThemeData.light(useMaterial3: true)
      : ThemeData.dark(useMaterial3: true);
  final scheme = lightMode
      ? ColorScheme.fromSeed(seedColor: mid, brightness: Brightness.light)
      : base.colorScheme.copyWith(
          primary: soft,
          onPrimary: const Color(0xFF4A0010),
          secondary: const Color(0xFFFFB2B8),
          surface: card,
          onSurface: text,
          onSurfaceVariant: dim,
        );
  return base.copyWith(
    scaffoldBackgroundColor: lightMode ? const Color(0xFFFAF9F8) : ink,
    colorScheme: scheme,
    textTheme: base.textTheme.apply(
      bodyColor: scheme.onSurface,
      displayColor: scheme.onSurface,
      fontFamily: 'Vazirmatn',
    ),
    cardTheme: CardTheme(
      color: lightMode ? const Color(0xFFF1EFEE) : card,
      elevation: 0,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: mid,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(52),
        shape: const StadiumBorder(),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(foregroundColor: lightMode ? dark : soft),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: lightMode ? const Color(0xFFF1EFEE) : const Color(0xFF22252D),
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 16),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.outline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.outline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.primary, width: 1.5),
      ),
    ),
  );
}
