package com.didi4164.WhatsAppCallRecorder

import java.util.UUID

/** Exact-ID reconciliation. The caller supplies durable reservations and an atomic action/destination commit. */
class DriveDefaultFolderProvisioner(
  private val client: DriveBackupClient,
  private val find: (String) -> DriveManagedFolder?,
  private val reserve: (DriveBackupClient.AccountInfo, String, String) -> DriveManagedFolder,
  private val commit: (DriveBackupClient.AccountInfo, DriveManagedFolder, DriveBackupClient.Folder) -> Unit,
  private val checkActive: () -> Unit,
  private val newMarker: () -> String = { UUID.randomUUID().toString().replace("-", "") }
) {
  fun connect(token: String) {
    checkActive()
    val account = client.account(token)
    checkActive()
    val intent = find(account.permissionId) ?: run {
      val id = client.generateId(token)
      checkActive()
      reserve(account, id, newMarker()) // Must commit to durable storage BEFORE a folder POST.
    }
    if (intent.accountId != account.permissionId) throw DriveBackupFailure("ACCOUNT_CHANGED")
    checkActive()
    val existing = client.managedFolder(token, intent)
    val folder = existing ?: run {
      if (intent.confirmed) throw DriveBackupFailure("FOLDER_UNAVAILABLE")
      checkActive()
      client.createManagedFolder(token, intent)
      checkActive()
      client.managedFolder(token, intent) ?: throw DriveBackupFailure("NETWORK", retryable = true)
    }
    checkActive()
    commit(account, intent, folder)
  }
}
