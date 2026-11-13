import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'main.dart' show CountryFlag, ErrorText;
import 'strings.dart';

/// The connect screen, laid out the way Windscribe's desktop client lays
/// theirs out: a deep navy stage, the wordmark and the preferences gear in
/// the top row, the city and the connection state on the left, one large
/// circular connect button in the middle of the stage, and a row of bubble
/// buttons along the bottom — locations in the centre, the firewall shield
/// on one side, the protocol on the other. The palette and the geometry
/// follow their open-source client (GPL-2.0, see README); the connection
/// logic behind every button is entirely ours.
class WindscribeHome extends StatefulWidget {
  const WindscribeHome({
    super.key,
    required this.words,
    required this.live,
    required this.connecting,
    required this.busy,
    required this.city,
    required this.code,
    required this.downloadLabel,
    required this.uploadLabel,
    required this.sessionLabel,
    required this.deviceLabel,
    required this.exitLabel,
    required this.usageLabel,
    this.fault = '',
    required this.statusText,
    required this.protocolText,
    required this.killSwitch,
    required this.limit,
    required this.remaining,
    required this.onToggle,
    required this.onServers,
    required this.onSettings,
  });

  final Words words;
  final bool live;
  final bool connecting;
  final bool busy;
  final String city;
  final String? code;
  final String downloadLabel;
  final String uploadLabel;
  final String sessionLabel;
  final String deviceLabel;
  final String exitLabel;
  final String usageLabel;
  final String fault;
  final String statusText;
  final String protocolText;
  final bool killSwitch;
  final int limit;
  final int remaining;
  final VoidCallback onToggle;
  final VoidCallback onServers;
  final VoidCallback onSettings;

  @override
  State<WindscribeHome> createState() => _WindscribeHomeState();
}

