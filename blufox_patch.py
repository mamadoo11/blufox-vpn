from pathlib import Path
import re
root = Path("app/lib")
screen = root / "screens/home_screen.dart"
s = screen.read_text()
imp = "import 'home/widgets/blufox_home_panel.dart';"
if imp not in s:
    anchor = "import 'home/widgets/home_controls.dart';"
    if anchor not in s: raise SystemExit("Could not find HomeControls import anchor")
    s = s.replace(anchor, anchor + "\n" + imp, 1)
pattern = re.compile(r"                    HomeControls\(\n.*?\n                    \),", re.DOTALL)
replacement = """                    BluFoxHomePanel(
                      state: state,
                      onToggle: () {
                        if (state.tunnelUp) {
                          confirmStop(context, _controller, state);
                        } else {
                          unawaited(_startWithAutoRefresh());
                        }
                      },
                    ),"""
s2, count = pattern.subn(replacement, s, count=1)
if count != 1: raise SystemExit(f"Expected one HomeControls block, found {count}")
traffic_pattern = re.compile(
    r"                    if \(state\.tunnelUp &&\n"
    r"                        \(!_filter\.panelExpanded \|\| state\.showingNetworks\)\)\n"
    r"                      TrafficBar\(\n.*?\n                      \),", re.DOTALL)
s3, traffic_count = traffic_pattern.subn("", s2, count=1)
if traffic_count != 1: raise SystemExit(f"Expected one TrafficBar block, found {traffic_count}")
s3 = s3.replace("title: const Text('L×Box'),", "title: const Text('BluFox VPN'),", 1)
screen.write_text(s3)
panel = root / "screens/home/widgets/blufox_home_panel.dart"
panel.write_text(r'''import 'package:flutter/material.dart';
import '../../../models/home_state.dart';
import '../../../models/tunnel_status.dart';

class BluFoxHomePanel extends StatelessWidget {
  const BluFoxHomePanel({super.key, required this.state, required this.onToggle});
  final HomeState state;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final connected = state.tunnelUp;
    final connecting = state.tunnel == TunnelStatus.connecting;
    final stopping = state.tunnel == TunnelStatus.stopping;
    final busy = state.busy || connecting || stopping;
    final accent = connected ? const Color(0xFF20B26B)
        : connecting || stopping ? const Color(0xFFE6A23C) : colors.primary;
    final status = connected ? 'متصل'
        : connecting ? 'در حال اتصال…'
        : stopping ? 'در حال قطع اتصال…'
        : state.lastError != null ? 'اتصال ناموفق' : 'قطع شده';

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            padding: const EdgeInsets.fromLTRB(18, 20, 18, 18),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(28),
              gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight,
                colors: [accent.withValues(alpha: 0.20), theme.cardColor]),
              border: Border.all(color: accent.withValues(alpha: 0.42)),
            ),
            child: Column(children: [
              Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Container(width: 10, height: 10,
                  decoration: BoxDecoration(color: accent, shape: BoxShape.circle,
                    boxShadow: [BoxShadow(color: accent.withValues(alpha: 0.42), blurRadius: 10, spreadRadius: 1)])),
                const SizedBox(width: 9),
                Text(status, style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800,
                  color: connected ? const Color(0xFF159957) : colors.onSurface)),
              ]),
              const SizedBox(height: 18),
              SizedBox(width: double.infinity, height: 82,
                child: FilledButton(
                  onPressed: busy ? null : onToggle,
                  style: FilledButton.styleFrom(
                    backgroundColor: connected ? const Color(0xFF159957) : colors.primary,
                    disabledBackgroundColor: accent.withValues(alpha: 0.60),
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22))),
                  child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    if (busy)
                      const SizedBox(width: 27, height: 27, child: CircularProgressIndicator(strokeWidth: 3, color: Colors.white))
                    else
                      Icon(connected ? Icons.power_settings_new_rounded : Icons.play_arrow_rounded, size: 34),
                    const SizedBox(width: 12),
                    Text(busy ? (connecting ? 'در حال اتصال' : stopping ? 'در حال قطع اتصال' : 'لطفاً صبر کن')
                        : connected ? 'قطع اتصال' : 'اتصال به VPN',
                      style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w800)),
                  ]))),
              const SizedBox(height: 10),
              Text(connected ? 'اتصال امن فعال است' : 'برای اتصال بزن',
                style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
              if (!connected && state.lastError != null) ...[
                const SizedBox(height: 10),
                Text('اتصال برقرار نشد؛ سرور یا اینترنت را بررسی کن.', textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(color: colors.error)),
              ],
            ]),
          ),
          const SizedBox(height: 14),
          Row(children: [
            Expanded(child: _TrafficCard(title: 'آپلود', value: state.traffic.uploadFormatted,
              icon: Icons.arrow_upward_rounded, color: const Color(0xFF4F8EF7))),
            const SizedBox(width: 12),
            Expanded(child: _TrafficCard(title: 'دانلود', value: state.traffic.downloadFormatted,
              icon: Icons.arrow_downward_rounded, color: const Color(0xFF20B26B))),
          ]),
          if (connected) ...[
            const SizedBox(height: 10),
            Container(padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 12),
              decoration: BoxDecoration(color: theme.cardColor, borderRadius: BorderRadius.circular(16),
                border: Border.all(color: colors.outlineVariant)),
              child: Row(children: [
                Icon(Icons.swap_vert_rounded, color: colors.onSurfaceVariant),
                const SizedBox(width: 8), const Expanded(child: Text('اتصالات فعال')),
                Text('\${state.traffic.connectionsIn}',
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800)),
              ])),
          ],
        ],
      ),
    );
  }
}

class _TrafficCard extends StatelessWidget {
  const _TrafficCard({required this.title, required this.value, required this.icon, required this.color});
  final String title;
  final String value;
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: theme.cardColor, borderRadius: BorderRadius.circular(20),
        border: Border.all(color: theme.colorScheme.outlineVariant)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [Icon(icon, color: color, size: 20), const SizedBox(width: 7),
          Text(title, style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700))]),
        const SizedBox(height: 12),
        Text(value, maxLines: 1, overflow: TextOverflow.ellipsis,
          style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 3),
        Text('حجم ترافیک', style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      ]),
    );
  }
}
''')
print("BluFox home panel patch applied.")

# Enable the existing public-server picker and point it to BluFox's maintained
# manifest. This reuses the upstream subscription parser; no server credentials
# are embedded in the APK.
loader = root / "services/community_servers_loader.dart"
ls = loader.read_text()
old_enabled = "static const enabled = false;"
if old_enabled in ls:
    ls = ls.replace(old_enabled, "static const enabled = true;", 1)
elif "static const enabled = true;" not in ls:
    raise SystemExit("Could not find community server enable flag")
old_url = "https://raw.githubusercontent.com/Leadaxe/LxBox/main/public-servers-manifest.json"
new_url = "https://raw.githubusercontent.com/mamadoo11/blufox-vpn/main/free-servers-manifest.json"
if old_url in ls:
    ls = ls.replace(old_url, new_url, 1)
elif new_url not in ls:
    raise SystemExit("Could not find community server manifest URL")
loader.write_text(ls)

server_picker = root / "screens/subscriptions_screen/public_test_servers.dart"
ps = server_picker.read_text()
ps = ps.replace('getLocalText.s("Get Public Test Servers")', 'getLocalText.s("Free Servers")', 1)
ps = ps.replace('getLocalText.s("List %d", i + 1)', 'getLocalText.s("Free server source %d", i + 1)', 1)
server_picker.write_text(ps)
print("BluFox free server source enabled.")
