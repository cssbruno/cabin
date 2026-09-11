# App languages

Choose **Settings → Control → Language**. The app includes English, Portuguese,
Spanish, French, German and Italian, plus **Follow system**. Each language is
shown by its native name so the picker remains recognizable after switching.

All 793 base message entries (including plural forms) are covered in each
translation: launcher pages, modular dashboard, vehicle widgets, climate,
settings, setup, connection help, backup/restore, diagnostics and status text.
Translations ship inside the APK and work offline. Measurement units remain a
separate preference. Phone-projected CarPlay/Android Auto content and external
vendor apps use their own language settings.

Android 13+ also exposes these six choices in its per-app language settings.
Older head units store the choice locally and apply it when the Activity reloads.
Services and widgets resolve the selected language when creating new messages;
an already cached status message can remain in its previous language until the
next state update. Switching language does not deliberately disconnect projection.

Translations are developer-authored and have not received native-speaker review.
Resource tests enforce full key coverage, plural coverage and format arguments
for every locale. Runtime tests cover stored selection, Android's per-app API,
resource resolution and large-text language-picker controls.

To add a language, translate every message in `res/values`, add the native name
to `AppLanguage.names`, declare it in `res/xml/locales_config.xml`, and extend
the language contract and runtime tests. Do not fill missing entries with copied
English text to satisfy coverage tests.
