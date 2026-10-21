# Android development and publication

- Keep `.private/`, `local.properties`, build outputs, logs, APKs and signing keys out of Git.
- Use the configured local Git repository. Commit completed changes after the relevant checks;
  do not leave delivered source changes uncommitted.
- The installed post-commit hook uploads the committed snapshot asynchronously. Check the sync
  status after committing. A successful local commit does not prove GitHub publication succeeded.
- Publication is handled through the fork's `sync/android` PR and required checks. Do not push a
  standalone Android tree over the fork's `main` branch or bypass failed checks.
- See `tools/maintenance/README.md` in the parent fork for source synchronization and upstream rules.
- Keep the existing application ID, version policy and signing identity when updating a personal
  installation. Signing credentials belong only in the ignored private configuration.
- Test real server writes only against an explicitly isolated QA runtime. Public CI uses example
  configuration, unit tests, compilation and lint; it does not perform production acceptance.

- Integrate completed task-branch work into the configured source branch before delivery. Record
  the local commit and confirm the managed GitHub PR result when reporting publication.
