## What this changes

<!-- One paragraph. If it ports a command, name the upstream command and the file it lives in. -->

## Checklist

- [ ] Behaviour matches WorldEdit 7.3.17 / FastAsyncWorldEdit (names, aliases, switches, argument
      order, messages, defaults). Anything that cannot match is documented in the README's platform
      limits instead of being silently different.
- [ ] No placeholder implementation: the command does the work or is not registered.
- [ ] `docs/` regenerated when the command surface changed (`./gradlew :core:genDocs`,
      `python3 scripts/generate_command_tables.py`), and `python3 scripts/flag_audit.py` reports no
      missing switch.
- [ ] Tests cover the new behaviour (`./gradlew :core:selfTest`), or the change explains why it
      cannot be tested head-less.
- [ ] Code comments explain decisions only; no commented-out code, no banner comments.
