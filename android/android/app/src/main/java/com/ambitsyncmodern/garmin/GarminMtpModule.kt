// Copyright (C) 2026 breizhvenum. Licensed under the GNU GPL v3 (see LICENSE).
package com.ambitsyncmodern.garmin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.io.File
import java.util.concurrent.Executors

/*
 * Garmin rom1rv: READ-ONLY access to a watch that shows up over USB in MTP mode (Forerunner 255...),
 * not as USB mass storage (that case is GarminModule's). Uses Android's own android.mtp.MtpDevice.
 * Nothing is ever written, renamed or deleted on the watch: only getObjectHandles / getObjectInfo / importFile.
 * Files are copied to the app's cache; the JS side then imports them like any picked .fit.
 */

private const val MTP_GARMIN_VID = 2334 // 0x091E
private const val ACTION_GARMIN_MTP_PERMISSION = "com.ambitsyncmodern.GARMIN_MTP_PERMISSION"

class GarminMtpModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val executor = Executors.newSingleThreadExecutor()
    private var mtp: MtpDevice? = null
    private var pendingPromise: Promise? = null
    private var pendingAction: ((UsbDevice) -> Unit)? = null

    override fun getName() = "GarminMtp"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_GARMIN_MTP_PERMISSION) return
            try { reactContext.unregisterReceiver(this) } catch (_: Exception) {}
            val promise = pendingPromise
            val action = pendingAction
            pendingPromise = null
            pendingAction = null
            if (promise == null || action == null) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device: UsbDevice? = findGarmin()
            if (!granted || device == null) {
                promise.reject("USB_PERMISSION_DENIED", "Autorisation USB refusée (ou montre débranchée).")
                return
            }
            action(device)
        }
    }

    private fun usbManager() = reactContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun findGarmin(): UsbDevice? =
        usbManager().deviceList.values.find { it.vendorId == MTP_GARMIN_VID }

    // Finds the watch, asks the USB permission if needed, then runs `action` with it.
    private fun withGarmin(promise: Promise, action: (UsbDevice) -> Unit) {
        // On déplace la recherche dans le thread d'arrière-plan pour ne pas geler l'UI
        // et on attend que la montre apparaisse (jusqu'à 15 secondes).
        executor.execute {
            var device: UsbDevice? = null
            val deadline = System.currentTimeMillis() + 15000L
            while (System.currentTimeMillis() < deadline) {
                device = findGarmin()
                if (device != null) break
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }

            if (device == null) {
                promise.reject("GARMIN_NOT_FOUND", "Aucune montre Garmin détectée. Vérifie le câble, que la montre est allumée et déverrouillée puis recharge les activités")
                return@execute
            }
            val um = usbManager()
            if (um.hasPermission(device)) {
                action(device)
                return@execute
            }
            pendingPromise = promise
            pendingAction = action
            
            // CORRECTION ANDROID 14 : Utilisation de FLAG_IMMUTABLE au lieu de FLAG_MUTABLE
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            val intent = Intent(ACTION_GARMIN_MTP_PERMISSION).setPackage(reactContext.packageName)
            val pi = PendingIntent.getBroadcast(reactContext, 1, intent, flags)
            val filter = IntentFilter(ACTION_GARMIN_MTP_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                reactContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                reactContext.registerReceiver(permissionReceiver, filter)
            }
            um.requestPermission(device, pi)
        }
    }

    private fun openMtp(device: UsbDevice): MtpDevice {
        try { mtp?.close() } catch (_: Exception) {}
        mtp = null
        val conn = usbManager().openDevice(device)
            ?: throw Exception("Impossible d'ouvrir la connexion USB avec la montre à ce stade. Recharger les activités à nouveau")
        val d = MtpDevice(device)
        if (!d.open(conn)) {
            conn.close()
            throw Exception("La montre ne répond pas en MTP. Déverrouille-la et vérifie qu'elle est en mode USB « transfert de fichiers ». Recharger les activités à nouveau")
        }
        mtp = d
        return d
    }

    private fun childFolder(d: MtpDevice, storageId: Int, parent: Int, name: String): Int? {
        val handles = d.getObjectHandles(storageId, 0, parent) ?: return null
        for (h in handles) {
            val info = d.getObjectInfo(h) ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION && info.name.equals(name, ignoreCase = true)) return h
        }
        return null
    }

    // GARMIN/Activity on the first storage that has it (FR255: "Primary").
    private fun findActivityFolder(d: MtpDevice): Pair<Int, Int>? {
        val storages = d.storageIds ?: return null
        for (sid in storages) {
            val garmin = childFolder(d, sid, 0, "GARMIN") ?: continue
            val act = childFolder(d, sid, garmin, "Activity") ?: continue
            return Pair(sid, act)
        }
        return null
    }

    // The `limit` most recent .fit files of the watch: [{ handle, name, size, modified(ms) }].
    @ReactMethod
    fun list(limit: Int, promise: Promise) {
        withGarmin(promise) { device ->
            executor.execute {
                try {
                    // The watch can take several seconds after the cable is plugged in before it exposes its
                    // storage: retry (re-opening the link each time) for up to 30 s instead of failing at once.
                    var dev: MtpDevice? = null
                    var found: Pair<Int, Int>? = null
                    var lastErr = "La montre ne répond pas."
                    val deadline = System.currentTimeMillis() + 30000L
                    var current: UsbDevice? = device
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val usb = findGarmin() ?: current
                                ?: throw Exception("Montre débranchée.")
                            current = usb
                            val opened = openMtp(usb)
                            val fol = findActivityFolder(opened)
                            if (fol != null) { dev = opened; found = fol; break }
                            lastErr = "Dossier GARMIN/Activity introuvable : la montre n'a pas fini de démarrer son stockage."
                        } catch (e: Exception) {
                            lastErr = e.message ?: e.toString()
                            if (findGarmin() == null) break   // really unplugged: no point waiting
                        }
                        try { mtp?.close() } catch (_: Exception) {}
                        mtp = null
                        Thread.sleep(1500)
                    }
                    val d = dev ?: throw Exception(lastErr)
                    val folder = found ?: throw Exception(lastErr)
                    val handles = d.getObjectHandles(folder.first, 0, folder.second) ?: IntArray(0)
                    val files = ArrayList<Triple<Int, String, Pair<Int, Long>>>()
                    for (h in handles) {
                        val info = d.getObjectInfo(h) ?: continue
                        if (info.format == MtpConstants.FORMAT_ASSOCIATION) continue
                        val n = info.name ?: continue
                        if (!n.endsWith(".fit", ignoreCase = true)) continue
                        files.add(Triple(h, n, Pair(info.compressedSize, info.dateModified)))
                    }
                    files.sortByDescending { it.third.second }
                    val arr = Arguments.createArray()
                    for (f in files.take(if (limit > 0) limit else files.size)) {
                        val m = Arguments.createMap()
                        m.putInt("handle", f.first)
                        m.putString("name", f.second)
                        m.putDouble("size", f.third.first.toDouble())
                        m.putDouble("modified", f.third.second.toDouble())
                        arr.pushMap(m)
                    }
                    promise.resolve(arr)
                } catch (e: Exception) {
                    promise.reject("MTP_ERROR", e.message ?: e.toString(), e)
                }
            }
        }
    }

    // Copies one file of the watch to the app's cache and returns its path (the watch is not modified).
    @ReactMethod
    fun copyToCache(handle: Int, name: String, promise: Promise) {
        executor.execute {
            try {
                val d = mtp ?: throw Exception("Montre non connectée : relance la lecture.")
                val dir = File(reactContext.cacheDir, "garmin_mtp")
                dir.mkdirs()
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dest = File(dir, safe)
                if (dest.exists()) dest.delete()
                if (!d.importFile(handle, dest.absolutePath)) throw Exception("Copie impossible : $name")
                promise.resolve(dest.absolutePath)
            } catch (e: Exception) {
                promise.reject("MTP_ERROR", e.message ?: e.toString(), e)
            }
        }
    }

    @ReactMethod
    fun close(promise: Promise) {
        executor.execute {
            try { mtp?.close() } catch (_: Exception) {}
            mtp = null
            promise.resolve(true)
        }
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
