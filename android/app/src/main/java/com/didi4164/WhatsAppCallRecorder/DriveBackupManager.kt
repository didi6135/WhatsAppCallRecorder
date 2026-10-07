package com.didi4164.WhatsAppCallRecorder

import android.accounts.Account
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object DriveBackupManager {
  const val SCOPE = "https://www.googleapis.com/auth/drive.file"
  private const val WORK = "codaki-drive-completed-recordings"
  internal val io = Executors.newSingleThreadExecutor { action -> Thread(action, "DriveBackupControl") }
  internal data class Selection(val account: DriveBackupClient.AccountInfo, val folder: DriveBackupClient.Folder,
    val managed: DriveManagedFolder? = null)

  /** Always asynchronous: RecordingStore.finish calls this while holding its own lock. */
  fun enqueueCompleted(context: Context, id: String) {
    val app = context.applicationContext
    io.execute {
      try {
        val file = RecordingStore.file(app, id, ".wav")
        if (file.isFile) DriveBackupQueue.enqueue(app, id, file.length())
        schedule(app)
      } catch (_: Exception) { /* Never turn a committed local recording into a failed save. */ }
    }
  }
  fun initialize(context: Context) {
    val app = context.applicationContext
    io.execute {
      try { if (DriveBackupQueue.config(app).enabled) { scan(app); schedule(app) } }
      catch (_: Exception) { /* The status journal exposes action-required state when unavailable. */ }
    }
  }
  private fun scan(context: Context) {
    // No queue lock is held across the store lock. Only finalized .wav/.json entries are returned.
    RecordingStore.list(context).forEach { item ->
      DriveBackupQueue.enqueue(context, item.getString("id"), item.optLong("fileSize"))
    }
  }
  fun setEnabled(context: Context, enabled: Boolean, expectedGeneration: Long? = null, beforeCommit: () -> Unit = {}) {
    if (enabled && !DriveBackupQueue.config(context).connected) throw DriveBackupFailure("NOT_CONNECTED")
    DriveBackupQueue.setEnabled(context, enabled, expectedGeneration, beforeCommit)
    cancel(context)
    if (enabled) { scan(context); schedule(context, replace = true) }
  }
  fun retry(context: Context) {
    DriveBackupQueue.retry(context); cancel(context)
    if (DriveBackupQueue.config(context).enabled) { scan(context); schedule(context, replace = true) }
  }
  fun cancel(context: Context) { DriveHttpTransport.cancelAll(); WorkManager.getInstance(context).cancelUniqueWork(WORK) }
  private fun schedule(context: Context, replace: Boolean = false) {
    val config = DriveBackupQueue.config(context)
    if (!config.enabled || !config.connected || DriveBackupQueue.blocked(context) || DriveBackupQueue.next(context, config) == null) return
    val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK).build()
    // APPEND closes the final-save/worker-completion lost-wakeup race. Extra empty work is harmless.
    WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
  }
  fun pickerRequest(config: DriveBackupQueue.Config, changeFolder: Boolean): AuthorizationRequest {
    val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
      .setOptOutIncludingGrantedScopes(true)
      .setPrompt(if (changeFolder) AuthorizationRequest.Prompt.CONSENT else AuthorizationRequest.Prompt.CONSENT or AuthorizationRequest.Prompt.SELECT_ACCOUNT)
      .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
      .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
      .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES, "application/vnd.google-apps.folder")
    if (changeFolder && config.connected) builder.setAccount(Account(config.email, "com.google"))
    return builder.build()
  }
  fun defaultFolderRequest(): AuthorizationRequest = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope(SCOPE))).setOptOutIncludingGrantedScopes(true)
    .setPrompt(AuthorizationRequest.Prompt.CONSENT or AuthorizationRequest.Prompt.SELECT_ACCOUNT).build()
  internal fun prepareSelection(result: com.google.android.gms.auth.api.identity.AuthorizationResult,
      original: DriveBackupQueue.Config, changeFolder: Boolean, client: DriveBackupClient, checkActive: () -> Unit): Selection {
    checkActive()
    val ids = result.tokenResponseParams?.getString("picked_file_ids")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    if (ids.size != 1 || !DriveBackupPolicy.validDriveId(ids[0])) throw DriveBackupFailure("FOLDER_UNAVAILABLE")
    val token = result.accessToken?.takeIf { it.isNotEmpty() } ?: throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
    val account = client.account(token)
    checkActive()
    val folder = client.folder(token, ids[0])
    if (changeFolder && original.connected && account.permissionId != original.accountId) throw DriveBackupFailure("ACCOUNT_CHANGED")
    checkActive()
    return Selection(account, folder)
  }
  internal fun prepareDefaultFolder(context: Context, result: com.google.android.gms.auth.api.identity.AuthorizationResult,
      original: DriveBackupQueue.Config, client: DriveBackupClient, checkActive: () -> Unit,
      reserveGate: (() -> DriveManagedFolder) -> DriveManagedFolder, commit: (Selection) -> Unit) {
    val token = result.accessToken?.takeIf { it.isNotEmpty() } ?: throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
    DriveDefaultFolderProvisioner(client,
      { accountId -> DriveBackupQueue.managed(context, accountId) },
      { account, id, marker -> reserveGate { DriveBackupQueue.reserveManaged(context, original.generation, account.permissionId, id, marker) } },
      { account, intent, folder -> commit(Selection(account, folder, intent)) }, checkActive).connect(token)
  }
  /** Caller holds its live action gate. Destination/confirmed receipt are committed atomically and remain paused. */
  internal fun commitSelection(context: Context, original: DriveBackupQueue.Config, selection: Selection) {
    if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
    val intent = selection.managed
    if (intent != null) {
      if (selection.account.permissionId != intent.accountId || selection.folder.id != intent.folderId) throw DriveBackupFailure("REMOTE_MISMATCH")
      DriveBackupQueue.selectManaged(context, original.generation, selection.account.email, intent, selection.folder.name)
    } else {
      DriveBackupQueue.select(context, selection.account.email, selection.account.permissionId, selection.folder.id, selection.folder.name, original.generation)
    }
  }
  fun disconnect(context: Context, expectedGeneration: Long? = null, beforeCommit: () -> Unit = {}) {
    if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
    val old = DriveBackupQueue.config(context)
    DriveBackupQueue.disconnect(context, expectedGeneration, beforeCommit); cancel(context)
    if (old.connected) {
      try {
        Tasks.await(Identity.getAuthorizationClient(context).revokeAccess(RevokeAccessRequest.builder()
          .setAccount(Account(old.email, "com.google")).setScopes(listOf(Scope(SCOPE))).build()), 20, TimeUnit.SECONDS)
      } catch (_: Exception) { /* Local disconnect is already durable even if Google is offline. */ }
    }
  }
  /** Worker authorization never launches an Activity/PendingIntent. */
  internal fun token(context: Context, config: DriveBackupQueue.Config): String {
    val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE)))
      .setAccount(Account(config.email, "com.google")).setOptOutIncludingGrantedScopes(true).build()
    val result = try { Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 30, TimeUnit.SECONDS) }
      catch (error: Exception) { throw authorizationFailure(error) }
    if (result.hasResolution()) throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
    return result.accessToken?.takeIf { it.isNotEmpty() } ?: throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
  }
  internal fun clearToken(context: Context, token: String) {
    try { Tasks.await(Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(token).build()), 10, TimeUnit.SECONDS) }
    catch (_: Exception) { }
  }
  internal fun authorizationFailure(error: Throwable): DriveBackupFailure {
    var cause = error
    while (cause.cause != null && cause !is ApiException) cause = cause.cause!!
    val status = (cause as? ApiException)?.statusCode
    return DriveBackupFailure(DriveGoogleStatus.code(status), retryable = DriveGoogleStatus.retryable(status), auth = DriveGoogleStatus.needsConsent(status))
  }
}
