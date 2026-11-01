import 'package:flutter/material.dart';

const dark = Color(0xFF78001A);
const mid = Color(0xFFE4002B);
const soft = Color(0xFFFF5165);
const light = Color(0xFFFFDADF);
const ink = Color(0xFF101217);
const card = Color(0xFF202229);
const text = Color(0xFFF4F5F9);
const dim = Color(0xFF9EA1AF);

class GzSpace {
  const GzSpace._();
  static const double xs = 4;
  static const double s = 8;
  static const double m = 12;
  static const double l = 16;
  static const double xl = 20;
  static const double xxl = 24;
  static const double xxxl = 32;
}

class GzRadius {
  const GzRadius._();
  static const double s = 8;
  static const double m = 12;
  static const double l = 16;
  static const double xl = 20;
  static const double card = 24;
}

Color muted(BuildContext context) =>
    Theme.of(context).colorScheme.onSurfaceVariant;

TextTheme _type(TextTheme base, ColorScheme scheme) => base
    .apply(
      bodyColor: scheme.onSurface,
      displayColor: scheme.onSurface,
      fontFamily: 'Vazirmatn',
    )
    .copyWith(
      headlineSmall: TextStyle(
        fontFamily: 'Vazirmatn',
        fontSize: 20,
        height: 1.2,
        fontWeight: FontWeight.w700,
        color: scheme.onSurface,
      ),
      titleLarge: TextStyle(
        fontFamily: 'Vazirmatn',
        fontSize: 17,
        height: 1.25,
        fontWeight: FontWeight.w600,
        color: scheme.onSurface,
      ),
      titleMedium: TextStyle(
        fontFamily: 'Vazirmatn',
        fontSize: 14,
        height: 1.4,
        fontWeight: FontWeight.w600,
        color: scheme.onSurface,
      ),
      bodyMedium: TextStyle(
        fontFamily: 'Vazirmatn',
        fontSize: 12,
        height: 1.5,
        fontWeight: FontWeight.w400,
        color: scheme.onSurface,
      ),
      bodySmall: TextStyle(
        fontFamily: 'Vazirmatn',
        fontSize: 11,
        height: 1.45,
        fontWeight: FontWeight.w400,
        color: scheme.onSurfaceVariant,
      ),
      labelSmall: TextStyle(
        fontFamily: 'Vazirmatn',
        fontSize: 11,
        height: 1.3,
        fontWeight: FontWeight.w600,
        letterSpacing: .2,
        color: scheme.onSurfaceVariant,
      ),
    );

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
    textTheme: _type(base.textTheme, scheme),
    cardTheme: CardTheme(
      color: lightMode ? const Color(0xFFF1EFEE) : card,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(GzRadius.card)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: mid,
        foregroundColor: Colors.white,
        minimumSize: const Size.fromHeight(52),
        textStyle: const TextStyle(
            fontFamily: 'Vazirmatn', fontSize: 14, fontWeight: FontWeight.w600),
        shape: const StadiumBorder(),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: lightMode ? dark : soft,
        textStyle: const TextStyle(
            fontFamily: 'Vazirmatn', fontSize: 13, fontWeight: FontWeight.w600),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: lightMode ? const Color(0xFFF1EFEE) : const Color(0xFF22252D),
      contentPadding:
          const EdgeInsets.symmetric(horizontal: GzSpace.l, vertical: GzSpace.l),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(GzRadius.l),
        borderSide: BorderSide(color: scheme.outline),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(GzRadius.l),
        borderSide: BorderSide(color: scheme.outline),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(GzRadius.l),
        borderSide: BorderSide(color: scheme.primary, width: 1.5),
      ),
    ),
  );
}
