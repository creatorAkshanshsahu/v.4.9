package com.aktv.app

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Label
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Descriptor
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.extractor.mp4.PsshAtomUtil
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Match Shaka's explicit clearKeys override. A Widevine-only MPD (or an MPD without default_KID)
 * does not otherwise give Media3's ClearKey manager usable init data, even with a valid local licence.
 * Only the key IDs from THIS source's supplied, validated keys are used. No key is generated or fetched.
 */
internal class ClearKeyDashManifestParser(drm: JSONObject) : DashManifestParser() {
    private val common = schemeData(drm)

    companion object {
        /** Also used by the offline device self-test; no separate initialization recipe. */
        fun schemeData(drm: JSONObject): DrmInitData.SchemeData {
            require(ClearKey.license(drm) != null) { "An explicit valid ClearKey configuration is required" }
            val keyIds = ClearKey.pairs(drm).keys.map { encoded ->
                val bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded))
                UUID(bytes.long, bytes.long)
            }.toTypedArray()
            return DrmInitData.SchemeData(C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4,
                PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, keyIds, null))
        }
    }

    override fun buildRepresentation(
        representationInfo: RepresentationInfo,
        label: String?,
        labels: MutableList<Label>,
        extraDrmSchemeType: String?,
        extraDrmSchemeDatas: ArrayList<DrmInitData.SchemeData>,
        extraInbandEventStreams: ArrayList<Descriptor>
    ): Representation {
        val format = representationInfo.format
        val trackType = MimeTypes.getTrackType(format.sampleMimeType).takeUnless { it == C.TRACK_TYPE_UNKNOWN }
            ?: MimeTypes.getTrackType(format.containerMimeType)
        if (trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO) {
            // Do not mutate the AdaptationSet's shared list. Every audio/video representation gets
            // the same configured key-ID set, just as the website's explicit ClearKey mode does.
            representationInfo.drmSchemeDatas.clear()
            representationInfo.drmSchemeDatas.add(common)
            return super.buildRepresentation(representationInfo, label, labels, extraDrmSchemeType,
                arrayListOf(), extraInbandEventStreams)
        }
        return super.buildRepresentation(representationInfo, label, labels, extraDrmSchemeType,
            extraDrmSchemeDatas, extraInbandEventStreams)
    }
}
