package com.orgzly.android.repos

import android.net.Uri
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.orgzly.android.BookName
import com.orgzly.android.util.UriUtils
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.joda.time.DateTime
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class GoogleDriveRepo(
    private val repoId: Long,
    private val repoUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val refreshToken: String
) : SyncRepo {

    private val legacyFolderId = getLegacyFolderId(repoUrl)
    private val normalizedDirectoryPath = normalizeDirectoryPath(getDirectoryPath(repoUrl))

    private val repoUri = UriUtils.uriFromPath(SCHEME, normalizedDirectoryPath)

    private val client = OkHttpClient.Builder().build()

    private val folderId: String by lazy { legacyFolderId ?: resolveFolderId(normalizedDirectoryPath) }

    companion object {
        const val SCHEME = "gdrive"

        const val CLIENT_ID_PREF_KEY = "client_id"
        const val CLIENT_SECRET_PREF_KEY = "client_secret"
        const val REFRESH_TOKEN_PREF_KEY = "refresh_token"
        const val REDIRECT_URI_PREF_KEY = "redirect_uri"

        fun getInstance(repoWithProps: RepoWithProps): GoogleDriveRepo {
            val id = repoWithProps.repo.id
            val repoUrl = repoWithProps.repo.url
            val clientId = checkNotNull(repoWithProps.props[CLIENT_ID_PREF_KEY]) { "Client ID not found" }
            val clientSecret = checkNotNull(repoWithProps.props[CLIENT_SECRET_PREF_KEY]) { "Client Secret not found" }
            val refreshToken = checkNotNull(repoWithProps.props[REFRESH_TOKEN_PREF_KEY]) { "Refresh token not found" }
            return GoogleDriveRepo(id, repoUrl, clientId, clientSecret, refreshToken)
        }


        private fun getDirectoryPath(url: String): String {
            val uri = Uri.parse(url)
            return if (uri.scheme == SCHEME) {
                uri.path ?: "/"
            } else {
                url
            }
        }

        private fun getLegacyFolderId(url: String): String? {
            val uri = Uri.parse(url)
            if (uri.scheme != SCHEME) {
                return null
            }

            val authority = uri.authority
            val path = uri.path

            return if (!authority.isNullOrEmpty() && (path.isNullOrEmpty() || path == "/")) {
                authority
            } else {
                null
            }
        }

        fun normalizeDirectoryPath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty() || trimmed == "/") {
                return "/"
            }

            val withoutTrailingSlash = trimmed.trimEnd('/')
            return if (withoutTrailingSlash.startsWith("/")) {
                withoutTrailingSlash
            } else {
                "/$withoutTrailingSlash"
            }
        }

        fun exchangeAuthorizationCode(
            clientId: String,
            clientSecret: String,
            authorizationCode: String,
            redirectUri: String
        ): String {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", authorizationCode)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build()

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()

            OkHttpClient.Builder().build().newCall(request).execute().use {
                if (!it.isSuccessful) {
                    throw IOException("Failed to exchange OAuth authorization code: ${it.code} ${it.message}")
                }

                val responseBody = it.body?.string() ?: throw IOException("No OAuth response body")
                val json = JsonParser.parseString(responseBody).asJsonObject
                return json.get("refresh_token")?.asString
                    ?: throw IOException("No refresh_token in OAuth response; ensure offline access was requested")
            }
        }
    }

    override fun isConnectionRequired() = true

    override fun isAutoSyncSupported() = true

    override fun getUri(): Uri = repoUri

    override fun getBooks(): MutableList<VersionedRook> {
        return listFiles().mapNotNull { file ->
            val name = file.get("name")?.asString ?: return@mapNotNull null
            if (!BookName.isSupportedFormatFileName(name)) return@mapNotNull null
            fileToVersionedRook(file, name)
        }.toMutableList()
    }

    override fun retrieveBook(repoRelativePath: String?, destination: File?): VersionedRook {
        val safePath = requireNotNull(repoRelativePath)
        val file = findFileByName(safePath) ?: throw FileNotFoundException("File $safePath not found in Google Drive folder")
        val fileId = file.get("id").asString

        val response = executeAuthorized(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .get()
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to download $safePath: ${it.code} ${it.message}")
            }
            val bytes = it.body?.bytes() ?: throw IOException("No data returned for $safePath")
            destination?.writeBytes(bytes)
        }

        return fileToVersionedRook(file, safePath)
    }

    override fun openRepoFileInputStream(repoRelativePath: String): InputStream {
        val file = findFileByName(repoRelativePath) ?: throw FileNotFoundException("File $repoRelativePath not found")
        val fileId = file.get("id").asString

        val response = executeAuthorized(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .get()
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to open $repoRelativePath: ${it.code} ${it.message}")
            }
            return ByteArrayInputStream(it.body?.bytes() ?: ByteArray(0))
        }
    }

    override fun storeBook(file: File, repoRelativePath: String): VersionedRook {
        if (repoRelativePath.contains("/")) {
            throw IOException("Google Drive sync currently supports files at repository root only")
        }

        val existing = findFileByName(repoRelativePath)

        val updatedFile = if (existing != null) {
            val fileId = existing.get("id").asString
            val mediaType = "text/plain".toMediaType()

            val response = executeAuthorized(
                Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
                    .patch(file.asRequestBody(mediaType))
                    .build()
            )

            response.use {
                if (!it.isSuccessful) {
                    throw IOException("Failed to update $repoRelativePath: ${it.code} ${it.message}")
                }
            }

            findFileByName(repoRelativePath)
                ?: throw IOException("Updated file $repoRelativePath not found")

        } else {
            val metadata = JsonObject().apply {
                addProperty("name", repoRelativePath)
                add("parents", JsonParser.parseString("[\"$folderId\"]"))
            }

            val createResponse = executeAuthorized(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files")
                    .post(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                    .build()
            )

            val createdFileId = createResponse.use {
                if (!it.isSuccessful) {
                    throw IOException("Failed to create metadata for $repoRelativePath: ${it.code} ${it.message}")
                }

                val body = it.body?.string() ?: throw IOException("No create response body")
                JsonParser.parseString(body).asJsonObject.get("id")?.asString
                    ?: throw IOException("Created Google Drive file has no ID")
            }

            val uploadResponse = executeAuthorized(
                Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files/$createdFileId?uploadType=media")
                    .patch(file.asRequestBody("text/plain".toMediaType()))
                    .build()
            )

            uploadResponse.use {
                if (!it.isSuccessful) {
                    throw IOException("Failed to upload $repoRelativePath: ${it.code} ${it.message}")
                }
            }

            findFileByName(repoRelativePath)
                ?: throw IOException("Uploaded file $repoRelativePath not found")
        }

        return fileToVersionedRook(updatedFile, repoRelativePath)
    }

    override fun renameBook(oldFullUri: Uri, newName: String): VersionedRook {
        if (newName.contains("/")) {
            throw IOException("Google Drive sync currently supports files at repository root only")
        }

        val oldRelativePath = BookName.getRepoRelativePath(repoUri, oldFullUri)
        val oldBookName = BookName.fromRepoRelativePath(oldRelativePath)
        val newRelativePath = BookName.repoRelativePath(newName, oldBookName.format)

        val existing = findFileByName(oldRelativePath)
            ?: throw FileNotFoundException("File $oldRelativePath not found")

        val fileId = existing.get("id").asString
        val metadata = JsonObject().apply {
            addProperty("name", newRelativePath)
        }

        val response = executeAuthorized(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId")
                .patch(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to rename $oldRelativePath: ${it.code} ${it.message}")
            }
        }

        val updated = findFileByName(newRelativePath)
            ?: throw IOException("Renamed file $newRelativePath not found")

        return fileToVersionedRook(updated, newRelativePath)
    }

    override fun delete(uri: Uri) {
        val repoRelativePath = BookName.getRepoRelativePath(repoUri, uri)
        val existing = findFileByName(repoRelativePath)
            ?: throw FileNotFoundException("File $repoRelativePath not found")

        val fileId = existing.get("id").asString

        val response = executeAuthorized(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId")
                .delete()
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to delete $repoRelativePath: ${it.code} ${it.message}")
            }
        }
    }

    override fun toString(): String = repoUri.toString()

    private fun listFiles(): List<JsonObject> {
        val query = "'$folderId' in parents and trashed = false"

        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("pageSize", "1000")
            .build()

        val response = executeAuthorized(
            Request.Builder()
                .url(url)
                .get()
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to list Google Drive files: ${it.code} ${it.message}")
            }

            val body = it.body?.string() ?: throw IOException("No response body")
            val json = JsonParser.parseString(body).asJsonObject
            val files = json.getAsJsonArray("files") ?: return emptyList()
            return files.map { node -> node.asJsonObject }
        }
    }

    private fun findFileByName(fileName: String): JsonObject? {
        val escapedFileName = fileName.replace("'", "\\'")
        val query = "'$folderId' in parents and name = '$escapedFileName' and trashed = false"

        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("pageSize", "1")
            .build()

        val response = executeAuthorized(
            Request.Builder()
                .url(url)
                .get()
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to query Google Drive file $fileName: ${it.code} ${it.message}")
            }

            val body = it.body?.string() ?: return null
            val json = JsonParser.parseString(body).asJsonObject
            val files = json.getAsJsonArray("files") ?: return null
            if (files.size() == 0) return null
            return files[0].asJsonObject
        }
    }

    private fun fileToVersionedRook(file: JsonObject, repoRelativePath: String): VersionedRook {
        val fileId = file.get("id")?.asString ?: ""
        val modifiedTime = file.get("modifiedTime")?.asString
        val mtime = if (modifiedTime != null) DateTime.parse(modifiedTime).millis else 0L

        return VersionedRook(
            repoId,
            RepoType.GOOGLE_DRIVE,
            repoUri,
            repoUri.buildUpon().appendPath(repoRelativePath).build(),
            "$fileId:$modifiedTime",
            mtime
        )
    }

    private fun executeAuthorized(request: Request): okhttp3.Response {
        val token = getAccessToken()
        val authorizedRequest = request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        return client.newCall(authorizedRequest).execute()
    }

    private fun getAccessToken(): String {
        val requestBody = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to get Google OAuth token: ${it.code} ${it.message}")
            }

            val body = it.body?.string() ?: throw IOException("No token response body")
            val json = JsonParser.parseString(body).asJsonObject
            return json.get("access_token")?.asString
                ?: throw IOException("Missing access_token in OAuth response")
        }
    }

    private fun resolveFolderId(path: String): String {
        if (path == "/") {
            return "root"
        }

        var parentId = "root"

        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            parentId = findDirectoryByName(parentId, segment) ?: createDirectory(parentId, segment)
        }

        return parentId
    }

    private fun findDirectoryByName(parentId: String, directoryName: String): String? {
        val escapedDirectoryName = directoryName.replace("'", "\\'")
        val query =
            "'$parentId' in parents and name = '$escapedDirectoryName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"

        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()

        val response = executeAuthorized(
            Request.Builder()
                .url(url)
                .get()
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed to query Google Drive directory $directoryName: ${it.code} ${it.message}")
            }

            val body = it.body?.string() ?: return null
            val files = JsonParser.parseString(body).asJsonObject.getAsJsonArray("files") ?: return null
            if (files.size() == 0) {
                return null
            }

            return files[0].asJsonObject.get("id")?.asString
        }
    }

    private fun createDirectory(parentId: String, directoryName: String): String {
        val metadata = JsonObject().apply {
            addProperty("name", directoryName)
            addProperty("mimeType", "application/vnd.google-apps.folder")
            add("parents", JsonParser.parseString("[\"$parentId\"]"))
        }

        val response = executeAuthorized(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files")
                .post(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()
        )

        response.use {
            if (!it.isSuccessful) {
                throw IOException("Failed creating Google Drive directory $directoryName: ${it.code} ${it.message}")
            }

            val body = it.body?.string() ?: throw IOException("No response body while creating directory")
            return JsonParser.parseString(body).asJsonObject.get("id")?.asString
                ?: throw IOException("Created Google Drive directory has no ID")
        }
    }
}
