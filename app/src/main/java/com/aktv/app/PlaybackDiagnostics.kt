package com.aktv.app

/** In-memory, bounded diagnostics. Never accepts a source URL, header, key, key ID or exception message. */
internal class PlaybackDiagnostics {
    enum class Provider { TG, JJTV, FROST, OUT }
    enum class Media { DASH, HLS, FILE }
    enum class Drm { NONE, CLEARKEY, WIDEVINE }
    enum class DrmStep { NOT_REQUESTED, CREATING, CREATED, SESSION_OPEN, KEY_REQUEST, KEY_RESPONSE, KEYS_LOADED }
    enum class Load { MANIFEST, INITIALIZATION, MEDIA, OTHER }
    enum class Family { HTTP, TLS, NETWORK, CREDENTIALS, DRM, DECODER, FORMAT, PLAYER }
    data class Problem(val family: Family, val code: Int? = null, val http: Int? = null,
                       val detail: String = "", val cryptoCode: Int? = null,
                       val reply: HttpReply? = null, val lookupStage: JjtvLookupStage? = null,
                       val credentialIssue: CredentialIssue? = null) {
        fun describe(): String = buildString {
            lookupStage?.let { append("JJTV ${it.label}: ") }
            append(family.name)
            credentialIssue?.let { append(if (it == CredentialIssue.EXPIRED) " supplied by provider have expired" else " supplied by provider are not valid yet") }
            http?.let { append(" $it") }
            code?.let { append(" / Media3 $it") }
            cryptoCode?.let { append(" / native $it") }
            // Only class names / platform diagnostic codes, never Throwable.message.
            if (detail.matches(Regex("[A-Za-z0-9_.$: >-]{1,180}"))) append(" / $detail")
            reply?.let { append(" [${it.describe()}]") }
        }
    }

    private val attempts = ArrayDeque<Attempt>()
    private var sequence = 0
    private var metadataProblem: Problem? = null
    private var resolvingProvider: Provider? = null
    private var resolvingProblem: Problem? = null
    @Synchronized fun resolving(provider: Provider) { resolvingProvider = provider; resolvingProblem = null }
    @Synchronized fun begin(provider: Provider, media: Media, drm: Drm, browserStyle: Boolean, compatible: Boolean): Attempt {
        resolvingProvider = null; resolvingProblem = null
        val attempt = Attempt(++sequence, provider, media, drm, browserStyle, compatible)
        if (attempts.size == 12) attempts.removeFirst()
        attempts.addLast(attempt)
        return attempt
    }
    @Synchronized fun metadataFailed(problem: Problem) { metadataProblem = problem; resolvingProblem = problem }
    @Synchronized fun hasClearKey() = attempts.any { it.drm == Drm.CLEARKEY }
    @Synchronized fun summary(): String = resolvingProvider?.let { provider ->
        resolvingProblem?.let { "$provider lookup: ${it.describe()}" } ?: "Resolving $provider configuration (no player yet)"
    } ?: attempts.lastOrNull()?.summary()
        ?: metadataProblem?.let { "Stream lookup: ${it.describe()}" } ?: "Waiting for stream configuration"
    @Synchronized fun report(): String = buildString {
        if (resolvingProvider != null) append("Current work: ${summary()}\n\nPrevious player attempt:\n")
        append(attempts.lastOrNull()?.report() ?: summary())
        val failures = attempts.mapNotNull { it.failureLine() }.takeLast(5)
        if (failures.isNotEmpty()) append("\n\nRecent attempts (original errors retained):\n" + failures.joinToString("\n"))
        metadataProblem?.let { append("\nMetadata lookup: ${it.describe()}") }
        append("\n\nNo stream URLs, cookies, key IDs or keys are included.")
    }

