package com.orgzly.android.ui.repo.googledrive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.orgzly.R
import com.orgzly.android.repos.GoogleDriveRepo
import com.orgzly.android.repos.RepoType
import com.orgzly.android.ui.CommonActivity
import com.orgzly.android.ui.repo.RepoViewModel
import com.orgzly.android.ui.repo.RepoViewModelFactory
import com.orgzly.android.ui.showSnackbar
import com.orgzly.android.util.MiscUtils
import com.orgzly.android.util.UriUtils
import com.orgzly.databinding.ActivityRepoGoogleDriveBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class GoogleDriveRepoActivity : CommonActivity() {
    private lateinit var binding: ActivityRepoGoogleDriveBinding
    private lateinit var viewModel: RepoViewModel
    private var existingRefreshToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityRepoGoogleDriveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repoId = intent.getLongExtra(ARG_REPO_ID, 0)
        val factory = RepoViewModelFactory.getInstance(dataRepository, repoId)

        viewModel = ViewModelProvider(this, factory).get(RepoViewModel::class.java)

        viewModel.finishEvent.observeSingle(this, Observer {
            finish()
        })

        viewModel.alreadyExistsEvent.observeSingle(this, Observer {
            showSnackbar(R.string.repository_url_already_exists)
        })

        viewModel.errorEvent.observeSingle(this, Observer { error ->
            if (error != null) {
                showSnackbar((error.cause ?: error).localizedMessage)
            }
        })

        if (repoId != 0L) {
            viewModel.loadRepoProperties()?.let { repoWithProps ->
                val url = repoWithProps.repo.url
                val uri = Uri.parse(url)
                val path = if (uri.scheme == GoogleDriveRepo.SCHEME) {
                    uri.path
                } else {
                    url
                }
                binding.activityRepoGoogleDriveDirectoryPath.setText(
                    GoogleDriveRepo.normalizeDirectoryPath(path ?: "/")
                )
                binding.activityRepoGoogleDriveClientId.setText(repoWithProps.props[GoogleDriveRepo.CLIENT_ID_PREF_KEY])
                binding.activityRepoGoogleDriveClientSecret.setText(repoWithProps.props[GoogleDriveRepo.CLIENT_SECRET_PREF_KEY])
                binding.activityRepoGoogleDriveRedirectUri.setText(repoWithProps.props[GoogleDriveRepo.REDIRECT_URI_PREF_KEY])
                existingRefreshToken = repoWithProps.props[GoogleDriveRepo.REFRESH_TOKEN_PREF_KEY]
            }
        }

        MiscUtils.clearErrorOnTextChange(
            binding.activityRepoGoogleDriveDirectoryPath,
            binding.activityRepoGoogleDriveDirectoryPathLayout
        )
        MiscUtils.clearErrorOnTextChange(
            binding.activityRepoGoogleDriveClientId,
            binding.activityRepoGoogleDriveClientIdLayout
        )
        MiscUtils.clearErrorOnTextChange(
            binding.activityRepoGoogleDriveClientSecret,
            binding.activityRepoGoogleDriveClientSecretLayout
        )
        MiscUtils.clearErrorOnTextChange(
            binding.activityRepoGoogleDriveRedirectUri,
            binding.activityRepoGoogleDriveRedirectUriLayout
        )
        MiscUtils.clearErrorOnTextChange(
            binding.activityRepoGoogleDriveAuthorizationCode,
            binding.activityRepoGoogleDriveAuthorizationCodeLayout
        )

        binding.topToolbar.setNavigationOnClickListener {
            finish()
        }

        binding.fab.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun saveAndFinish() {
        val rawDirectoryPath = binding.activityRepoGoogleDriveDirectoryPath.text.toString().trim()
        val directoryPath = GoogleDriveRepo.normalizeDirectoryPath(rawDirectoryPath)
        val clientId = binding.activityRepoGoogleDriveClientId.text.toString().trim()
        val clientSecret = binding.activityRepoGoogleDriveClientSecret.text.toString().trim()
        val redirectUri = binding.activityRepoGoogleDriveRedirectUri.text.toString().trim()
        val authCode = binding.activityRepoGoogleDriveAuthorizationCode.text.toString().trim()

        binding.activityRepoGoogleDriveDirectoryPathLayout.error = if (rawDirectoryPath.isEmpty()) getString(R.string.can_not_be_empty) else null
        binding.activityRepoGoogleDriveClientIdLayout.error = if (clientId.isEmpty()) getString(R.string.can_not_be_empty) else null
        binding.activityRepoGoogleDriveClientSecretLayout.error = if (clientSecret.isEmpty()) getString(R.string.can_not_be_empty) else null
        binding.activityRepoGoogleDriveRedirectUriLayout.error = if (redirectUri.isEmpty()) getString(R.string.can_not_be_empty) else null
        binding.activityRepoGoogleDriveAuthorizationCodeLayout.error = null

        if (rawDirectoryPath.isEmpty() || clientId.isEmpty() || clientSecret.isEmpty() || redirectUri.isEmpty()) {
            return
        }

        lifecycleScope.launch {
            try {
                val refreshToken = resolveRefreshToken(clientId, clientSecret, redirectUri, authCode)
                val props = mapOf(
                    GoogleDriveRepo.CLIENT_ID_PREF_KEY to clientId,
                    GoogleDriveRepo.CLIENT_SECRET_PREF_KEY to clientSecret,
                    GoogleDriveRepo.REFRESH_TOKEN_PREF_KEY to refreshToken,
                    GoogleDriveRepo.REDIRECT_URI_PREF_KEY to redirectUri
                )

                val repoUrl = UriUtils.uriFromPath(GoogleDriveRepo.SCHEME, directoryPath).toString()
                viewModel.saveRepo(RepoType.GOOGLE_DRIVE, repoUrl, props)
            } catch (e: Exception) {
                showSnackbar((e.cause ?: e).localizedMessage)
            }
        }
    }

    private suspend fun resolveRefreshToken(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        authCode: String
    ): String {
        if (authCode.isNotEmpty()) {
            return withContext(Dispatchers.IO) {
                GoogleDriveRepo.exchangeAuthorizationCode(clientId, clientSecret, authCode, redirectUri)
            }
        }

        return existingRefreshToken ?: run {
            binding.activityRepoGoogleDriveAuthorizationCodeLayout.error = getString(R.string.can_not_be_empty)
            throw IOException(getString(R.string.google_drive_authorization_code_required))
        }
    }

    companion object {
        private const val ARG_REPO_ID = "repo_id"

        @JvmStatic
        @JvmOverloads
        fun start(activity: Activity, repoId: Long = 0) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setClass(activity, GoogleDriveRepoActivity::class.java)
                .putExtra(ARG_REPO_ID, repoId)

            activity.startActivity(intent)
        }
    }
}
