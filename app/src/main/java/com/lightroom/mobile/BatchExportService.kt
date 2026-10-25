package com.lightroom.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Serial, persistent queue: no collection of full-resolution bitmaps is retained in memory. */
class BatchExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var latestStartId = 0
    private var destroyed = false
    private lateinit var store: LibraryStore
    companion object {
        private val mutableProgress = MutableStateFlow("No batch export running")
        val progress: StateFlow<String> = mutableProgress
    }
    override fun onCreate() {
        super.onCreate(); LiteRtModels.init(this); store = LibraryStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("batch_export", "Photo exports", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String, running: Boolean = true): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "batch_export").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("LumaRoom batch export").setContentText(text).setContentIntent(open).setOngoing(running)
            .apply { if (running) addAction(Notification.Action.Builder(null, "Cancel remaining", cancel).build()) }.build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startForeground(401, notification("Preparing photos"))
        if (intent?.action == "cancel") {
            scope.launch { store.cancelExports(); mutableProgress.value = "Remaining exports cancelled"; if (worker?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
            // Let the active image finish; it publishes atomically. The next queued job will be absent.
            return START_NOT_STICKY
        }
        if (worker?.isActive == true) return START_REDELIVER_INTENT
        launchWorker()
        return START_REDELIVER_INTENT
    }
    private fun launchWorker() {
        worker = scope.launch {
            var saved = 0; var failed = 0
            val cancelledBefore = store.exportCounts()["cancelled"] ?: 0
            var paused = false
            try {
                while (isActive) {
                    val job = store.nextExport() ?: break
                    mutableProgress.value = "Exporting ${job.photo.name} · $saved saved · $failed failed"
                    getSystemService(NotificationManager::class.java).notify(401, notification(mutableProgress.value))
                    val result = ImageExporter.export(this@BatchExportService, job.photo, job.state, job.options, job.metadata)
                    when (result) {
                        is ImageExporter.ExportResult.Saved -> { store.finishExport(job.id, "saved:${result.uri}"); saved++ }
                        is ImageExporter.ExportResult.Failed -> { store.finishExport(job.id, "failed: ${result.reason}"); failed++ }
                    }
                }
                mutableProgress.value = "$saved saved · $failed failed · ${(store.exportCounts()["cancelled"] ?: 0)-cancelledBefore} cancelled"
                getSystemService(NotificationManager::class.java).notify(402, notification(mutableProgress.value, false))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { paused = true; mutableProgress.value = "Export paused: ${e.message}. Tap Resume exports." }
            finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    worker = null
                    // A new start may arrive after the queue was observed empty. Recheck before stopping.
                    if (!destroyed && !paused && store.nextExport() != null) launchWorker()
                    else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStartId) }
                }
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { worker?.cancel(); stopSelf() }
    override fun onDestroy() { destroyed = true; val active = worker; scope.cancel(); active?.invokeOnCompletion { store.close() } ?: store.close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
