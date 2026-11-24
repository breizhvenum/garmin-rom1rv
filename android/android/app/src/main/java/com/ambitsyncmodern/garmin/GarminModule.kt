package com.ambitsyncmodern.garmin

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import com.facebook.react.bridge.*
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileInputStream
import me.jahnen.libaums.core.fs.UsbFileOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/*
 * v2.3 beta — Garmin USB Mass Storage support. See GARMIN_USB_IMPORT_SPEC.md for the full
 * research trail (real hardware: André's eTrex 30, 2026-08-07).
 *
 * Garmin devices work completely differently from the Ambit3 (plain files on a FAT
 * filesystem, not an NSP flash protocol), so this is a wholly separate module, not an
 * extension of AmbitUsbModule.kt — matches this project's "one file per format" convention.
 *
 * Uses libaums (me.jahnen.libaums:core, Apache 2.0) for userspace USB Mass Storage access —
 * a real, maintained library, not hand-rolled SCSI/FAT. Confirmed necessary rather than
 * relying on OS auto-mount: stock Android has no built-in document provider for arbitrary
 * USB-OTG mass storage devices (this is exactly the gap libaums exists to fill), unlike the
 * desktop-Linux automount (`udisks2`) used during initial research, which doesn't generalize
 * to Android.
 */

private const val GARMIN_VID = 2334 // 0x091E, confirmed on real hardware — see device_filter.xml
private const val ACTION_GARMIN_USB_PERMISSION = "com.ambitsyncmodern.GARMIN_USB_PERMISSION"
private const val GARMIN_SAF_PREFS = "garmin_saf"
private const val GARMIN_SAF_TREE = "treeUri"
private const val GARMIN_SAF_REQUEST_TREE = 0x6245

data class GarminVolumeInfo(
    val volumeIndex: Int,
    val hasGarminDeviceXml: Boolean,
    val model: String?,
    val firmwareVersion: String?,
    val partNumber: String?,
    val activityPath: String?, // resolved from GarminDevice.xml's GPSData/OutputFromUnit entry
)

class GarminModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    private val executor = Executors.newSingleThreadExecutor()
    private var pendingConnectPromise: Promise? = null

    // Kept open between calls so listActivityFiles()/readActivityFile() can reuse the same
    // mounted volumes without re-scanning USB every time. Closed explicitly by disconnect().
    private var openDevices: List<UsbMassStorageDevice> = emptyList()
    private var volumeInfos: List<GarminVolumeInfo> = emptyList()
    // Some phones mount the Forerunner's FAT volume themselves. In that case the
    // kernel owns the block device and libaums cannot initialise it; SAF is the
    // supported way to access that already-mounted volume.
    private var safRoot: DocumentFile? = null
    private var pendingSafPromise: Promise? = null

    init { reactContext.addActivityEventListener(this) }

    override fun getName() = "GarminModule"

    // Required by NativeEventEmitter on the JS side (without them React Native logs a warning at
    // every start). Events are sent through RCTDeviceEventEmitter, so there's nothing to track.
    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}

    private fun savedSafRoot(): DocumentFile? {
        val raw = reactContext.getSharedPreferences(GARMIN_SAF_PREFS, Context.MODE_PRIVATE)
            .getString(GARMIN_SAF_TREE, null) ?: return null
        val uri = android.net.Uri.parse(raw)
        val granted = reactContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (granted) DocumentFile.fromTreeUri(reactContext, uri) else null
    }

    private fun clearSavedSafRoot() {
        reactContext.getSharedPreferences(GARMIN_SAF_PREFS, Context.MODE_PRIVATE).edit()
            .remove(GARMIN_SAF_TREE).apply()
    }

    private fun safActivityDirectory(root: DocumentFile?): DocumentFile? {
        // Android's folder picker may hand us either the storage root (which
        // contains GARMIN), or GARMIN itself when the user opens that folder
        // before tapping "Use this folder". Both are valid Forerunner roots.
        if (root?.name?.equals("Garmin", ignoreCase = true) == true) {
            return findDocumentChild(root, "Activity")
        }
        val garmin = root?.let { findDocumentChild(it, "Garmin") } ?: return null
        return findDocumentChild(garmin, "Activity")
    }

    private fun useSafRoot(root: DocumentFile, promise: Promise) {
        if (safActivityDirectory(root) == null) {
            promise.reject("NOT_GARMIN", "That folder is not a Garmin watch storage root (GARMIN/Activity is missing).")
            return
        }
        safRoot = root
        openDevices = emptyList()
        volumeInfos = listOf(GarminVolumeInfo(0, true, "Garmin", null, null, "Garmin/Activity"))
        mainResolve(promise, buildScanResult(volumeInfos))
    }

    private fun requestSafRoot(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) { mainReject(promise, "NO_ACTIVITY", "App not in foreground"); return }
        if (pendingSafPromise != null) { mainReject(promise, "BUSY", "A Garmin connection is already in progress"); return }
        pendingSafPromise = promise
        UiThreadUtil.runOnUiThread {
            try { activity.startActivityForResult(safTreeIntent(), GARMIN_SAF_REQUEST_TREE) }
            catch (e: Exception) {
                pendingSafPromise = null
                mainReject(promise, "PICK_FAILED", e.message ?: "Could not open storage picker")
            }
        }
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != GARMIN_SAF_REQUEST_TREE) return
        val promise = pendingSafPromise ?: return
        pendingSafPromise = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) { mainReject(promise, "CANCELLED", "No folder selected"); return }
        try {
            reactContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            reactContext.getSharedPreferences(GARMIN_SAF_PREFS, Context.MODE_PRIVATE).edit()
                .putString(GARMIN_SAF_TREE, uri.toString()).apply()
            val root = DocumentFile.fromTreeUri(reactContext, uri)
                ?: throw IllegalStateException("Could not open selected folder")
            useSafRoot(root, promise)
        } catch (e: Exception) { mainReject(promise, "GRANT_FAILED", e.message ?: "Could not use selected folder") }
    }

    override fun onNewIntent(intent: Intent) {}

    /** Returns the automounted Garmin volume when Android exposes one. This lets a
     * first Forerunner connection open the right folder picker immediately instead
     * of waiting for a raw libaums claim that the OS has already made impossible. */
    private fun autoMountedGarminVolume(): android.os.storage.StorageVolume? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val manager = reactContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.firstOrNull { volume ->
                !volume.isPrimary && volume.state == Environment.MEDIA_MOUNTED &&
                    (volume.getDescription(reactContext).contains("garmin", true) ||
                        volume.getDescription(reactContext).contains("forerunner", true))
            }
        } catch (_: Exception) { null }
    }

    private fun safTreeIntent(): Intent =
        try { autoMountedGarminVolume()?.createOpenDocumentTreeIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) }
        catch (_: Exception) { Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_GARMIN_USB_PERMISSION) return
            try { reactContext.unregisterReceiver(this) } catch (_: Exception) {}
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val promise = pendingConnectPromise ?: return
            pendingConnectPromise = null
            if (!granted) {
                promise.reject("USB_PERMISSION_DENIED", "USB permission denied by user")
                return
            }
            scanVolumes(promise)
        }
    }

    // ─── connect() ───────────────────────────────────────────────────────────
    // Finds a Garmin mass-storage device, requests permission if needed, mounts
    // every volume it exposes (internal memory + SD card show up as separate
    // volumes — see GARMIN_USB_IMPORT_SPEC.md's multi-volume note), and parses
    // each one's GarminDevice.xml if present.
    @ReactMethod
    fun connect(promise: Promise) {
        val usbManager = reactContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val garmin: UsbDevice? = usbManager.deviceList.values.find { it.vendorId == GARMIN_VID }
        if (garmin == null) {
            promise.reject("GARMIN_NOT_FOUND", "No Garmin device detected. Check the USB cable and that the device is powered on. [USB seen: ${describeUsbDevices(reactContext)}]")
            return
        }

        // A previous one-time folder grant makes auto-mounted Forerunners work
        // without another picker or a raw USB claim.
        savedSafRoot()?.let { root ->
            if (safActivityDirectory(root) != null) { useSafRoot(root, promise); return }
            // Do not strand the user with a remembered Downloads/Activity folder.
            // Ask again immediately, rather than waiting for the raw USB fallback.
            clearSavedSafRoot()
            requestSafRoot(promise)
            return
        }

        // On devices that auto-mount the Forerunner, prefer SAF immediately.
        if (autoMountedGarminVolume() != null) { requestSafRoot(promise); return }

        if (usbManager.hasPermission(garmin)) {
            scanVolumes(promise)
            return
        }

        pendingConnectPromise = promise
        
        // CORRECTION ANDROID 14 : Utilisation de FLAG_IMMUTABLE au lieu de FLAG_MUTABLE
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val permissionIntent = PendingIntent.getBroadcast(
            reactContext, 0, Intent(ACTION_GARMIN_USB_PERMISSION), flags
        )
        
        val filter = IntentFilter(ACTION_GARMIN_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reactContext.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            reactContext.registerReceiver(usbPermissionReceiver, filter)
        }
        usbManager.requestPermission(garmin, permissionIntent)
    }

    // Real-world reference (sportablet.com's Garmin-connection tutorial, via the
    // decompiled apps' own author): a Garmin handheld can take up to ~40 seconds
    // to finish mounting after the USB link comes up — Android itself is busy
    // scanning the new filesystem for media in that window. A single immediate
    // enumeration attempt, as this originally did, fails on a device that just
    // hasn't finished mounting yet, indistinguishable from "not a mass storage
    // device at all". Retry instead of failing fast.
    private val MOUNT_RETRY_INTERVAL_MS = 3000L
    private val MOUNT_RETRY_BUDGET_MS = 45000L

    private fun scanVolumes(promise: Promise) {
        executor.execute {
            closeAllDevices() // in case of a stale previous connect()
            val deadline = System.currentTimeMillis() + MOUNT_RETRY_BUDGET_MS
            var attempt = 0
            while (true) {
                attempt++
                val outcome = tryScanVolumesOnce()
                when (outcome) {
                    is ScanOutcome.Success -> {
                        openDevices = outcome.opened
                        volumeInfos = outcome.infos
                        mainResolve(promise, buildScanResult(outcome.infos))
                        return@execute
                    }
                    is ScanOutcome.NotFoundYet -> {
                        if (System.currentTimeMillis() >= deadline) {
                            // The Forerunner 245 is often auto-mounted by Android. Offer
                            // the storage-root grant instead of reporting a misleading USB failure.
                            requestSafRoot(promise)
                            return@execute
                        }
                        emitMountWaiting(attempt, (deadline - System.currentTimeMillis()) / 1000)
                        Thread.sleep(MOUNT_RETRY_INTERVAL_MS)
                    }
                    is ScanOutcome.HardFailure -> {
                        mainReject(promise, outcome.code, outcome.message)
                        return@execute
                    }
                }
            }
        }
    }

    private sealed class ScanOutcome {
        data class Success(val opened: List<UsbMassStorageDevice>, val infos: List<GarminVolumeInfo>) : ScanOutcome()
        data class NotFoundYet(val code: String, val message: String) : ScanOutcome()
        data class HardFailure(val code: String, val message: String) : ScanOutcome()
    }

    private fun tryScanVolumesOnce(): ScanOutcome {
        return try {
            val allDevices = UsbMassStorageDevice.getMassStorageDevices(reactContext)
            val garminDevices = allDevices.filter { it.usbDevice.vendorId == GARMIN_VID }
            if (garminDevices.isEmpty()) {
                return ScanOutcome.NotFoundYet("GARMIN_NOT_FOUND",
                    "No Garmin mass-storage device found yet — still waiting for it to finish mounting")
            }

            val opened = mutableListOf<UsbMassStorageDevice>()
            val infos = mutableListOf<GarminVolumeInfo>()
            var volumeIndex = 0
            val notes = mutableListOf<String>()   // what happened to each device, shown in the error
            notes.add("${garminDevices.size} mass-storage device(s)")

            for (device in garminDevices) {
                try {
                    device.init()
                } catch (e: Exception) {
                    notes.add("init failed: ${e.javaClass.simpleName} ${e.message}")
                    continue // this LUN/partition failed to init — skip it, don't fail the whole connect
                }
                notes.add("init ok, ${device.partitions.size} partition(s)")
                opened.add(device)
                for (partition in device.partitions) {
                    val root = partition.fileSystem.rootDirectory
                    val garminDir = findChildCaseInsensitive(root, "Garmin")
                    val descriptor = garminDir?.let { findChildCaseInsensitive(it, "GarminDevice.xml") }
                    val parsed = descriptor?.let { parseGarminDeviceXml(readUsbFileFully(it)) }
                    infos.add(
                        GarminVolumeInfo(
                            volumeIndex = volumeIndex,
                            hasGarminDeviceXml = descriptor != null,
                            model = parsed?.model,
                            firmwareVersion = parsed?.firmwareVersion,
                            partNumber = parsed?.partNumber,
                            activityPath = parsed?.activityPath,
                        )
                    )
                    volumeIndex++
                }
            }

            if (infos.isEmpty()) {
                closeDevicesList(opened)
                return ScanOutcome.NotFoundYet("GARMIN_NO_VOLUMES",
                    "Garmin device detected but no readable volumes yet — still waiting for it to finish mounting [" + notes.joinToString(" | ") + "]")
            }

            ScanOutcome.Success(opened, infos)
        } catch (e: Exception) {
            ScanOutcome.HardFailure("GARMIN_SCAN_FAILED", e.message ?: "Unknown error scanning Garmin volumes")
        }
    }

    private fun emitMountWaiting(attempt: Int, secondsLeft: Long) {
        val params = Arguments.createMap().apply {
            putInt("attempt", attempt)
            putInt("secondsLeft", secondsLeft.toInt())
        }
        reactContext
            .getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("GarminMountWaiting", params)
    }

    private fun buildScanResult(infos: List<GarminVolumeInfo>): WritableMap {
        val result = Arguments.createMap()
        val volumesArray = Arguments.createArray()
        for (info in infos) {
            val m = Arguments.createMap()
            m.putInt("volumeIndex", info.volumeIndex)
            m.putBoolean("hasGarminDeviceXml", info.hasGarminDeviceXml)
            m.putString("model", info.model)
            m.putString("firmwareVersion", info.firmwareVersion)
            m.putString("partNumber", info.partNumber)
            m.putString("activityPath", info.activityPath)
            volumesArray.pushMap(m)
        }
        result.putArray("volumes", volumesArray)
        val hasSdCard = infos.any { !it.hasGarminDeviceXml } && infos.any { it.hasGarminDeviceXml }
        result.putBoolean("hasSdCard", hasSdCard)
        return result
    }

    // ─── listActivityFiles() ─────────────────────────────────────────────────
    @ReactMethod
    fun listActivityFiles(volumeIndex: Int, promise: Promise) {
        executor.execute {
            try {
                val dir = resolveVolumeActivityDir(volumeIndex)
                if (dir == null) {
                    mainReject(promise, "GARMIN_PATH_NOT_FOUND", "Activity path not found for this volume")
                    return@execute
                }
                val names = Arguments.createArray()
                for (f in dir.listFiles()) {
                    if (!f.isDirectory) names.pushString(f.name)
                }
                mainResolve(promise, names)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_LIST_FAILED", e.message ?: "Unknown error listing activity files")
            }
        }
    }

    // ─── readActivityFile() ──────────────────────────────────────────────────
    @ReactMethod
    fun readActivityFile(volumeIndex: Int, fileName: String, promise: Promise) {
        executor.execute {
            try {
                val dir = resolveVolumeActivityDir(volumeIndex)
                val file = dir?.let { findChildCaseInsensitive(it, fileName) }
                if (file == null) {
                    mainReject(promise, "GARMIN_FILE_NOT_FOUND", "$fileName not found")
                    return@execute
                }
                val text = String(readUsbFileFully(file), Charsets.UTF_8)
                mainResolve(promise, text)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_READ_FAILED", e.message ?: "Unknown error reading $fileName")
            }
        }
    }

    // ─── listGpxDirFiles() / readGpxDirFile() ────────────────────────────────
    @ReactMethod
    fun listGpxDirFiles(volumeIndex: Int, promise: Promise) {
        executor.execute {
            try {
                val dir = resolveVolumeGpxDir(volumeIndex)
                if (dir == null) {
                    mainReject(promise, "GARMIN_PATH_NOT_FOUND", "No Garmin/GPX folder on this volume")
                    return@execute
                }
                val names = Arguments.createArray()
                for (f in dir.listFiles()) {
                    if (!f.isDirectory && f.name.lowercase().endsWith(".gpx")) names.pushString(f.name)
                }
                mainResolve(promise, names)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_LIST_FAILED", e.message ?: "Unknown error listing Garmin/GPX")
            }
        }
    }

    @ReactMethod
    fun readGpxDirFile(volumeIndex: Int, fileName: String, promise: Promise) {
        executor.execute {
            try {
                val dir = resolveVolumeGpxDir(volumeIndex)
                val file = dir?.let { findChildCaseInsensitive(it, fileName) }
                if (file == null) {
                    mainReject(promise, "GARMIN_FILE_NOT_FOUND", "$fileName not found")
                    return@execute
                }
                val text = String(readUsbFileFully(file), Charsets.UTF_8)
                mainResolve(promise, text)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_READ_FAILED", e.message ?: "Unknown error reading $fileName")
            }
        }
    }

    // ─── writeGpxToSdCard() ──────────────────────────────────────────────────
    @ReactMethod
    fun writeGpxToSdCard(volumeIndex: Int, fileName: String, gpxContent: String, promise: Promise) {
        executor.execute {
            try {
                val info = volumeInfos.find { it.volumeIndex == volumeIndex }
                if (info == null) {
                    mainReject(promise, "GARMIN_VOLUME_NOT_FOUND", "Unknown volume")
                    return@execute
                }
                if (info.hasGarminDeviceXml) {
                    mainReject(promise, "GARMIN_REFUSED_INTERNAL_WRITE",
                        "Refusing to write to internal memory — SD card only, by design. See GARMIN_USB_IMPORT_SPEC.md.")
                    return@execute
                }
                val (device, partitionOffset) = deviceAndPartitionForVolumeIndex(volumeIndex)
                if (device == null) {
                    mainReject(promise, "GARMIN_VOLUME_NOT_FOUND", "Volume no longer available")
                    return@execute
                }
                val partition = device.partitions[partitionOffset]
                val root = partition.fileSystem.rootDirectory
                val garminDir = findChildCaseInsensitive(root, "Garmin")
                    ?: root.createDirectory("Garmin")
                val gpxDir = findChildCaseInsensitive(garminDir, "GPX")
                    ?: garminDir.createDirectory("GPX")
                val existing = findChildCaseInsensitive(gpxDir, fileName)
                val target = existing ?: gpxDir.createFile(fileName)
                val os = UsbFileOutputStream(target)
                os.write(gpxContent.toByteArray(Charsets.UTF_8))
                os.close()
                mainResolve(promise, true)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_WRITE_FAILED", e.message ?: "Unknown error writing $fileName")
            }
        }
    }

    @ReactMethod
    fun disconnect(promise: Promise) {
        executor.execute {
            closeAllDevices()
            safRoot = null
            mainResolve(promise, true)
        }
    }


    // ─── FIT activities over USB Mass Storage (Forerunner 245 "Stockage de masse" mode) ────────
    @ReactMethod
    fun usbMode(promise: Promise) {
        try {
            val um = reactContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = um.deviceList.values.find { it.vendorId == GARMIN_VID }
            if (dev == null) { promise.resolve("none"); return }
            var mass = false
            for (i in 0 until dev.interfaceCount) if (dev.getInterface(i).interfaceClass == 8) mass = true
            promise.resolve(if (mass) "mass" else "other")
        } catch (e: Exception) {
            promise.resolve("other")
        }
    }

    // usbInfo(): what the phone sees on USB, for error messages (e.g. "091e:4abb classes=8").
    @ReactMethod
    fun usbInfo(promise: Promise) {
        promise.resolve(describeUsbDevices(reactContext))
    }

    @ReactMethod
    fun listFitFiles(limit: Int, promise: Promise) {
        executor.execute {
            try {
                val safDir = safActivityDirectory(safRoot)
                if (safDir != null) {
                    val files = safDir.listFiles()
                        .filter { !it.isDirectory && (it.name ?: "").endsWith(".fit", ignoreCase = true) }
                        .sortedByDescending { (it.name ?: "").lowercase() }
                    val arr = Arguments.createArray()
                    for (file in files.take(if (limit > 0) limit else files.size)) {
                        val m = Arguments.createMap()
                        m.putInt("volumeIndex", 0)
                        m.putString("name", file.name)
                        arr.pushMap(m)
                    }
                    mainResolve(promise, arr)
                    return@execute
                }
                val found = ArrayList<Pair<Int, String>>()
                var vi = 0
                var folderFound = false 
                for (device in openDevices) {
                    for (p in device.partitions) {
                        val root = p.fileSystem.rootDirectory
                        val g = findChildCaseInsensitive(root, "Garmin")
                        if (g == null) { vi++; continue } 
                        
                        val a = findChildCaseInsensitive(g, "Activity")
                        if (a == null) { vi++; continue } 
                        
                        folderFound = true 
                        for (f in a.listFiles()) {
                            if (!f.isDirectory && f.name.endsWith(".fit", ignoreCase = true)) found.add(Pair(vi, f.name))
                        }
                        vi++
                    }
                }
                
                if (!folderFound) {
                    mainReject(promise, "GARMIN_NO_FOLDER", "Le dossier Garmin/Activity n'a pas été trouvé sur le volume.")
                    return@execute
                }

                found.sortByDescending { it.second.lowercase() }
                val arr = Arguments.createArray()
                for (f in found.take(if (limit > 0) limit else found.size)) {
                    val m = Arguments.createMap()
                    m.putInt("volumeIndex", f.first)
                    m.putString("name", f.second)
                    arr.pushMap(m)
                }
                mainResolve(promise, arr)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_LIST_FAILED", e.message ?: "Unknown error listing .fit files")
            }
        }
    }

    @ReactMethod
    fun copyFitToCache(volumeIndex: Int, name: String, promise: Promise) {
        executor.execute {
            try {
                val safDir = safActivityDirectory(safRoot)
                if (safDir != null) {
                    val file = findDocumentChild(safDir, name)
                        ?: run { mainReject(promise, "GARMIN_FILE_NOT_FOUND", "Fichier $name introuvable"); return@execute }
                    val bytes = reactContext.contentResolver.openInputStream(file.uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                        out.toByteArray()
                    } ?: throw IllegalStateException("Impossible de lire $name")
                    val dir = java.io.File(reactContext.cacheDir, "garmin_msc").apply { mkdirs() }
                    val dest = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    dest.writeBytes(bytes)
                    mainResolve(promise, dest.absolutePath)
                    return@execute
                }
                val (device, off) = deviceAndPartitionForVolumeIndex(volumeIndex)
                if (device == null) { mainReject(promise, "GARMIN_FILE_NOT_FOUND", "Volume introuvable"); return@execute }
                val root = device.partitions[off].fileSystem.rootDirectory
                val g = findChildCaseInsensitive(root, "Garmin")
                if (g == null) { mainReject(promise, "GARMIN_NO_FOLDER", "Dossier Garmin introuvable"); return@execute }
                val a = findChildCaseInsensitive(g, "Activity")
                if (a == null) { mainReject(promise, "GARMIN_NO_FOLDER", "Dossier Activity introuvable"); return@execute }
                val file = findChildCaseInsensitive(a, name)
                if (file == null) { mainReject(promise, "GARMIN_FILE_NOT_FOUND", "Fichier $name introuvable"); return@execute }
                
                val dir = java.io.File(reactContext.cacheDir, "garmin_msc")
                dir.mkdirs()
                val dest = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                if (dest.exists()) dest.delete()
                
                try {
                    dest.writeBytes(readUsbFileFully(file))
                } catch (e: Exception) {
                    mainReject(promise, "GARMIN_READ_FAILED", "Impossible de lire le fichier $name : ${e.message}")
                    return@execute
                }
                mainResolve(promise, dest.absolutePath)
            } catch (e: Exception) {
                mainReject(promise, "GARMIN_READ_FAILED", e.message ?: "Unknown error reading $name")
            }
        }
    }

    // ─── internal helpers ────────────────────────────────────────────────────

    private fun resolveVolumeActivityDir(volumeIndex: Int): UsbFile? {
        val info = volumeInfos.find { it.volumeIndex == volumeIndex } ?: return null
        val path = info.activityPath ?: return null
        val (device, partitionOffset) = deviceAndPartitionForVolumeIndex(volumeIndex)
        if (device == null) return null
        val root = device.partitions[partitionOffset].fileSystem.rootDirectory
        var current: UsbFile = root
        for (segment in path.split("/").filter { it.isNotEmpty() }) {
            current = findChildCaseInsensitive(current, segment) ?: return null
        }
        return current
    }

    private fun resolveVolumeGpxDir(volumeIndex: Int): UsbFile? {
        val (device, partitionOffset) = deviceAndPartitionForVolumeIndex(volumeIndex)
        if (device == null) return null
        val root = device.partitions[partitionOffset].fileSystem.rootDirectory
        val garminDir = findChildCaseInsensitive(root, "Garmin") ?: return null
        return findChildCaseInsensitive(garminDir, "GPX")
    }

    private fun deviceAndPartitionForVolumeIndex(volumeIndex: Int): Pair<UsbMassStorageDevice?, Int> {
        var i = 0
        for (device in openDevices) {
            for (p in device.partitions.indices) {
                if (i == volumeIndex) return Pair(device, p)
                i++
            }
        }
        return Pair(null, -1)
    }

    private fun closeAllDevices() {
        closeDevicesList(openDevices)
        openDevices = emptyList()
        volumeInfos = emptyList()
    }

    private fun closeDevicesList(devices: List<UsbMassStorageDevice>) {
        for (d in devices) {
            try { d.close() } catch (_: Exception) {}
        }
    }

    private fun mainResolve(promise: Promise, value: Any) {
        UiThreadUtil.runOnUiThread {
            when (value) {
                is WritableMap -> promise.resolve(value)
                is WritableArray -> promise.resolve(value)
                is String -> promise.resolve(value)
                is Boolean -> promise.resolve(value)
                else -> promise.resolve(null)
            }
        }
    }

    private fun mainReject(promise: Promise, code: String, message: String) {
        UiThreadUtil.runOnUiThread { promise.reject(code, message) }
    }
}

