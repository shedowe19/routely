import 'package:flutter/material.dart';

import '../data/models.dart';
import 'theme.dart';

class RoutelyAppBar extends StatelessWidget implements PreferredSizeWidget {
  const RoutelyAppBar({
    super.key,
    required this.title,
    this.actions = const [],
    this.leading,
  });
  final String title;
  final List<Widget> actions;
  final Widget? leading;
  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);
  @override
  Widget build(BuildContext context) => AppBar(
    title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
    leading: leading,
    actions: actions,
  );
}

class ResponsiveContent extends StatelessWidget {
  const ResponsiveContent({super.key, required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 760),
      child: child,
    ),
  );
}

class Avatar extends StatelessWidget {
  const Avatar({super.key, required this.user, this.size = 40});
  final User? user;
  final double size;
  @override
  Widget build(BuildContext context) {
    final url = Uri.tryParse(user?.profilePicture ?? '');
    final canLoad =
        url != null &&
        url.scheme == 'https' &&
        url.host.isNotEmpty &&
        url.userInfo.isEmpty;
    final name = user?.displayName ?? user?.username ?? '?';
    final placeholder = Center(
      child: Text(
        name.isEmpty ? '?' : name.characters.first.toUpperCase(),
        style: TextStyle(fontSize: size * .4, fontWeight: FontWeight.bold),
      ),
    );
    return ClipOval(
      child: SizedBox.square(
        dimension: size,
        child: ColoredBox(
          color: Theme.of(context).colorScheme.secondaryContainer,
          child: canLoad
              ? Image.network(
                  url.toString(),
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => placeholder,
                )
              : placeholder,
        ),
      ),
    );
  }
}

class StateMessage extends StatelessWidget {
  const StateMessage({
    super.key,
    required this.icon,
    required this.title,
    this.message,
    this.loading = false,
    this.actionLabel,
    this.onAction,
  });
  final IconData icon;
  final String title;
  final String? message, actionLabel;
  final bool loading;
  final VoidCallback? onAction;
  @override
  Widget build(BuildContext context) => Center(
    child: SingleChildScrollView(
      primary: false,
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (loading)
            const CircularProgressIndicator()
          else
            Icon(icon, size: 48, color: Theme.of(context).colorScheme.primary),
          const SizedBox(height: 16),
          Text(
            title,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.titleLarge,
          ),
          if (message != null) ...[
            const SizedBox(height: 8),
            Text(message!, textAlign: TextAlign.center),
          ],
          if (actionLabel != null && onAction != null) ...[
            const SizedBox(height: 16),
            FilledButton(onPressed: onAction, child: Text(actionLabel!)),
          ],
        ],
      ),
    ),
  );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle({
    super.key,
    required this.icon,
    required this.title,
    this.subtitle,
  });
  final IconData icon;
  final String title;
  final String? subtitle;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    child: Row(
      children: [
        Icon(icon, color: Theme.of(context).colorScheme.primary),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
              ),
              if (subtitle != null)
                Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      ],
    ),
  );
}

class StatPill extends StatelessWidget {
  const StatPill({super.key, required this.label, required this.value});
  final String label, value;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
    decoration: BoxDecoration(
      color: Theme.of(
        context,
      ).colorScheme.primaryContainer.withValues(alpha: .35),
      borderRadius: BorderRadius.circular(24),
    ),
    child: Column(
      children: [
        Text(value, style: const TextStyle(fontWeight: FontWeight.bold)),
        Text(label, style: Theme.of(context).textTheme.bodySmall),
      ],
    ),
  );
}

String displayTime(String? value) {
  final time = DateTime.tryParse(value ?? '')?.toLocal();
  return time == null
      ? '–'
      : '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

String displayTimestamp(String? value) {
  final time = DateTime.tryParse(value ?? '')?.toLocal();
  if (time == null) return '';
  String padded(int number) => '$number'.padLeft(2, '0');
  return '${padded(time.day)}.${padded(time.month)}.${time.year} '
      '${padded(time.hour)}:${padded(time.minute)}';
}

class LineBadge extends StatelessWidget {
  const LineBadge({
    super.key,
    required this.line,
    this.category,
    this.color,
    this.textColor,
  });
  final String line;
  final String? category, color, textColor;
  Color? _parse(String? value) {
    final hex = value?.replaceFirst('#', '');
    if (hex == null || !RegExp(r'^[0-9a-fA-F]{6}$').hasMatch(hex)) return null;
    return Color(0xff000000 | int.parse(hex, radix: 16));
  }

  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(minWidth: 62, minHeight: 44),
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    decoration: BoxDecoration(
      color: _parse(color) ?? RoutelyColors.transport(category),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      line,
      textAlign: TextAlign.center,
      style: TextStyle(
        color: _parse(textColor) ?? Colors.white,
        fontWeight: FontWeight.bold,
        fontSize: 18,
      ),
    ),
  );
}

