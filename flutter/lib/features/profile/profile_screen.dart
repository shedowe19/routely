import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/app_store.dart';
import '../../shared/widgets.dart';
import 'profile_controller.dart';
import 'profile_widgets.dart';

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({
    super.key,
    required this.store,
    required this.onStatusTap,
    required this.onSettingsTap,
  });
  final AppStore store;
  final void Function(int) onStatusTap;
  final VoidCallback onSettingsTap;

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  late final controller = ProfileController.forStore(widget.store);
  bool loggingOut = false;

  @override
  void initState() {
    super.initState();
    unawaited(controller.refresh());
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> _logout() async {
    if (loggingOut) return;
    setState(() => loggingOut = true);
    try {
      await widget.store.logout();
    } catch (failure) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Abmeldung fehlgeschlagen: $failure')),
        );
      }
    } finally {
      if (mounted) setState(() => loggingOut = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: RoutelyAppBar(
      title: 'Profil',
      actions: [
        IconButton(
          tooltip: 'Aktualisieren',
          onPressed: controller.refresh,
          icon: const Icon(Icons.refresh),
        ),
        IconButton(
          tooltip: 'Abmelden',
          onPressed: loggingOut ? null : _logout,
          icon: const Icon(Icons.logout),
        ),
      ],
    ),
    body: ListenableBuilder(
      listenable: controller,
      builder: (context, _) => ResponsiveContent(
        child: RefreshIndicator(
          onRefresh: controller.refresh,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.only(bottom: 32),
            children: [
              if (controller.user == null)
                SizedBox(
                  height: 360,
                  child: controller.loading
                      ? const StateMessage(
                          icon: Icons.person,
                          title: 'Profil wird geladen',
                          message:
                              'Statistiken und letzte Fahrten werden vorbereitet.',
                          loading: true,
                        )
                      : StateMessage(
                          icon: Icons.error_outline,
                          title: 'Profil konnte nicht geladen werden',
                          message: controller.error,
                          actionLabel: 'Erneut versuchen',
                          onAction: controller.refresh,
                        ),
                )
              else ...[
                if (controller.error != null)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          controller.error!,
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                        TextButton(
                          onPressed: controller.refresh,
                          child: const Text('Erneut versuchen'),
                        ),
                      ],
                    ),
                  ),
                ProfileHero(user: controller.user!),
                if (controller.offline)
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: Text(
                      'Offline: Zuletzt gespeicherte Fahrten werden angezeigt.',
                    ),
                  ),
                if (controller.statistics != null)
                  ProfileStatistics(statistics: controller.statistics!),
                const SectionTitle(
                  icon: Icons.route,
                  title: 'Letzte Fahrten',
                  subtitle: 'Deine neuesten Check-ins',
                ),
                if (controller.statuses.isEmpty && !controller.loading)
                  const SizedBox(
                    height: 200,
                    child: StateMessage(
                      icon: Icons.train,
                      title: 'Noch keine Fahrten',
                      message: 'Deine Check-ins erscheinen hier.',
                    ),
                  ),
                for (final status in controller.statuses)
                  StatusCard(
                    status: status,
                    onStatusTap: () => widget.onStatusTap(status.id),
                  ),
                if (controller.loading)
                  const Padding(
                    padding: EdgeInsets.all(16),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
                  child: OutlinedButton.icon(
                    onPressed: widget.onSettingsTap,
                    icon: const Icon(Icons.settings),
                    label: const Text('Einstellungen'),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      foregroundColor: Theme.of(context).colorScheme.error,
                    ),
                    onPressed: loggingOut ? null : _logout,
                    icon: const Icon(Icons.logout),
                    label: Text(loggingOut ? 'Abmeldung läuft…' : 'Abmelden'),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 20, bottom: 8),
                  child: Text(
                    'Version ${const String.fromEnvironment('ROUTELY_VERSION', defaultValue: '2.0.0')}',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    ),
  );
}
