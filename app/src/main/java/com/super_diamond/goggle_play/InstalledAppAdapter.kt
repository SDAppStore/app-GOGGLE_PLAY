package com.super_diamond.goggle_play

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.aurora.gplayapi.data.models.App
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val icon: Drawable,
    val remote: App? = null,
    val checked: Boolean = false
) {
    val hasUpdate: Boolean get() = remote?.versionCode?.let { it > versionCode } == true
}

class InstalledAppAdapter(
    private val onOpen: (InstalledAppEntry) -> Unit,
    private val onUpdate: (App) -> Unit,
    private val onDetails: (InstalledAppEntry) -> Unit,
    private val onReadyToInstall: (DownloadProgress) -> Unit
) : RecyclerView.Adapter<InstalledAppAdapter.Holder>() {
    private val items = mutableListOf<InstalledAppEntry>()
    private val progress = mutableMapOf<String, DownloadProgress>()

    fun submit(entries: List<InstalledAppEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    fun updateRemote(packageName: String, remote: App?) {
        val position = items.indexOfFirst { it.packageName == packageName }
        if (position < 0) return
        items[position] = items[position].copy(remote = remote, checked = true)
        notifyItemChanged(position)
    }

    fun setProgresses(values: List<DownloadProgress>) {
        progress.clear()
        values.forEach { progress[it.packageName] = it }
        notifyDataSetChanged()
    }

    fun updateProgress(value: DownloadProgress) {
        progress[value.packageName] = value
        val position = items.indexOfFirst { it.packageName == value.packageName }
        if (position >= 0) notifyItemChanged(position)
    }

    fun updateCount(): Int = items.count(InstalledAppEntry::hasUpdate)

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
        private val action: MaterialButton = view.findViewById(R.id.installButton)
        private val progressContainer: View = view.findViewById(R.id.rowProgressContainer)
        private val progressText: TextView = view.findViewById(R.id.rowProgressText)
        private val progressBar: LinearProgressIndicator = view.findViewById(R.id.rowProgress)

        fun bind(item: InstalledAppEntry) {
            val download = progress[item.packageName]
            icon.setImageDrawable(item.icon)
            name.text = item.label
            developer.text = item.packageName
            meta.text = when {
                item.hasUpdate -> "Update available  •  ${item.versionName} → ${item.remote?.versionName}"
                item.checked -> "${item.versionName}  •  Up to date"
                else -> "${item.versionName}  •  Checking for updates…"
            }
            action.isEnabled = download?.isActive != true
            action.text = when (download?.stage) {
                DownloadProgress.Stage.PREPARING -> "…"
                DownloadProgress.Stage.DOWNLOADING -> "${download.overallPercent}%"
                DownloadProgress.Stage.READY_TO_INSTALL -> "Install"
                DownloadProgress.Stage.INSTALLING -> "Installing"
                else -> if (item.hasUpdate) "Update" else "Open"
            }
            action.setOnClickListener {
                when {
                    download?.stage == DownloadProgress.Stage.READY_TO_INSTALL -> onReadyToInstall(download)
                    item.hasUpdate -> item.remote?.let(onUpdate)
                    else -> onOpen(item)
                }
            }
            progressContainer.isVisible = download?.isActive == true || download?.stage == DownloadProgress.Stage.READY_TO_INSTALL
            if (progressContainer.isVisible && download != null) {
                progressText.text = download.subtitle.ifBlank { download.title }
                progressBar.isIndeterminate = download.stage == DownloadProgress.Stage.PREPARING || download.totalBytes <= 0
                if (!progressBar.isIndeterminate) progressBar.setProgressCompat(download.overallPercent, true)
            }
            itemView.setOnClickListener { onDetails(item) }
        }
    }
}
