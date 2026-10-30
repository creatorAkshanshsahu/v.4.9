package com.aktv.app

/** A second 403 after a fresh JJTV lookup is a provider refusal, not a reason to keep retrying it. */
internal object FreshRefusalPolicy {
    fun shouldMoveToAlternateProvider(provider: LiveProvider, httpCode: Int?, freshLookupTried: Boolean): Boolean =
        provider == LiveProvider.JJTV && httpCode == 403 && freshLookupTried
}