private fun findChildCaseInsensitive(dir: UsbFile, name: String): UsbFile? =
    dir.listFiles().find { it.name.equals(name, ignoreCase = true) }

private fun findDocumentChild(dir: DocumentFile, name: String): DocumentFile? =
    dir.listFiles().firstOrNull { it.name?.equals(name, ignoreCase = true) == true }

private fun readUsbFileFully(file: UsbFile): ByteArray {
    val ins = UsbFileInputStream(file)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var totalBytes = 0
    while (true) {
        val n = ins.read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
        totalBytes += n
    }
    ins.close()
    println("GarminModule: Lu $totalBytes octets pour ${file.name}")
    return out.toByteArray()
}

private data class ParsedGarminDevice(
    val model: String?,
    val firmwareVersion: String?,
    val partNumber: String?,
    val activityPath: String?,
)

private fun parseGarminDeviceXml(bytes: ByteArray): ParsedGarminDevice? {
    return try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        var model: String? = null
        var softwareVersion: String? = null
        var partNumber: String? = null
        var activityPath: String? = null

        var inModel = false
        var inGpsDataType = false
        var currentDataTypeName: String? = null
        var inFile = false
        var currentTransferDirection: String? = null
        var currentPath: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "Model" -> inModel = true
                        "PartNumber" -> if (inModel) partNumber = parser.nextText()
                        "SoftwareVersion" -> if (inModel) softwareVersion = parser.nextText()
                        "Description" -> if (inModel) model = parser.nextText()
                        "DataType" -> currentDataTypeName = null
                        "Name" -> if (currentDataTypeName == null) {
                            currentDataTypeName = parser.nextText()
                            inGpsDataType = currentDataTypeName == "GPSData"
                        }
                        "File" -> { inFile = true; currentTransferDirection = null; currentPath = null }
                        "Path" -> if (inFile) currentPath = parser.nextText()
                        "TransferDirection" -> if (inFile) currentTransferDirection = parser.nextText()
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Model" -> inModel = false
                        "File" -> {
                            if (inGpsDataType && currentTransferDirection == "OutputFromUnit" && currentPath != null) {
                                activityPath = currentPath
                            }
                            inFile = false
                        }
                        "DataType" -> inGpsDataType = false
                    }
                }
            }
            eventType = parser.next()
        }

        ParsedGarminDevice(
            model = model,
            firmwareVersion = softwareVersion?.let { formatSoftwareVersion(it) },
            partNumber = partNumber,
            activityPath = activityPath,
        )
    } catch (e: Exception) {
        null
    }
}

private fun formatSoftwareVersion(raw: String): String {
    val digits = raw.trim()
    if (digits.length <= 2) return "0.${digits.padStart(2, '0')}"
    val whole = digits.substring(0, digits.length - 2)
    val frac = digits.substring(digits.length - 2)
    return "$whole.$frac"
}
// Diagnostic for "no Garmin found": every USB device the phone sees (vendor:product and interface classes).
// A Garmin shows up as 091e:xxxx; class 8 = Mass Storage, 6 = MTP/PTP.
internal fun describeUsbDevices(context: Context): String {
    return try {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val list = um.deviceList.values
        if (list.isEmpty()) "none" else list.joinToString("; ") { d ->
            String.format("%04x:%04x", d.vendorId, d.productId) + " classes=" +
                (0 until d.interfaceCount).joinToString(",") { d.getInterface(it).interfaceClass.toString() }
        }
    } catch (e: Exception) {
        "unavailable"
    }
}
