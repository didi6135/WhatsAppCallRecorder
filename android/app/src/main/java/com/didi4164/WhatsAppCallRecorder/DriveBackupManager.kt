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
  fun setEnabled(context: Context, enabled: Boolean) {
    if (enabled && !DriveBackupQueue.config(context).connected) throw DriveBackupFailure("NOT_CONNECTED")
    DriveBackupQueue.setEnabled(context, enabled)
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
  fun acceptSelection(context: Context, result: com.google.android.gms.auth.api.identity.AuthorizationResult,
      original: DriveBackupQueue.Config, changeFolder: Boolean) {
    if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
    if (DriveBackupQueue.config(context).generation != original.generation) throw DriveBackupFailure("ACCOUNT_CHANGED")
    val ids = result.tokenResponseParams?.getString("picked_file_ids")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    if (ids.size != 1 || !DriveBackupPolicy.validDriveId(ids[0])) throw DriveBackupFailure("FOLDER_UNAVAILABLE")
    val token = result.accessToken?.takeIf { it.isNotEmpty() } ?: throw DriveBackupFailure("AUTH_REQUIRED", auth = true)
    val client = DriveBackupClient(); val account = client.account(token); val folder = client.folder(token, ids[0])
    if (changeFolder && original.connected && account.permissionId != original.accountId) throw DriveBackupFailure("ACCOUNT_CHANGED")
    // A canceled picker never reaches here. Confirmed selection pauses until a fresh explicit opt-in.
    if (RecordingService.isBusy() || DriveBackupQueue.config(context).generation != original.generation) throw DriveBackupFailure("ACCOUNT_CHANGED")
    DriveBackupQueue.select(context, account.email, account.permissionId, folder.id, folder.name)
    cancel(context)
  }
  fun disconnect(context: Context) {
    if (RecordingService.isBusy()) throw DriveBackupFailure("RECORDING_BUSY")
    val old = DriveBackupQueue.config(context)
    DriveBackupQueue.disconnect(context); cancel(context)
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
