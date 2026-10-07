# Default Google Drive folder connection

Users can choose their own Google account and authorize creation of a `wa-reco`
folder in their My Drive. The existing Google-hosted picker remains available
for choosing a different existing folder. Both routes use only `drive.file` and
the Android AuthorizationClient; neither needs a backend or a shared account.
Completing connection leaves automatic backup off. Uploading existing and future
completed recordings requires the separate backup confirmation.

## Creation and retry contract

- The native Drive account response supplies the stable permission ID. A generated
  folder ID and random private app-property marker are saved in the existing
  AtomicFile journal before any folder-create request. No OAuth token is saved.
  The writer explicitly syncs file bytes, verifies the exact committed bytes after
  AtomicFile finishes, and syncs the parent directory before returning a reservation;
  a silently unsuccessful framework finish cannot authorize a remote create.
- A retry reads that same folder ID first. A usable receipt must identify the
  expected folder, exact app marker, user ownership, My Drive storage, non-trashed
  state, and permission to add children. Names are not used as identities, so
  renaming the folder does not break the binding.
- Reuse is based on this installation's preserved journal. A new installation or
  cleared app data does not adopt a folder solely by its name and can create a
  separate `wa-reco` folder. Users can choose an existing folder through the
  retained picker instead.
- An unconfirmed reservation may be created with its original ID after a 404.
  A successful POST or a 409 conflict still requires an exact verified GET.
  Network loss keeps the reservation; subsequent attempts do not allocate another
  folder ID. A previously confirmed folder becoming unavailable requires repair
  rather than automatic replacement.
- The confirmed reservation and selected paused destination are one
  generation-conditional journal commit. Disconnect retains managed folder
  identities and existing upload receipts for later reconciliation. It does not
  delete any remote folder or local recording.
- Cancellation/module invalidation cancels the action-owned transport. Activity
  request codes are not reused within the process; mismatched/stale results and
  duplicate callbacks cannot complete another action. Recording busy state,
  resumed app lifecycle, current account configuration generation, and action
  liveness are checked at operation boundaries and before destination commit.
  A cancellation racing an already transmitted POST may leave the empty folder
  remotely created; its durable identity is retained and it is not selected.

The optional bridge field `folderSource` is derived as `managed` only when the
current account/folder matches a confirmed reservation; other connected
destinations are `selected`. Older bridge results without the field continue
to use the existing-folder authorization route. Markers and account-ledger data
are not exposed through the bridge.

Google supports generated IDs for folder creation and prevents duplicate
creation on same-ID retries. See [Create and manage files](https://developers.google.com/workspace/drive/api/guides/create-file).

## Verification

`tools/drive-backup-tests/run-host-tests.ps1` uses already cached dependencies and
executes the production journal, provisioning, REST client, and transport against
synthetic data. Tests cover durable reservation failure/recovery, account
isolation, exact receipts, lost create acknowledgement, 409 reconciliation,
cancellation after transmission, confirmed-folder disappearance, paused commits,
and preservation of existing destination/receipts. The host Context, AtomicFile,
language, and directory-syscall classes are explicit shims; they do not prove Android lifecycle or
OAuth behavior. Android unit tests compile the actual native bridge and existing
Drive result/diagnostic contracts.

Real Google account consent, folder creation/reuse, token renewal, Android process
interruption, and an actual backup receipt remain separate device/provider tests.
No private recording is needed for a synthetic provider acceptance test.