    class Attempt internal constructor(
        private val number: Int, private val provider: Provider, private val media: Media,
        val drm: Drm, private val browserStyle: Boolean, private val compatible: Boolean
    ) {
        private var step = DrmStep.NOT_REQUESTED
        private var drmSupported: Boolean? = null
        private var drmProblem: Problem? = null
        private var terminalProblem: Problem? = null
        private var lastLoadProblem: Problem? = null
        private var timedOut = false
        private var manifest = 0; private var initialization = 0; private var segments = 0
        private var loadedBytes = 0L
        private var clockSkewSeconds: Long? = null
        private var manifestReply: HttpReply? = null
        private var surface = false
        private var firstFrame = false; private var audioAdvancing = false
        private var state = "IDLE"
        private var video = "not selected"; private var audio = "not selected"
        private var videoDecoder = "not initialized"; private var audioDecoder = "not initialized"
        private var selectedVideo = 0; private var selectedAudio = 0
        private var unsupportedDrm = 0; private var unsupportedCodec = 0; private var exceedsCapabilities = 0
        private val schemes = linkedSetOf<String>()

        @Synchronized fun drmSupport(supported: Boolean) { drmSupported = supported }
        @Synchronized fun drmStep(value: DrmStep) { if (value.ordinal > step.ordinal) step = value }
        @Synchronized fun drmError(value: Problem) { drmProblem = value }
        @Synchronized fun failed(value: Problem) { terminalProblem = value }
        @Synchronized fun timeout() { timedOut = true }
        @Synchronized fun loadError(value: Problem) { lastLoadProblem = value }
        @Synchronized fun loaded(type: Load, bytes: Long, skewSeconds: Long? = null) {
            when (type) { Load.MANIFEST -> manifest++; Load.INITIALIZATION -> initialization++; Load.MEDIA -> segments++; else -> Unit }
            loadedBytes += bytes.coerceAtLeast(0)
            lastLoadProblem = null
            if (type == Load.MANIFEST && skewSeconds != null) clockSkewSeconds = skewSeconds
        }
        @Synchronized fun httpResponse(reply: HttpReply) { manifestReply = reply }
        @Synchronized fun surfaceReady() { surface = true }
        @Synchronized fun frameRendered() { firstFrame = true }
        @Synchronized fun audioStarted() { audioAdvancing = true }
        @Synchronized fun state(value: String) { state = value.takeIf { it in setOf("IDLE", "BUFFERING", "READY", "ENDED") } ?: "UNKNOWN" }
        @Synchronized fun decoder(isVideo: Boolean, name: String) {
            val safe = name.takeIf { it.matches(Regex("(?:OMX|c2|C2)\\.[A-Za-z0-9_.-]{1,90}")) } ?: "platform decoder"
            if (isVideo) videoDecoder = safe else audioDecoder = safe
        }
        @Synchronized fun tracks(videos: Int, audios: Int, drmUnsupported: Int, codecUnsupported: Int, exceedsReported: Int = 0) {
            selectedVideo = videos; selectedAudio = audios; unsupportedDrm = drmUnsupported; unsupportedCodec = codecUnsupported; exceedsCapabilities = exceedsReported
        }
        @Synchronized fun format(isVideo: Boolean, mime: String?, width: Int = 0, height: Int = 0, channels: Int = 0, scheme: String? = null) {
            val name = when (mime) {
                "video/avc" -> "H.264"; "video/hevc" -> "HEVC"; "video/x-vnd.on2.vp9" -> "VP9"; "video/av01" -> "AV1"
                "audio/mp4a-latm" -> "AAC"; "audio/ac3" -> "AC3"; "audio/eac3", "audio/eac3-joc" -> "EAC3"
                "audio/opus" -> "Opus"; "audio/mpeg" -> "MP3"; else -> "other codec"
            }
            if (scheme != null) schemes.add(scheme.takeIf { it in setOf("cenc", "cbcs", "cbc1", "cens") } ?: "unknown")
            if (isVideo) video = "$name ${width.coerceIn(0, 16384)}x${height.coerceIn(0, 16384)}"
            else audio = "$name ${channels.coerceIn(0, 32)}ch"
        }
        /** A missing picture is not necessarily a decoder fault. Downloads and keys must get a
         * chance to complete before a software-decoder retry can help. Explicit codec errors are
         * handled separately by the caller, even if initialization never completed.
         */
        @Synchronized fun canRetryDecoder(): Boolean = videoDecoder != "not initialized" ||
            (selectedVideo > 0 && (step == DrmStep.KEYS_LOADED || (drm == Drm.NONE && schemes.isEmpty())))

        @Synchronized fun summary(): String = when {
            terminalProblem != null -> terminalProblem!!.describe()
            firstFrame -> "Media3 reported a video frame; visible picture still requires device verification"
            drmSupported == false && step.ordinal < DrmStep.SESSION_OPEN.ordinal -> "Android reports no support for $drm"
            drmProblem != null && step != DrmStep.KEYS_LOADED -> "Android DRM: ${drmProblem!!.describe()}"
            lastLoadProblem != null -> "Media download: ${lastLoadProblem!!.describe()}"
            manifest == 0 && media != Media.FILE -> "No manifest download has completed"
            unsupportedDrm > 0 && selectedVideo == 0 && selectedAudio == 0 -> "No tracks selected: Android reports unsupported DRM"
            drm != Drm.NONE && step != DrmStep.KEYS_LOADED -> "Waiting for Android $drm keys (stage ${step.name})"
            unsupportedCodec > 0 && selectedVideo == 0 -> "No video selected: decoder format unsupported"
            exceedsCapabilities > 0 && selectedVideo == 0 -> "No video selected: codec exceeds reported device limits"
            !surface -> "The video surface is not ready"
            videoDecoder == "not initialized" -> "Keys/download stage passed; video decoder has not initialized"
            else -> "Video decoder initialized, but no first frame was reported"
        }
        @Synchronized fun failureLine(): String? = if (terminalProblem != null || timedOut)
            "#$number $provider $drm ${if (compatible) "compatible" else "standard"}: ${summary()}" else null
        @Synchronized fun report(): String = buildString {
            append("Attempt #$number: $provider / $media / configured DRM $drm\n")
            append("Mode: ${if (compatible) "compatible" else "standard"}; headers: ${if (browserStyle) "browser" else "declared"}\n")
            append("Finding: ${summary()}\n")
            append("State: $state${if (timedOut) " / deadline reached" else ""}\n")
            append("Loads complete: manifest $manifest, init $initialization, media $segments; $loadedBytes bytes\n")
            clockSkewSeconds?.let { append("Device clock minus HTTP Date: ${it}s (cached Date may differ)\n") }
            manifestReply?.let { append("Initial manifest response: ${it.describe()}\n") }
            append("DRM support: ${drmSupported?.toString() ?: "not queried"}; stage: ${step.name}\n")
            drmProblem?.let { append("DRM event (not necessarily terminal): ${it.describe()}\n") }
            append("Selected tracks: video $selectedVideo, audio $selectedAudio; unsupported DRM $unsupportedDrm, codec $unsupportedCodec; above reported limits $exceedsCapabilities\n")
            append("Encryption scheme: ${schemes.joinToString().ifEmpty { "not reported" }}\n")
            append("Video: $video; decoder $videoDecoder\nAudio: $audio; decoder $audioDecoder\n")
            append("Surface ready: $surface; first video frame: $firstFrame; audio position advanced: $audioAdvancing")
        }
    }
}
