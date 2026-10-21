package com.previewkit.idea.manifest

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class UnsupportedManifestService(private val project: Project) : Disposable {

    private val snapshotRef =
        AtomicReference<Map<String, List<UnsupportedManifestEntry>>?>(null)
    private val loading = AtomicBoolean(false)
    private val generation = AtomicLong(0)

    private val messageBusConnection: MessageBusConnection =
        project.messageBus.connect(this)

    init {
        messageBusConnection.subscribe(VirtualFileManager.VFS_CHANGES, ManifestFileListener())
        requestLoad()
    }

    fun entriesFor(module: Module): List<UnsupportedManifestEntry> {
        val snapshot = snapshotRef.get()
        if (snapshot == null) {
            requestLoad()
            return emptyList()
        }
        return contentRootPathsOf(module).flatMap { rootPath -> snapshot[rootPath].orEmpty() }
    }

    fun invalidate() {
        generation.incrementAndGet()
        snapshotRef.set(null)
    }

    override fun dispose() {
    }

    private fun requestLoad() {
        if (!loading.compareAndSet(false, true)) return
        val epoch = generation.get()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val rootPaths = ReadAction.compute<List<String>, RuntimeException> { collectContentRootPaths() }
                val entriesByRoot = UnsupportedManifestLocator.loadManifests(rootPaths)
                if (generation.get() == epoch) snapshotRef.set(entriesByRoot)
            } finally {
                loading.set(false)
            }
        }
    }

    private fun collectContentRootPaths(): List<String> {
        return ModuleManager.getInstance(project).modules
            .flatMap { module -> ModuleRootManager.getInstance(module).contentRoots.map { root -> root.path } }
            .distinct()
    }

    private fun contentRootPathsOf(module: Module): List<String> {
        return ModuleRootManager.getInstance(module).contentRoots.map { root -> root.path }
    }

    private inner class ManifestFileListener : BulkFileListener {

        override fun before(events: List<VFileEvent>) {
            invalidateIfManifest(events)
        }

        override fun after(events: List<VFileEvent>) {
            invalidateIfManifest(events)
        }
    }

    private fun invalidateIfManifest(events: List<VFileEvent>) {
        for (event in events) {
            val file = event.file ?: continue
            if (file.path.endsWith(MANIFEST_PATH_SUFFIX)) invalidate()
        }
    }

    companion object {

        fun getInstance(project: Project): UnsupportedManifestService =
            project.getService(UnsupportedManifestService::class.java)
    }
}
