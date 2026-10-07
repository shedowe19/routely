import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../data/app_store.dart';
import '../../data/routely_api.dart';
import '../../shared/theme.dart';

class SetupScreen extends StatefulWidget {
  const SetupScreen({super.key, required this.store});
  final AppStore store;

  @override
  State<SetupScreen> createState() => _SetupScreenState();
}

class _SetupScreenState extends State<SetupScreen> {
  final form = GlobalKey<FormState>();
  final server = TextEditingController(text: 'https://traewelling.de');
  final token = TextEditingController();
  bool showToken = false;
  bool loading = false;
  String? error;
  String? browserError;

  @override
  void dispose() {
    server.dispose();
    token.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (loading || !form.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    setState(() {
      loading = true;
      error = null;
    });
    try {
      await widget.store.login(server.text.trim(), token.text.trim());
      if (mounted) token.clear();
    } catch (failure) {
      if (mounted) setState(() => error = failure.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> _openTokenSettings() async {
    try {
      final base = normalizeServerUrl(server.text);
      if (!await launchUrl(
        Uri.parse('$base/settings#security'),
        mode: LaunchMode.externalApplication,
      )) {
        throw const FormatException(
          'Es ist keine App zum Öffnen dieses Links verfügbar.',
        );
      }
      if (mounted) setState(() => browserError = null);
    } catch (failure) {
      if (mounted) setState(() => browserError = failure.toString());
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [RoutelyColors.indigo, Color(0xff1a237e)],
        ),
      ),
      child: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 36),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: Column(
                children: [
                  Container(
                    width: 108,
                    height: 108,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: Colors.white.withValues(alpha: .1),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: .24),
                      ),
                    ),
                    child: const Icon(
                      Icons.train,
                      color: Colors.white,
                      size: 48,
                    ),
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'Routely',
                    style: TextStyle(
                      fontSize: 36,
                      fontWeight: FontWeight.w900,
                      letterSpacing: 1,
                      color: Colors.white,
                    ),
                  ),
                  Text(
                    'Dein Zug-Check-in Begleiter',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: .8),
                      fontSize: 16,
                    ),
                  ),
                  const SizedBox(height: 18),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    alignment: WrapAlignment.center,
                    children: [
                      _feature(Icons.train, 'Check-ins'),
                      _feature(Icons.timeline, 'Live'),
                      _feature(Icons.people, 'Community'),
                    ],
                  ),
                  const SizedBox(height: 36),
                  Card(
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(30),
                    ),
                    elevation: 12,
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Form(
                        key: form,
                        child: Column(
                          children: [
                            Text(
                              'Anmelden',
                              style: Theme.of(context).textTheme.headlineSmall
                                  ?.copyWith(fontWeight: FontWeight.bold),
                            ),
                            const SizedBox(height: 24),
                            TextFormField(
                              controller: server,
                              enabled: !loading,
                              keyboardType: TextInputType.url,
                              autocorrect: false,
                              textInputAction: TextInputAction.next,
                              decoration: const InputDecoration(
                                labelText: 'Server-URL',
                                hintText: 'https://traewelling.de',
                                prefixIcon: Icon(Icons.language),
                              ),
                              validator: (value) {
                                try {
                                  normalizeServerUrl(value ?? '');
                                  return null;
                                } catch (_) {
                                  return 'Bitte eine gültige HTTPS-Server-URL eingeben.';
                                }
                              },
                            ),
                            const SizedBox(height: 16),
                            TextFormField(
                              controller: token,
                              enabled: !loading,
                              obscureText: !showToken,
                              autocorrect: false,
                              enableSuggestions: false,
                              textInputAction: TextInputAction.done,
                              onFieldSubmitted: (_) => _login(),
                              validator: (value) =>
                                  value?.trim().isNotEmpty == true
                                  ? null
                                  : 'Bitte Access-Token eingeben.',
                              decoration: InputDecoration(
                                labelText: 'Access-Token',
                                hintText: 'Dein persönlicher API-Token',
                                prefixIcon: const Icon(Icons.key),
                                suffixIcon: IconButton(
                                  tooltip: showToken
                                      ? 'Token verbergen'
                                      : 'Token anzeigen',
                                  onPressed: () =>
                                      setState(() => showToken = !showToken),
                                  icon: Icon(
                                    showToken
                                        ? Icons.visibility_off
                                        : Icons.visibility,
                                  ),
                                ),
                              ),
                            ),
                            const SizedBox(height: 12),
                            Container(
                              width: double.infinity,
                              padding: const EdgeInsets.all(12),
                              decoration: BoxDecoration(
                                color: Theme.of(
                                  context,
                                ).colorScheme.primary.withValues(alpha: .06),
                                borderRadius: BorderRadius.circular(16),
                              ),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Row(
                                    children: [
                                      Icon(Icons.info_outline, size: 18),
                                      SizedBox(width: 6),
                                      Expanded(
                                        child: Text(
                                          'Wo finde ich meinen Token?',
                                          style: TextStyle(
                                            fontWeight: FontWeight.bold,
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                  const SizedBox(height: 6),
                                  const Text(
                                    'Einstellungen → Sicherheit → API-Tokens',
                                  ),
                                  TextButton.icon(
                                    onPressed: _openTokenSettings,
                                    icon: const Icon(
                                      Icons.open_in_new,
                                      size: 16,
                                    ),
                                    label: const Text('Im Browser öffnen'),
                                  ),
                                  if (browserError != null)
                                    Text(
                                      browserError!,
                                      style: TextStyle(
                                        color: Theme.of(
                                          context,
                                        ).colorScheme.error,
                                      ),
                                    ),
                                ],
                              ),
                            ),
                            if (error != null)
                              Padding(
                                padding: const EdgeInsets.only(top: 16),
                                child: Semantics(
                                  liveRegion: true,
                                  child: Text(
                                    error!,
                                    style: TextStyle(
                                      color: Theme.of(
                                        context,
                                      ).colorScheme.error,
                                    ),
                                  ),
                                ),
                              ),
                            const SizedBox(height: 24),
                            SizedBox(
                              width: double.infinity,
                              child: FilledButton.icon(
                                onPressed: loading ? null : _login,
                                style: FilledButton.styleFrom(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 18,
                                  ),
                                ),
                                icon: loading
                                    ? const SizedBox(
                                        height: 22,
                                        width: 22,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(Icons.login),
                                label: Text(
                                  loading ? 'Anmeldung läuft…' : 'Anmelden',
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    ),
  );

  Widget _feature(IconData icon, String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .1),
      borderRadius: BorderRadius.circular(99),
      border: Border.all(color: Colors.white.withValues(alpha: .2)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: Colors.white, size: 16),
        const SizedBox(width: 6),
        Text(label, style: const TextStyle(color: Colors.white)),
      ],
    ),
  );
}
