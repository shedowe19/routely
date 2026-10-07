import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/app_store.dart';
import '../../shared/widgets.dart';
import '../profile/profile_controller.dart';
import '../profile/profile_widgets.dart';

class UserProfileScreen extends StatefulWidget {
  const UserProfileScreen({
    super.key,
    required this.store,
    required this.username,
    required this.onStatusTap,
  });
  final AppStore store;
  final String username;
  final void Function(int) onStatusTap;

  @override
  State<UserProfileScreen> createState() => _UserProfileScreenState();
}

class _UserProfileScreenState extends State<UserProfileScreen> {
  late ProfileController controller;
  final scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _createController();
    scroll.addListener(_loadNearEnd);
  }

  void _createController() {
    controller = ProfileController.forStore(
      widget.store,
      username: widget.username,
    );
    unawaited(controller.refresh());
  }

  @override
  void didUpdateWidget(covariant UserProfileScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.username != widget.username ||
        oldWidget.store != widget.store) {
      controller.dispose();
      _createController();
      if (scroll.hasClients) scroll.jumpTo(0);
    }
  }

  void _loadNearEnd() {
    if (scroll.hasClients && scroll.position.extentAfter < 400) {
      unawaited(controller.loadMore());
    }
  }

  @override
  void dispose() {
    scroll.dispose();
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: RoutelyAppBar(
      title: '@${widget.username}',
      actions: [
        IconButton(
          tooltip: 'Aktualisieren',
          onPressed: controller.refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListenableBuilder(
      listenable: controller,
      builder: (context, _) => ResponsiveContent(
        child: RefreshIndicator(
          onRefresh: controller.refresh,
          child: ListView(
            controller: scroll,
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.only(bottom: 32),
            children: [
              if (controller.user == null)
                SizedBox(
                  height: 360,
                  child: controller.loading
                      ? const StateMessage(
                          icon: Icons.person_search,
                          title: 'Profil wird geladen',
                          message:
                              'Nutzerprofil und sichtbare Fahrten werden vorbereitet.',
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
                ProfileHero(
                  user: controller.user!,
                  followBusy: controller.following,
                  onFollow: controller.user!.id == widget.store.user?.id
                      ? null
                      : controller.toggleFollow,
                ),
                if (controller.historyVisible && controller.offline)
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    child: Text(
                      'Offline: Zuletzt gespeicherte Fahrten werden angezeigt.',
                    ),
                  ),
                if (!controller.historyVisible)
                  const SizedBox(
                    height: 220,
                    child: StateMessage(
                      icon: Icons.lock_outline,
                      title: 'Fahrten sind nicht sichtbar',
                      message:
                          'Dieses Profil gibt seine Fahrten aktuell nicht für dich frei.',
                    ),
                  ),
                if (controller.historyVisible)
                  const SectionTitle(
                    icon: Icons.route,
                    title: 'Fahrten',
                    subtitle: 'Sichtbare Check-ins dieses Profils',
                  ),
                if (controller.historyVisible &&
                    controller.statuses.isEmpty &&
                    !controller.loading)
                  const SizedBox(
                    height: 220,
                    child: StateMessage(
                      icon: Icons.train,
                      title: 'Keine Fahrten sichtbar',
                      message:
                          'Dieses Profil hat aktuell keine sichtbaren Fahrten.',
                    ),
                  ),
                if (controller.historyVisible)
                  for (final status in controller.statuses)
                    StatusCard(
                      status: status,
                      onStatusTap: () => widget.onStatusTap(status.id),
                    ),
                if (controller.loading)
                  const Padding(
                    padding: EdgeInsets.all(20),
                    child: Center(child: CircularProgressIndicator()),
                  )
                else if (controller.historyVisible &&
                    controller.hasMore &&
                    controller.error == null)
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: TextButton.icon(
                      onPressed: controller.loadMore,
                      icon: const Icon(Icons.expand_more),
                      label: const Text('Weitere Fahrten laden'),
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
