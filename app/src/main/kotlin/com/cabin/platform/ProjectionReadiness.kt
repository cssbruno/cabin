package com.cabin.platform

import com.cabin.R
import androidx.compose.ui.res.stringResource
import com.cabin.CabinManager
import com.cabin.protocol.PhoneType

enum class ProjectionUsbAccess { UNKNOWN, NOT_FOUND, PERMISSION_NEEDED, PERMITTED }

/** Counts recognized adapters only. Null means Android did not expose a reliable answer. */
data class ProjectionUsbReadiness(
    val access: ProjectionUsbAccess = ProjectionUsbAccess.UNKNOWN,
    val attachedAdapters: Int? = null,
)

enum class ProjectionOptionalCapability { NOT_REQUESTED, AVAILABLE, PERMISSION_NEEDED, BACKGROUND_RESTRICTED, UNKNOWN }

/** A one-shot, local observation: no phone identities, radio scores, or automatic recovery. */
data class ProjectionReadinessSnapshot(
    val state: CabinManager.State = CabinManager.State.DISCONNECTED,
    val sessionRequested: Boolean = false,
    val phoneConnectionAllowed: Boolean = true,
    val usb: ProjectionUsbReadiness = ProjectionUsbReadiness(),
    val adapterOpened: Boolean = false,
    val phoneType: PhoneType? = null,
    val microphone: ProjectionOptionalCapability = ProjectionOptionalCapability.UNKNOWN,
    val location: ProjectionOptionalCapability = ProjectionOptionalCapability.UNKNOWN,
)

data class ProjectionReadinessPresentation(
    val title: String,
    val nextStep: String,
    val canConnectPhone: Boolean,
    val usbDetail: String,
    val phoneDetail: String,
    val microphoneDetail: String,
    val locationDetail: String,
)

/** A permission failure for one device must not hide another adapter with granted access. */
internal fun projectionUsbReadiness(permissions: List<Boolean?>?): ProjectionUsbReadiness =
    ProjectionUsbReadiness(
        access =
            when {
                permissions == null -> ProjectionUsbAccess.UNKNOWN
                permissions.isEmpty() -> ProjectionUsbAccess.NOT_FOUND
                permissions.any { it == true } -> ProjectionUsbAccess.PERMITTED
                permissions.any { it == null } -> ProjectionUsbAccess.UNKNOWN
                else -> ProjectionUsbAccess.PERMISSION_NEEDED
            },
        attachedAdapters = permissions?.size,
    )

internal fun projectionOptionalCapability(
    requested: Boolean?,
    permissionGranted: Boolean?,
    backgroundCapabilitiesAvailable: Boolean,
): ProjectionOptionalCapability =
    when {
        requested == null -> ProjectionOptionalCapability.UNKNOWN
        !requested -> ProjectionOptionalCapability.NOT_REQUESTED
        permissionGranted == null -> ProjectionOptionalCapability.UNKNOWN
        !permissionGranted -> ProjectionOptionalCapability.PERMISSION_NEEDED
        !backgroundCapabilitiesAvailable -> ProjectionOptionalCapability.BACKGROUND_RESTRICTED
        else -> ProjectionOptionalCapability.AVAILABLE
    }

