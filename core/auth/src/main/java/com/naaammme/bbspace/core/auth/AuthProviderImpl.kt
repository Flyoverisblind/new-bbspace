package com.naaammme.bbspace.core.auth

import com.naaammme.bbspace.core.common.AuthProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthProviderImpl @Inject constructor(
    private val authStore: AuthStore
) : AuthProvider {
    override val mid: Long get() = authStore.mid
    override val accessToken: String get() = authStore.accessToken
    override val cookieHeader: String
        get() = authStore.getSavedCredential()
            ?.cookies
            ?.joinToString("; ") { "${it.name}=${it.value}" }
            .orEmpty()
    override val csrfToken: String get() = authStore.biliJct
}
