import 'package:flutter/material.dart';

import '../../data/models.dart';
import '../../shared/theme.dart';
import '../../shared/widgets.dart';

String formatProfileDuration(int minutes) =>
    minutes >= 60 ? '${minutes ~/ 60}h ${minutes % 60}m' : '${minutes}m';
String formatProfileDistance(int meters) {
  final number = (meters / 1000).round().toString();
  return '${number.replaceAllMapped(RegExp(r'(\d)(?=(\d{3})+$)'), (match) => '${match[1]}.')} km';
}

class ProfileHero extends StatelessWidget {
  const ProfileHero({
    super.key,
    required this.user,
    this.followBusy = false,
    this.onFollow,
  });
  final User user;
  final bool followBusy;
  final VoidCallback? onFollow;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(16),
    child: Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            RoutelyColors.indigo,
            Theme.of(context).colorScheme.primary.withValues(alpha: .88),
            RoutelyColors.teal.withValues(alpha: .85),
          ],
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: .16),
            blurRadius: 18,
            offset: const Offset(0, 7),
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            Container(
              padding: const EdgeInsets.all(3),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: Colors.white.withValues(alpha: .75),
                  width: 3,
                ),
              ),
              child: Avatar(user: user, size: 98),
            ),
            const SizedBox(height: 16),
            Text(
              user.displayName ?? user.username,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 26,
                fontWeight: FontWeight.bold,
                color: Colors.white,
              ),
            ),
            const SizedBox(height: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: .14),
                borderRadius: BorderRadius.circular(99),
              ),
              child: Text(
                '@${user.username}',
                style: const TextStyle(color: Colors.white),
              ),
            ),
            if (user.bio?.trim().isNotEmpty == true) ...[
              const SizedBox(height: 14),
              Text(
                user.bio!,
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.white.withValues(alpha: .86)),
              ),
            ],
            if (onFollow != null) ...[
              const SizedBox(height: 16),
              FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: Colors.white,
                  foregroundColor: RoutelyColors.indigo,
                ),
                onPressed: followBusy || user.followPending == true
                    ? null
                    : onFollow,
                icon: followBusy
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Icon(
                        user.followPending == true
                            ? Icons.hourglass_top
                            : user.following == true
                            ? Icons.person_remove
                            : Icons.person_add,
                      ),
                label: Text(
                  user.followPending == true
                      ? 'Angefragt'
                      : user.following == true
                      ? 'Entfolgen'
                      : 'Folgen',
                ),
              ),
            ],
            const SizedBox(height: 24),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: [
                _heroStat(
                  'Distanz',
                  formatProfileDistance(user.totalDistance ?? 0),
                ),
                _heroStat(
                  'Zeit',
                  formatProfileDuration(user.totalDuration ?? 0),
                ),
                if (user.pointsEnabled != false)
                  _heroStat('Punkte', '${user.points ?? 0}'),
              ],
            ),
          ],
        ),
      ),
    ),
  );

  Widget _heroStat(String label, String value) => Container(
    constraints: const BoxConstraints(minWidth: 90),
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: .13),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: Colors.white.withValues(alpha: .16)),
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          value,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 17,
            fontWeight: FontWeight.bold,
          ),
        ),
        Text(
          label,
          style: TextStyle(
            color: Colors.white.withValues(alpha: .8),
            fontSize: 12,
          ),
        ),
      ],
    ),
  );
}

class ProfileStatistics extends StatelessWidget {
  const ProfileStatistics({super.key, required this.statistics});
  final Statistics statistics;

  @override
  Widget build(BuildContext context) {
    final categories = statistics.categories;
    final count = categories.fold<int>(
      0,
      (sum, category) => sum + category.count,
    );
    final duration = categories.fold<int>(
      0,
      (sum, category) => sum + category.duration,
    );
    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Fahrten (letzte 28 Tage)',
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 16,
              runSpacing: 12,
              children: [
                StatPill(label: 'Fahrten', value: '$count'),
                StatPill(label: 'Zeit', value: formatProfileDuration(duration)),
              ],
            ),
            if (categories.isNotEmpty) ...[
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 12),
                child: Divider(),
              ),
              Text(
                'Verkehrsmittel',
                style: Theme.of(context).textTheme.labelLarge,
              ),
              for (final category in categories)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final label = Text(_category(category.name ?? ''));
                      final value = Text(
                        '${category.count}×  ${formatProfileDuration(category.duration)}',
                        style: TextStyle(
                          fontWeight: FontWeight.w600,
                          color: Theme.of(context).colorScheme.secondary,
                        ),
                      );
                      if (constraints.maxWidth < 400 ||
                          MediaQuery.textScalerOf(context).scale(16) > 24) {
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [label, const SizedBox(height: 4), value],
                        );
                      }
                      return Row(
                        children: [
                          Expanded(child: label),
                          const SizedBox(width: 12),
                          Flexible(child: value),
                        ],
                      );
                    },
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }

  String _category(String name) =>
      const {
        'nationalExpress': 'Fernverkehr (ICE/IC)',
        'national': 'Fernverkehr',
        'regionalExp': 'RegionalExpress',
        'regional': 'Regional (RE/RB)',
        'suburban': 'S-Bahn',
        'subway': 'U-Bahn',
        'tram': 'Straßenbahn',
        'bus': 'Bus',
        'ferry': 'Fähre',
      }[name] ??
      name;
}