class _WindscribeHomeState extends State<WindscribeHome>
    with SingleTickerProviderStateMixin {
  // Windscribe's own accents: the connected blue and the disconnected red,
  // plus the warning amber their badges use.
  static const navyTop = Color(0xFF102A43);
  static const navyBottom = Color(0xFF0A1B2E);
  static const connectedBlue = Color(0xFF006AFF);
  static const connectedBlueSoft = Color(0xFF2C8BFF);
  static const dangerRed = Color(0xFFE41228);
  static const bubble = Color(0x24FFFFFF);
  static const bubbleHover = Color(0x3DFFFFFF);
  static const textSoft = Color(0xB3DFEEFF);

  late final AnimationController spin = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1400),
  );

  @override
  void initState() {
    super.initState();
    if (widget.connecting) spin.repeat();
  }

  @override
  void didUpdateWidget(WindscribeHome old) {
    super.didUpdateWidget(old);
    if (widget.connecting && !spin.isAnimating) spin.repeat();
    if (!widget.connecting && spin.isAnimating) spin.stop();
  }

  @override
  void dispose() {
    spin.dispose();
    super.dispose();
  }

  Color get accent => widget.live ? connectedBlue : dangerRed;

  @override
  Widget build(BuildContext context) {
    return Stack(children: [
      const Positioned.fill(
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [navyTop, navyBottom],
            ),
          ),
        ),
      ),
      Column(children: [
        _header(),
        const SizedBox(height: 6),
        _cityAndStatus(),
        Expanded(child: Center(child: _connectButton())),
        _infoPanel(),
        if (widget.fault.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 4, 24, 0),
            child: ErrorText(widget.fault),
          ),
        _bottomRow(),
        const SizedBox(height: 14),
      ]),
    ]);
  }

  Widget _header() => Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
        child: Row(children: [
          KeyedSubtree(
            key: const ValueKey('home-settings-gear'),
            child: _IconButton(
              icon: Icons.settings_outlined,
              tooltip: widget.words.settings,
              onTap: widget.busy ? null : widget.onSettings,
            ),
          ),
          const SizedBox(width: 10),
          const Text(
            'GOZAR',
            style: TextStyle(
              color: Colors.white,
              fontSize: 17,
              fontWeight: FontWeight.w800,
              letterSpacing: 3,
            ),
          ),
          const Spacer(),
          Flexible(
            child: Tooltip(
              message: widget.words.killSwitchHint,
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: widget.killSwitch ? bubble : Colors.transparent,
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(
                    color: widget.killSwitch
                        ? const Color(0x66FEBC2E)
                        : const Color(0x22FFFFFF),
                  ),
                ),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(
                    Icons.shield_outlined,
                    size: 14,
                    color: widget.killSwitch
                        ? const Color(0xFFFEBC2E)
                        : textSoft,
                  ),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text(
                      widget.words.killSwitch,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: textSoft, fontSize: 11),
                    ),
                  ),
                ]),
              ),
            ),
          ),
        ]),
      );

  Widget _cityAndStatus() => Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              CountryFlag(code: widget.code, size: 26),
              const SizedBox(width: 9),
              Flexible(
                child: Text(
                  widget.city,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 19,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ]),
            const SizedBox(height: 4),
            Row(children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  color: widget.connecting
                      ? const Color(0xFFFEBC2E)
                      : accent,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                widget.statusText,
                style: const TextStyle(
                    color: textSoft,
                    fontSize: 12,
                    fontWeight: FontWeight.w600),
              ),
              const SizedBox(width: 10),
              Text(
                widget.protocolText,
                textDirection: TextDirection.ltr,
                style: const TextStyle(color: Color(0x80DFEEFF), fontSize: 11),
              ),
            ]),
          ],
        ),
      );

  Widget _connectButton() {
    const diameter = 176.0;
    return Tooltip(
      message: widget.live ? widget.words.disconnect : widget.words.connect,
      child: Semantics(
        button: true,
        label: widget.live ? widget.words.disconnect : widget.words.connect,
        child: GestureDetector(
        onTap: widget.busy ? null : widget.onToggle,
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: SizedBox(
          width: diameter,
          height: diameter,
          child: CustomPaint(
            painter: _RingPainter(
              accent: accent,
              connecting: widget.connecting,
              spin: spin,
            ),
            child: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.power_settings_new,
                    color: Colors.white,
                    size: 46,
                    shadows: [
                      Shadow(color: accent.withValues(alpha: .8), blurRadius: 18),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    widget.connecting
                        ? widget.words.connecting
                        : (widget.live
                            ? widget.words.connected
                            : widget.words.connect),
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      letterSpacing: .6,
                    ),
                  ),
                  ],
                ),
              ),
            ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _infoPanel() {
    final fraction = widget.limit <= 0
        ? 0.0
        : (widget.remaining / widget.limit).clamp(0.0, 1.0);
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 4),
      child: Column(children: [
        if (widget.live)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Wrap(
              spacing: 16,
              runSpacing: 2,
              alignment: WrapAlignment.center,
              children: [
                Text(widget.downloadLabel,
                    style: const TextStyle(color: textSoft, fontSize: 11)),
                Text(widget.uploadLabel,
                    style: const TextStyle(color: textSoft, fontSize: 11)),
                Text(widget.sessionLabel,
                    style: const TextStyle(
                        color: Color(0x80DFEEFF), fontSize: 11)),
              ],
            ),
          ),
        if (widget.limit > 0) ...[
          ClipRRect(
            borderRadius: BorderRadius.circular(3),
            child: LinearProgressIndicator(
              minHeight: 5,
              backgroundColor: const Color(0x22FFFFFF),
              valueColor: AlwaysStoppedAnimation(
                  fraction < .2 ? dangerRed : connectedBlueSoft),
              value: fraction,
            ),
          ),
          const SizedBox(height: 5),
        ],
        Row(children: [
          Expanded(
            child: Text(
              widget.usageLabel,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: textSoft, fontSize: 10.5),
            ),
          ),
        ]),
        const SizedBox(height: 4),
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: Text(widget.deviceLabel,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color: Color(0x80DFEEFF), fontSize: 10)),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(widget.exitLabel,
                textAlign: TextAlign.end,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    color: Color(0x80DFEEFF), fontSize: 10)),
          ),
        ]),
        const SizedBox(height: 4),
        Row(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.shield_outlined, size: 12, color: textSoft),
          const SizedBox(width: 5),
          Text(widget.words.firewall,
              style: const TextStyle(color: textSoft, fontSize: 10)),
          const SizedBox(width: 6),
          Text(
              widget.killSwitch
                  ? widget.words.activeAccount
                  : widget.words.unavailable,
              style: TextStyle(
                  color: widget.killSwitch
                      ? connectedBlueSoft
                      : const Color(0x66DFEEFF),
                  fontSize: 10)),
        ]),
      ]),
    );
  }

  Widget _bottomRow() => Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
        child: Row(children: [
          const Spacer(),
          _Bubble(
            icon: Icons.public,
            label: widget.words.locations,
            onTap: widget.busy || widget.live ? null : widget.onServers,
          ),
          const Spacer(),
        ]),
      );
}

