package com.podvault.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.podvault.app.PodVaultApplication

/** Small helper so screens can grab a ViewModel wired to the Application's repositories
 *  without pulling in Hilt for this MVP. */
@Composable
inline fun <reified VM : ViewModel> podVaultViewModel(
    crossinline creator: (PodVaultApplication) -> VM
): VM {
    val app = LocalContext.current.applicationContext as PodVaultApplication
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = creator(app) as T
    })
}