class StatusCard extends StatelessWidget {
  const StatusCard({
    super.key,
    required this.status,
    this.onLike,
    this.onStatusTap,
    this.onUserTap,
    this.likeBusy = false,
  });
  final Status status;
  final VoidCallback? onLike, onStatusTap, onUserTap;
  final bool likeBusy;
  @override
  Widget build(BuildContext context) {
    final checkin = status.checkin;
    final createdAt = displayTimestamp(status.createdAt);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Card(
        child: InkWell(
          borderRadius: BorderRadius.circular(24),
          onTap: onStatusTap,
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (status.user != null)
                  InkWell(
                    onTap: onUserTap,
                    child: Row(
                      children: [
                        Avatar(user: status.user),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                status.user!.displayName ??
                                    status.user!.username,
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              Text(
                                '@${status.user!.username}',
                                style: Theme.of(context).textTheme.bodySmall,
                              ),
                              if (createdAt.isNotEmpty)
                                Text(
                                  createdAt,
                                  style: Theme.of(context).textTheme.labelSmall
                                      ?.copyWith(
                                        color: Theme.of(context)
                                            .colorScheme
                                            .onSurface
                                            .withValues(alpha: .5),
                                      ),
                                ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                if (status.user == null && createdAt.isNotEmpty)
                  Text(
                    createdAt,
                    style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: Theme.of(
                        context,
                      ).colorScheme.onSurface.withValues(alpha: .5),
                    ),
                  ),
                if (checkin != null) ...[
                  const SizedBox(height: 18),
                  Row(
                    children: [
                      LineBadge(
                        line: checkin.lineName ?? 'Fahrt',
                        category: checkin.category,
                        color: checkin.routeColor,
                        textColor: checkin.routeTextColor,
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Text(
                          checkin.operatorName ?? checkin.category ?? '',
                          style: Theme.of(context).textTheme.bodyMedium,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  _stop(
                    context,
                    checkin.origin,
                    checkin.manualDeparture ??
                        checkin.origin?.effectiveDeparture,
                    false,
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 5),
                    child: SizedBox(
                      height: 22,
                      child: VerticalDivider(
                        width: 3,
                        thickness: 3,
                        color: RoutelyColors.teal.withValues(alpha: .7),
                      ),
                    ),
                  ),
                  _stop(
                    context,
                    checkin.destination,
                    checkin.manualArrival ??
                        checkin.destination?.effectiveArrival,
                    true,
                  ),
                  const SizedBox(height: 16),
                  Wrap(
                    spacing: 12,
                    runSpacing: 8,
                    children: [
                      if (checkin.distanceMeters != null)
                        Text(
                          '${(checkin.distanceMeters! / 1000).toStringAsFixed(1).replaceAll('.', ',')} km',
                        ),
                      if (checkin.duration != null)
                        Text('${checkin.duration} min'),
                      if (checkin.points != null)
                        Text(
                          '${checkin.points} Pkt',
                          style: const TextStyle(color: RoutelyColors.amber),
                        ),
                    ],
                  ),
                ],
                if (status.body?.isNotEmpty == true)
                  Padding(
                    padding: const EdgeInsets.only(top: 16),
                    child: Text(status.body!),
                  ),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    IconButton(
                      tooltip: status.liked
                          ? 'Gefällt mir zurücknehmen'
                          : 'Gefällt mir',
                      onPressed: likeBusy ? null : onLike,
                      icon: likeBusy
                          ? const SizedBox.square(
                              dimension: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : Icon(
                              status.liked
                                  ? Icons.favorite
                                  : Icons.favorite_border,
                              color: status.liked ? RoutelyColors.error : null,
                            ),
                    ),
                    Text('${status.likes}'),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _stop(
    BuildContext context,
    Stop? stop,
    String? time,
    bool destination,
  ) => Row(
    children: [
      Icon(
        Icons.circle,
        size: 12,
        color: destination ? RoutelyColors.amber : RoutelyColors.teal,
      ),
      const SizedBox(width: 10),
      Expanded(
        child: Text(
          stop?.stationName ?? 'Unbekannter Halt',
          style: const TextStyle(fontWeight: FontWeight.w600),
        ),
      ),
      Text(displayTime(time)),
    ],
  );
}
