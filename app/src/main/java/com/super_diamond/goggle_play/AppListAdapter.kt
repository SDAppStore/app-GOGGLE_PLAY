package com.super_diamond.goggle_play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.aurora.gplayapi.data.models.App
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class AppListAdapter(
    private val isInstalled: (String) -> Boolean,
    private val onOpen: (App) -> Unit,
    private val onInstall: (App) -> Unit,
    private val onDetails: (App) -> Unit
) : RecyclerView.Adapter<AppListAdapter.Holder>() {
    private val items = mutableListOf<App>()
    private val downloadProgress = mutableMapOf<String, DownloadProgress>()

    fun submit(apps: List<App>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    fun setProgresses(progresses: List<DownloadProgress>) {
        downloadProgress.clear()
        progresses.forEach { downloadProgress[it.packageName] = it }
        notifyDataSetChanged()
    }

    fun updateProgress(progress: DownloadProgress) {
        downloadProgress[progress.packageName] = progress
        notifyPackageChanged(progress.packageName)
    }

    private fun notifyPackageChanged(packageName: String) {
        val position = items.indexOfFirst { it.packageName == packageName }
        if (position >= 0) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.row_app, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val name: TextView = view.findViewById(R.id.appName)
        private val developer: TextView = view.findViewById(R.id.appDeveloper)
        private val meta: TextView = view.findViewById(R.id.appMeta)
        private val install: MaterialButton = view.findViewById(R.id.installButton)
        private val progressContainer: View = view.findViewById(R.id.rowProgressContainer)
        private val progressText: TextView = view.findViewById(R.id.rowProgressText)
        private val progressBar: LinearProgressIndicator = view.findViewById(R.id.rowProgress)

        fun bind(app: App) {
            name.text = app.displayName.ifBlank { app.packageName }
            developer.text = app.developerName.ifBlank { app.packageName }
            val rating = if (app.rating.average > 0f) "%.1f ★".format(app.rating.average) else "New"
            val size = if (app.size > 0) formatBytes(app.size) else app.categoryName
            meta.text = listOf(rating, size).filter(String::isNotBlank).joinToString("  •  ")
            icon.load(app.iconArtwork.url) {
                crossfade(true)
                placeholder(R.drawable.ic_goggle_triangle)
                error(R.drawable.ic_goggle_triangle)
            }
            val installed = isInstalled(app.packageName)
            val download = downloadProgress[app.packageName]
            val active = download?.isActive == true
            install.isEnabled = !active
            install.text = when {
                download?.stage == DownloadProgress.Stage.DOWNLOADING -> "${download.overallPercent}%"
                download?.stage == DownloadProgress.Stage.PREPARING -> "…"
                download?.stage == DownloadProgress.Stage.READY_TO_INSTALL -> "Install"
                download?.stage == DownloadProgress.Stage.INSTALLING -> "Installing"
                installed -> itemView.context.getString(R.string.open)
                else -> itemView.context.getString(R.string.install)
            }
            install.setOnClickListener {
                if (!active) {
                    if (download?.stage == DownloadProgress.Stage.READY_TO_INSTALL) onInstall(app)
                    else if (isInstalled(app.packageName)) onOpen(app) else onInstall(app)
                }
            }
            progressContainer.isVisible = active || download?.stage == DownloadProgress.Stage.READY_TO_INSTALL
            download?.takeIf { progressContainer.isVisible }?.let { current ->
                progressText.text = current.subtitle.ifBlank { current.title }
                progressBar.isIndeterminate = current.stage == DownloadProgress.Stage.PREPARING || current.totalBytes <= 0
                if (!progressBar.isIndeterminate) progressBar.setProgressCompat(current.overallPercent, true)
            }
            itemView.setOnClickListener { onDetails(app) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            else -> "%.0f KB".format(bytes / 1024.0)
        }
    }
}
