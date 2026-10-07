import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/app_store.dart';
import '../../shared/widgets.dart';
import 'feed_controller.dart';

class FeedScreen extends StatefulWidget {
  const FeedScreen({
    super.key,
    required this.store,
    required this.onStatusTap,
    required this.onUserTap,
    required this.onSearchUsersTap,
  });
  final AppStore store;
  final void Function(int) onStatusTap;
  final void Function(String) onUserTap;
  final VoidCallback onSearchUsersTap;

  @override
  State<FeedScreen> createState() => _FeedScreenState();
}

class _FeedScreenState extends State<FeedScreen> {
  late FeedController controller;
  final scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    controller = FeedController.forStore(widget.store);
    scroll.addListener(_loadNearEnd);
    unawaited(controller.refresh());
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
      title: 'Routely',
      actions: [
        IconButton(
          tooltip: 'Benutzer suchen',
          onPressed: widget.onSearchUsersTap,
          icon: const Icon(Icons.search),
        ),
        IconButton(
          tooltip: 'Aktualisieren',
          onPressed: controller.refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListenableBuilder(
      listenable: controller,
      builder: (context, _) => Column(
        children: [
          Material(
            color: Theme.of(context).colorScheme.surface,
            child: Row(
              children: [
                _tab('Freunde', Icons.people, FeedType.dashboard),
                _tab('Global', Icons.public, FeedType.global),
              ],
            ),
          ),
          Expanded(
            child: ResponsiveContent(
              child: RefreshIndicator(
                onRefresh: controller.refresh,
                child: ListView(
                  controller: scroll,
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.only(top: 8, bottom: 32),
                  children: [
                    if (controller.offline)
                      const Padding(
                        padding: EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 8,
                        ),
                        child: Row(
                          children: [
                            Icon(Icons.cloud_off_outlined),
                            SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'Offline: Zuletzt gespeicherte Fahrten werden angezeigt.',
                              ),
                            ),
                          ],
                        ),
                      ),
                    if (controller.error != null &&
                        controller.statuses.isNotEmpty)
                      _errorBanner(),
                    if (controller.statuses.isEmpty)
                      SizedBox(height: 360, child: _emptyState())
                    else ...[
                      for (final status in controller.statuses)
                        StatusCard(
                          status: status,
                          likeBusy: controller.isLikePending(status.id),
                          onLike: status.isLikable
                              ? () => controller.toggleLike(status.id)
                              : null,
                          onStatusTap: () => widget.onStatusTap(status.id),
                          onUserTap: status.user == null
                              ? null
                              : () => widget.onUserTap(status.user!.username),
                        ),
                      if (controller.loading || controller.refreshing)
                        const Padding(
                          padding: EdgeInsets.all(20),
                          child: Center(child: CircularProgressIndicator()),
                        )
                      else if (controller.hasMore && controller.error == null)
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
        ],
      ),
    ),
  );

  Widget _tab(String label, IconData icon, FeedType type) {
    final selected = controller.type == type;
    final colors = Theme.of(context).colorScheme;
    return Expanded(
      child: Semantics(
        selected: selected,
        button: true,
        label: label,
        child: InkWell(
          onTap: () {
            if (scroll.hasClients) scroll.jumpTo(0);
            unawaited(controller.switchType(type));
          },
          child: Container(
            padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
            decoration: BoxDecoration(
              border: Border(
                bottom: BorderSide(
                  color: selected ? colors.primary : Colors.transparent,
                  width: 3,
                ),
              ),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  color: selected ? colors.primary : colors.onSurfaceVariant,
                ),
                const SizedBox(width: 8),
                Flexible(
                  child: Text(
                    label,
                    style: TextStyle(
                      color: selected
                          ? colors.primary
                          : colors.onSurfaceVariant,
                      fontWeight: selected
                          ? FontWeight.bold
                          : FontWeight.normal,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _emptyState() {
    if (controller.loading || controller.refreshing) {
      return const StateMessage(
        icon: Icons.train,
        title: 'Feed wird geladen',
        message: 'Wir holen die neuesten Fahrten für dich.',
        loading: true,
      );
    }
    if (controller.error != null) {
      return StateMessage(
        icon: Icons.error_outline,
        title: 'Feed konnte nicht geladen werden',
        message: controller.error,
        actionLabel: 'Erneut versuchen',
        onAction: controller.refresh,
      );
    }
    return StateMessage(
      icon: Icons.train,
      title: 'Noch keine Fahrten',
      message: controller.type == FeedType.dashboard
          ? 'Folge anderen Nutzern, um ihre Check-ins hier zu sehen.'
          : 'Hier erscheinen die neuesten öffentlichen Check-ins.',
      actionLabel: controller.type == FeedType.dashboard
          ? 'Benutzer suchen'
          : 'Aktualisieren',
      onAction: controller.type == FeedType.dashboard
          ? widget.onSearchUsersTap
          : controller.refresh,
    );
  }

  Widget _errorBanner() => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          controller.error!,
          style: TextStyle(color: Theme.of(context).colorScheme.error),
        ),
        TextButton(
          onPressed: controller.refresh,
          child: const Text('Erneut versuchen'),
        ),
      ],
    ),
  );
}
