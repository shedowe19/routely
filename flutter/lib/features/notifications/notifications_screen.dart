import 'dart:async';

import 'package:flutter/material.dart';

import '../../data/app_store.dart';
import '../../data/models.dart';
import '../../shared/widgets.dart';
import 'notifications_controller.dart';

class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({
    super.key,
    required this.store,
    this.onUnreadCountChanged,
    this.onNotificationLink,
  });

  final AppStore store;
  final ValueChanged<int>? onUnreadCountChanged;
  final ValueChanged<String>? onNotificationLink;

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  final _scroll = ScrollController();
  NotificationsController? _controller;
  String _revision = '';

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_loadMoreIfNeeded);
    widget.store.addListener(_storeChanged);
    _bindSession();
  }

  void _bindSession() {
    _controller?.removeListener(_changed);
    _controller?.dispose();
    _controller = null;
    _revision = widget.store.sessionRevision;
    if (!widget.store.authenticated) return;
    // Capture this API instance; an old controller cannot use a new account.
    final api = widget.store.api;
    _controller = NotificationsController(
      loadPage: (page) => api.notifications(page: page),
      unreadCount: api.unreadCount,
      markRead: api.markNotificationRead,
      markAllRead: api.markAllNotificationsRead,
      sessionRevision: () => widget.store.sessionRevision,
      onUnreadCountChanged: (count) => widget.onUnreadCountChanged?.call(count),
    )..addListener(_changed);
    unawaited(_controller!.refresh());
  }

  void _storeChanged() {
    if (_revision == widget.store.sessionRevision) return;
    if (!mounted) return;
    setState(_bindSession);
  }

  void _changed() {
    if (!mounted) return;
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _loadMoreIfNeeded();
    });
  }

  void _loadMoreIfNeeded() {
    final controller = _controller;
    if (controller == null ||
        !_scroll.hasClients ||
        controller.state.error != null) {
      return;
    }
    if (_scroll.position.extentAfter < 240) {
      unawaited(controller.loadMore());
    }
  }

  @override
  void didUpdateWidget(covariant NotificationsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.store != widget.store) {
      oldWidget.store.removeListener(_storeChanged);
      widget.store.addListener(_storeChanged);
      _bindSession();
    }
  }

  @override
  void dispose() {
    widget.store.removeListener(_storeChanged);
    _controller?.removeListener(_changed);
    _controller?.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    final state = controller?.state ?? const NotificationsState();
    final colors = Theme.of(context).colorScheme;
    final compact = MediaQuery.sizeOf(context).width < 390;
    return Scaffold(
      appBar: RoutelyAppBar(
        title: 'Meldungen',
        actions: [
          IconButton(
            tooltip: 'Aktualisieren',
            onPressed: controller == null || state.isRefreshing
                ? null
                : () => unawaited(controller.refresh()),
            icon: const Icon(Icons.refresh),
          ),
          if (state.unreadCount > 0 || controller?.isMarkingAll == true)
            if (compact)
              IconButton(
                tooltip: 'Alle als gelesen markieren',
                onPressed: controller?.canMarkAll == true
                    ? () => unawaited(controller!.markAllAsRead())
                    : null,
                icon: const Icon(Icons.done_all),
              )
            else
              TextButton.icon(
                onPressed: controller?.canMarkAll == true
                    ? () => unawaited(controller!.markAllAsRead())
                    : null,
                icon: const Icon(Icons.done_all, size: 18),
                label: const Text('Alle gelesen'),
              ),
        ],
      ),
      body: ResponsiveContent(
        child: Column(
          children: [
            if (state.error != null && state.notifications.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 8, 0),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        state.error!,
                        style: TextStyle(color: colors.error),
                      ),
                    ),
                    IconButton(
                      tooltip: 'Fehlermeldung schließen',
                      onPressed: controller?.clearError,
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
              ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  return RefreshIndicator(
                    onRefresh: () =>
                        controller?.refresh() ?? Future<void>.value(),
                    child: ListView.builder(
                      controller: _scroll,
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.symmetric(vertical: 10),
                      itemCount: state.notifications.isEmpty
                          ? 1
                          : state.notifications.length + 1,
                      itemBuilder: (context, index) {
                        if (state.notifications.isEmpty) {
                          return SizedBox(
                            height: (constraints.maxHeight - 20)
                                .clamp(0.0, double.infinity)
                                .toDouble(),
                            child: _emptyState(controller, state),
                          );
                        }
                        if (index == state.notifications.length) {
                          if (state.isLoading) {
                            return const Padding(
                              padding: EdgeInsets.all(20),
                              child: Center(child: CircularProgressIndicator()),
                            );
                          }
                          if (state.hasMore && state.error != null) {
                            return Center(
                              child: TextButton.icon(
                                onPressed: () =>
                                    unawaited(controller!.loadMore()),
                                icon: const Icon(Icons.refresh),
                                label: const Text(
                                  'Weitere Meldungen erneut laden',
                                ),
                              ),
                            );
                          }
                          return const SizedBox(height: 16);
                        }
                        final notification = state.notifications[index];
                        return _NotificationCard(
                          notification: notification,
                          onTap: () {
                            if (!notification.isRead) {
                              unawaited(
                                controller!.markAsRead(notification.id),
                              );
                            }
                            final link = notification.link;
                            if (_revision == widget.store.sessionRevision &&
                                link != null &&
                                link.trim().isNotEmpty) {
                              widget.onNotificationLink?.call(link);
                            }
                          },
                        );
                      },
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _emptyState(
    NotificationsController? controller,
    NotificationsState state,
  ) {
    if (controller == null) {
      return const StateMessage(
        icon: Icons.person_outline,
        title: 'Anmeldung erforderlich',
        message: 'Melde dich an, um deine Benachrichtigungen zu sehen.',
      );
    }
    if (state.isLoading || state.isRefreshing) {
      return const StateMessage(
        icon: Icons.notifications,
        title: 'Meldungen werden geladen',
        message: 'Neue Benachrichtigungen werden abgefragt.',
        loading: true,
      );
    }
    if (state.error != null) {
      return StateMessage(
        icon: Icons.error_outline,
        title: 'Meldungen konnten nicht geladen werden',
        message: state.error,
        actionLabel: 'Erneut versuchen',
        onAction: () => unawaited(controller.refresh()),
      );
    }
    return const StateMessage(
      icon: Icons.notifications_none,
      title: 'Alles erledigt',
      message: 'Du hast aktuell keine Benachrichtigungen.',
    );
  }
}

class _NotificationCard extends StatelessWidget {
  const _NotificationCard({required this.notification, required this.onTap});
  final AppNotification notification;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final type = notification.type?.toLowerCase() ?? '';
    final unread = !notification.isRead;
    final (icon, tint) = switch (type) {
      final value when value.contains('liked') => (
        Icons.favorite,
        colors.error,
      ),
      final value when value.contains('follow') => (
        Icons.person_add,
        colors.primary,
      ),
      final value when value.contains('connection') => (
        Icons.train,
        colors.secondary,
      ),
      final value when value.contains('mention') => (
        Icons.alternate_email,
        colors.primary,
      ),
      _ => (Icons.notifications, colors.primary),
    };
    final lead = notification.lead?.trim();
    final notice = notification.notice?.trim();
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Card(
        margin: EdgeInsets.zero,
        elevation: unread ? 5 : 2,
        color: unread ? colors.primary.withValues(alpha: 0.05) : colors.surface,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: tint.withValues(alpha: unread ? 0.2 : 0.08)),
        ),
        child: InkWell(
          onTap: onTap,
          child: IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (unread)
                  Container(width: 4, color: tint.withValues(alpha: 0.8)),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Container(
                          padding: const EdgeInsets.all(10),
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: tint.withValues(alpha: 0.12),
                          ),
                          child: Icon(icon, size: 24, color: tint),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                lead == null || lead.isEmpty
                                    ? 'Benachrichtigung'
                                    : lead,
                                style: theme.textTheme.bodyMedium?.copyWith(
                                  fontWeight: unread
                                      ? FontWeight.bold
                                      : FontWeight.w500,
                                  color: unread
                                      ? colors.primary
                                      : colors.onSurface,
                                ),
                              ),
                              if (notice != null && notice.isNotEmpty) ...[
                                const SizedBox(height: 4),
                                Text(
                                  notice,
                                  maxLines: 3,
                                  overflow: TextOverflow.ellipsis,
                                  style: theme.textTheme.bodySmall?.copyWith(
                                    color: colors.onSurface.withValues(
                                      alpha: 0.7,
                                    ),
                                  ),
                                ),
                              ],
                              if (_timestamp(notification).isNotEmpty) ...[
                                const SizedBox(height: 8),
                                Text(
                                  _timestamp(notification),
                                  style: theme.textTheme.labelSmall?.copyWith(
                                    color: colors.onSurface.withValues(
                                      alpha: 0.5,
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                        if (unread) ...[
                          const SizedBox(width: 8),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 8,
                              vertical: 3,
                            ),
                            decoration: BoxDecoration(
                              color: colors.secondary.withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(99),
                            ),
                            child: Text(
                              'Neu',
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: colors.secondary,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ],
                      ],
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

  String _timestamp(AppNotification notification) {
    final human = notification.createdAtForHumans;
    if (human != null && human.isNotEmpty) return human;
    final parsed = DateTime.tryParse(notification.createdAt ?? '')?.toLocal();
    if (parsed == null) return '';
    String padded(int value) => '$value'.padLeft(2, '0');
    return '${padded(parsed.day)}.${padded(parsed.month)}.${parsed.year} '
        '${padded(parsed.hour)}:${padded(parsed.minute)}';
  }
}
