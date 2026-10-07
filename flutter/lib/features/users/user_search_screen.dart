import 'package:flutter/material.dart';

import '../../data/app_store.dart';
import '../../shared/widgets.dart';
import 'user_search_controller.dart';

class UserSearchScreen extends StatefulWidget {
  const UserSearchScreen({
    super.key,
    required this.store,
    required this.onUserTap,
  });
  final AppStore store;
  final void Function(String) onUserTap;

  @override
  State<UserSearchScreen> createState() => _UserSearchScreenState();
}

class _UserSearchScreenState extends State<UserSearchScreen> {
  late final controller = UserSearchController.forStore(widget.store);
  final text = TextEditingController();

  @override
  void dispose() {
    controller.dispose();
    text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: const RoutelyAppBar(title: 'Benutzer suchen'),
    body: ResponsiveContent(
      child: ListenableBuilder(
        listenable: controller,
        builder: (context, _) => Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: TextField(
                controller: text,
                autofocus: true,
                onChanged: controller.updateQuery,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => controller.retry(),
                decoration: InputDecoration(
                  labelText: 'Benutzername',
                  hintText: 'Name oder @Benutzername',
                  prefixIcon: const Icon(Icons.search),
                  suffixIcon: text.text.isEmpty
                      ? null
                      : IconButton(
                          tooltip: 'Löschen',
                          icon: const Icon(Icons.clear),
                          onPressed: () {
                            text.clear();
                            controller.updateQuery('');
                          },
                        ),
                ),
              ),
            ),
            Expanded(
              child: controller.loading
                  ? const StateMessage(
                      icon: Icons.search,
                      title: 'Suche läuft',
                      message: 'Wir durchsuchen passende Routely-Profile.',
                      loading: true,
                    )
                  : controller.error != null
                  ? StateMessage(
                      icon: Icons.error_outline,
                      title: 'Suche fehlgeschlagen',
                      message: controller.error,
                      actionLabel: 'Erneut versuchen',
                      onAction: controller.retry,
                    )
                  : controller.query.trim().isEmpty
                  ? const StateMessage(
                      icon: Icons.person,
                      title: 'Wen suchst du?',
                      message: 'Gib einen Namen oder Benutzernamen ein.',
                    )
                  : controller.results.isEmpty
                  ? const StateMessage(
                      icon: Icons.person,
                      title: 'Keine Benutzer gefunden',
                      message:
                          'Probiere eine andere Schreibweise oder kürzere Suchbegriffe.',
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                      itemCount: controller.results.length,
                      itemBuilder: (context, index) {
                        final user = controller.results[index];
                        return Card(
                          margin: const EdgeInsets.only(bottom: 10),
                          child: ListTile(
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(18),
                            ),
                            contentPadding: const EdgeInsets.all(14),
                            leading: Avatar(user: user),
                            title: Text(
                              user.displayName ?? user.username,
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            subtitle: Text('@${user.username}'),
                            trailing: Icon(
                              Icons.chevron_right,
                              color: Theme.of(context).colorScheme.primary,
                            ),
                            onTap: () => widget.onUserTap(user.username),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    ),
  );
}