/** Stage copy is based on typed observations, never status-string matching or guessed faults. */
fun projectionReadinessPresentation(resources: android.content.res.Resources, snapshot: ProjectionReadinessSnapshot): ProjectionReadinessPresentation {
    val phoneName =
        when (snapshot.phoneType) {
            PhoneType.CARPLAY, PhoneType.CARPLAY_WIRELESS -> "CarPlay"
            PhoneType.ANDROID_AUTO -> "Android Auto"
            else -> resources.getString(R.string.readiness_projection)
        }
    val (title, nextStep) =
        when {
            !snapshot.phoneConnectionAllowed ->
                resources.getString(R.string.readiness_paused) to
                    resources.getString(R.string.readiness_paused_detail)
            !snapshot.sessionRequested ->
                resources.getString(R.string.readiness_idle) to
                    resources.getString(R.string.readiness_idle_detail)
            snapshot.state == CabinManager.State.STREAMING ->
                resources.getString(R.string.readiness_connected, phoneName) to
                    resources.getString(R.string.readiness_connected_detail)
            snapshot.state == CabinManager.State.DEVICE_CONNECTED ->
                resources.getString(R.string.readiness_phone_connected) to
                    resources.getString(R.string.readiness_starting_detail)
            snapshot.adapterOpened ->
                resources.getString(R.string.readiness_usb_opened) to
                    resources.getString(R.string.readiness_usb_opened_detail)
            snapshot.usb.access == ProjectionUsbAccess.NOT_FOUND ->
                resources.getString(R.string.readiness_no_adapter) to
                    resources.getString(R.string.readiness_no_adapter_detail)
            snapshot.usb.access == ProjectionUsbAccess.PERMISSION_NEEDED ->
                resources.getString(R.string.readiness_usb_approval) to
                    resources.getString(R.string.readiness_usb_approval_detail)
            snapshot.usb.access == ProjectionUsbAccess.PERMITTED ->
                resources.getString(R.string.readiness_usb_available) to
                    resources.getString(R.string.readiness_usb_available_detail)
            else ->
                resources.getString(R.string.readiness_usb_unknown) to
                    resources.getString(R.string.readiness_usb_unknown_detail)
        }
    val usbDetail =
        when {
            snapshot.adapterOpened -> resources.getString(R.string.readiness_usb_interface)
            snapshot.usb.access == ProjectionUsbAccess.NOT_FOUND -> resources.getString(R.string.readiness_usb_missing)
            snapshot.usb.access == ProjectionUsbAccess.PERMISSION_NEEDED -> resources.getString(R.string.readiness_usb_denied)
            snapshot.usb.access == ProjectionUsbAccess.PERMITTED -> resources.getString(R.string.readiness_usb_permitted)
            else -> resources.getString(R.string.readiness_usb_unchecked)
        } + if ((snapshot.usb.attachedAdapters ?: 0) > 1) " " + resources.getQuantityString(R.plurals.readiness_attached_adapters, snapshot.usb.attachedAdapters!!, snapshot.usb.attachedAdapters) else ""
    val phoneDetail =
        when {
            !snapshot.phoneConnectionAllowed -> resources.getString(R.string.readiness_phone_paused)
            !snapshot.sessionRequested -> resources.getString(R.string.readiness_phone_idle)
            snapshot.state == CabinManager.State.STREAMING -> resources.getString(R.string.readiness_phone_streaming, phoneName)
            snapshot.state == CabinManager.State.DEVICE_CONNECTED -> resources.getString(R.string.readiness_phone_starting)
            else -> resources.getString(R.string.readiness_phone_unconfirmed)
        }
    return ProjectionReadinessPresentation(
        title = title,
        nextStep = nextStep,
        canConnectPhone = !snapshot.sessionRequested || !snapshot.phoneConnectionAllowed || snapshot.state == CabinManager.State.DISCONNECTED,
        usbDetail = usbDetail,
        phoneDetail = phoneDetail,
        microphoneDetail = optionalCapabilityDetail(resources, snapshot.microphone, microphone = true),
        locationDetail = optionalCapabilityDetail(resources, snapshot.location, microphone = false),
    )
}

private fun optionalCapabilityDetail(
    resources: android.content.res.Resources,
    capability: ProjectionOptionalCapability,
    microphone: Boolean,
): String =
    when (capability) {
        ProjectionOptionalCapability.NOT_REQUESTED ->
            if (microphone) {
                resources.getString(R.string.readiness_mic_not_requested)
            } else {
                resources.getString(R.string.readiness_gps_not_requested)
            }
        ProjectionOptionalCapability.AVAILABLE ->
            if (microphone) {
                resources.getString(R.string.readiness_mic_available)
            } else {
                resources.getString(R.string.readiness_gps_available)
            }
        ProjectionOptionalCapability.PERMISSION_NEEDED ->
            if (microphone) {
                resources.getString(R.string.readiness_mic_permission)
            } else {
                resources.getString(R.string.readiness_gps_permission)
            }
        ProjectionOptionalCapability.BACKGROUND_RESTRICTED ->
            if (microphone) {
                resources.getString(R.string.readiness_mic_background)
            } else {
                resources.getString(R.string.readiness_gps_background)
            }
        ProjectionOptionalCapability.UNKNOWN -> resources.getString(R.string.readiness_optional_unknown)
    }
