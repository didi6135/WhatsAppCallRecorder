package com.didi4164.WhatsAppCallRecorder

/** Durable create identity; contains neither a token nor a selected/upload-enabled destination. */
data class DriveManagedFolder(val accountId: String, val folderId: String, val marker: String, val confirmed: Boolean) {
  companion object {
    const val PROPERTY = "waRecoFolderMarker"
    fun validMarker(value: String) = value.matches(Regex("[a-f0-9]{32}"))
  }
  init {
    require(accountId.length in 1..256 && !accountId.contains('\n') && !accountId.contains('\r'))
    require(DriveBackupPolicy.validDriveId(folderId) && validMarker(marker))
  }
}