class _IconButton extends StatelessWidget {
  const _IconButton({required this.icon, required this.tooltip, this.onTap});
  final IconData icon;
  final String tooltip;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Tooltip(
        message: tooltip,
        child: Material(
          color: Colors.transparent,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.all(7),
              child: Icon(icon, color: const Color(0xB3DFEEFF), size: 19),
            ),
          ),
        ),
      );
}

class _Bubble extends StatefulWidget {
  const _Bubble({required this.icon, required this.label, this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  State<_Bubble> createState() => _BubbleState();
}

class _BubbleState extends State<_Bubble> {
  bool hover = false;

  @override
  Widget build(BuildContext context) => MouseRegion(
        onEnter: (_) => setState(() => hover = true),
        onExit: (_) => setState(() => hover = false),
        child: Material(
          color: hover && widget.onTap != null
              ? _WindscribeHomeState.bubbleHover
              : _WindscribeHomeState.bubble,
          borderRadius: BorderRadius.circular(22),
          child: InkWell(
            borderRadius: BorderRadius.circular(22),
            onTap: widget.onTap,
            child: Padding(
              padding:
                  const EdgeInsets.symmetric(horizontal: 18, vertical: 11),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                Icon(widget.icon,
                    size: 16, color: const Color(0xCCDFEEFF)),
                const SizedBox(width: 8),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 220),
                  child: Text(
                    widget.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        color: Colors.white,
                        fontSize: 12.5,
                        fontWeight: FontWeight.w600),
                  ),
                ),
                const SizedBox(width: 8),
                const Icon(Icons.keyboard_arrow_down,
                    size: 16, color: Color(0x99DFEEFF)),
              ]),
            ),
          ),
        ),
      );
}

/// The ring around the connect button: a soft track, a coloured arc that
/// spins while the tunnel negotiates, and a steady full ring once it is up.
class _RingPainter extends CustomPainter {
  _RingPainter(
      {required this.accent, required this.connecting, required this.spin});
  final Color accent;
  final bool connecting;
  final Animation<double> spin;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 7
      ..color = const Color(0x1FFFFFFF);
    canvas.drawCircle(rect.center, size.width / 2 - 6, track);
    final glow = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 7
      ..strokeCap = StrokeCap.round
      ..color = accent.withValues(alpha: .95)
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 6);
    final sweep = connecting ? 1.6 : 2 * math.pi;
    final start = connecting ? spin.value * 2 * math.pi : -math.pi / 2;
    canvas.drawArc(rect.deflate(6), start, sweep, false, glow);
    final solid = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 4.5
      ..strokeCap = StrokeCap.round
      ..color = accent;
    canvas.drawArc(rect.deflate(6), start, sweep, false, solid);
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.accent != accent ||
      old.connecting != connecting ||
      old.spin.value != spin.value;
}
