package net.pantasystem.milktea.data.infrastructure.drive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.pantasystem.milktea.api.misskey.drive.DeleteFileDTO
import net.pantasystem.milktea.api.misskey.drive.ShowFile
import net.pantasystem.milktea.api.misskey.drive.toJsonObject
import net.pantasystem.milktea.common.runCancellableCatching
import net.pantasystem.milktea.common.throwIfHasError
import net.pantasystem.milktea.common_android.hilt.IODispatcher
import net.pantasystem.milktea.data.api.misskey.MisskeyAPIProvider
import net.pantasystem.milktea.data.converters.FilePropertyDTOEntityConverter
import net.pantasystem.milktea.model.account.GetAccount
import net.pantasystem.milktea.model.drive.*
import net.pantasystem.milktea.model.file.AppFile
import javax.inject.Inject


class DriveFileRepositoryImpl @Inject constructor(
    val getAccount: GetAccount,
    private val misskeyAPIProvider: MisskeyAPIProvider,
    private val driveFileDataSource: FilePropertyDataSource,
    private val driveFileUploaderProvider: FileUploaderProvider,
    private val filePropertyDTOEntityConverter: FilePropertyDTOEntityConverter,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher
) : DriveFileRepository {
    override suspend fun find(id: FileProperty.Id): FileProperty {
        return withContext(ioDispatcher) {
            val file = driveFileDataSource.find(id).getOrNull()
            if (file != null) {
                return@withContext file
            }
            val account = getAccount.get(id.accountId)
            val api = misskeyAPIProvider.get(account.normalizedInstanceUri)
            val response = api.showFile(ShowFile(fileId = id.fileId, i = account.token))
                .throwIfHasError()
            val fp = filePropertyDTOEntityConverter.convert(response.body()!!, account)
            driveFileDataSource.add(fp)
            return@withContext fp
        }
    }

    override suspend fun toggleNsfw(id: FileProperty.Id) {
        withContext(ioDispatcher) {
            val account = getAccount.get(id.accountId)
            val api = misskeyAPIProvider.get(account.normalizedInstanceUri)
            val fileProperty = find(id)
            val result = api.updateFile(
                UpdateFileProperty(
                    fileProperty.id,
                    isSensitive = ValueType.Some(!fileProperty.isSensitive)
                ).toJsonObject(account.token)
            ).throwIfHasError()

            driveFileDataSource.add(
                filePropertyDTOEntityConverter.convert(
                    result.body()!!,
                    account
                )
            )
        }
    }

    override suspend fun create(accountId: Long, file: AppFile.Local): Result<FileProperty> {
        return runCancellableCatching {
            withContext(ioDispatcher) {
                val property = driveFileUploaderProvider.get(getAccount.get(accountId))
                    .upload(UploadSource.LocalFile(file), true)
                driveFileDataSource.add(property).getOrThrow()
                driveFileDataSource.find(property.id).getOrThrow()
            }

        }
    }

    override suspend fun delete(id: FileProperty.Id): Result<Unit> {
        return runCancellableCatching {
            withContext(ioDispatcher) {
                val account = getAccount.get(id.accountId)
                val property = find(id)
                misskeyAPIProvider.get(account).deleteFile(
                    DeleteFileDTO(i = account.token, fileId = id.fileId)
                )
                driveFileDataSource.remove(property)
            }
        }
    }

    override suspend fun update(updateFileProperty: UpdateFileProperty): Result<FileProperty> {
        return runCancellableCatching {
            withContext(ioDispatcher) {
                val res =
                    misskeyAPIProvider.get(getAccount.get(updateFileProperty.fileId.accountId))
                        .updateFile(
                            updateFileProperty.toJsonObject(
                                getAccount.get(updateFileProperty.fileId.accountId).token,
                            )
                        ).throwIfHasError()
                        .body()!!
                filePropertyDTOEntityConverter.convert(
                    res,
                    getAccount.get(updateFileProperty.fileId.accountId)
                ).also {
                    driveFileDataSource.add(it)
                }
            }

        }
    }
}