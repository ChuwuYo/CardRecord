package com.shuaji.cards.data.backup

import android.net.Uri
import android.os.CancellationSignal
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** JVM 测试专用的 file:// 适配器；生产 APK 只保留 SAF content URI 实现。 */
internal object LocalBackupDirectoryAccess : BackupDirectoryAccess {
    override fun createBackup(
        parentUri: Uri,
        suggestedName: String,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory {
        cancellationSignal.throwIfCanceled()
        val parent = parentUri.toFile()
        if (!parent.isDirectory) throw FileNotFoundException()
        return LocalBackupDirectory(createUniqueDirectory(parent, suggestedName))
    }

    override fun openBackup(
        directoryUri: Uri,
        cancellationSignal: CancellationSignal,
    ): BackupDirectory {
        cancellationSignal.throwIfCanceled()
        val directory = directoryUri.toFile()
        if (!directory.isDirectory) throw FileNotFoundException()
        return LocalBackupDirectory(directory)
    }
}

private class LocalBackupDirectory(
    private val directory: File,
) : BackupDirectory {
    private val imagesDirectory = directory.resolve(BACKUP_IMAGES_DIRECTORY_NAME)

    override val displayName: String = directory.name
    override val lastModifiedMillis: Long?
        get() = directory.resolve(BACKUP_MANIFEST_FILE_NAME).takeIf(File::isFile)?.lastModified()

    override fun openManifestInput(cancellationSignal: CancellationSignal): InputStream? {
        cancellationSignal.throwIfCanceled()
        return directory.resolve(BACKUP_MANIFEST_FILE_NAME).takeIf(File::isFile)?.let(::FileInputStream)
    }

    override fun openManifestOutput(cancellationSignal: CancellationSignal): OutputStream {
        cancellationSignal.throwIfCanceled()
        return FileOutputStream(directory.resolve(BACKUP_MANIFEST_FILE_NAME), false)
    }

    override fun indexImageInputs(
        assetFileNames: Set<String>,
        cancellationSignal: CancellationSignal,
    ) {
        cancellationSignal.throwIfCanceled()
    }

    override fun openImageInput(
        assetFileName: String,
        cancellationSignal: CancellationSignal,
    ): InputStream? {
        cancellationSignal.throwIfCanceled()
        return imagesDirectory.resolve(assetFileName).takeIf(File::isFile)?.let(::FileInputStream)
    }

    override fun openImageOutput(
        assetFileName: String,
        cancellationSignal: CancellationSignal,
    ): OutputStream? {
        cancellationSignal.throwIfCanceled()
        if (!imagesDirectory.isDirectory && !imagesDirectory.mkdir()) return null
        return FileOutputStream(imagesDirectory.resolve(assetFileName), false)
    }

    override fun delete(): Boolean = directory.deleteRecursively()
}

private fun createUniqueDirectory(
    parent: File,
    suggestedName: String,
): File {
    repeat(100) { index ->
        val suffix = if (index == 0) "" else "_${index + 1}"
        val candidate = parent.resolve("$suggestedName$suffix")
        if (candidate.mkdir()) return candidate
        if (!candidate.exists()) throw FileNotFoundException()
    }
    throw FileNotFoundException()
}

private fun Uri.toFile(): File = File(path ?: throw FileNotFoundException())
