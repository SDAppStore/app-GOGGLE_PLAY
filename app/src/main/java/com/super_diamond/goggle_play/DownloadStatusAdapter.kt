package com.super_diamond.goggle_play

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator

class DownloadStatusAdapter(
    private val onClick: (DownloadProgress) -> Unit
) : RecyclerView.Adapter<DownloadStatusAdapter.Holder>() {
    private val items = mutableListOf<DownloadProgress>()

    fun submit(progresses: List<DownloadProgress>) {
        items.clear()
        items.addAll(progresses.filter { it.stage != DownloadProgress.Stage.FINISHED }.sortedByDescending { it.updatedAt })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.row_download_status, parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.downloadTitle)
        private val subtitle: TextView = view.findViewById(R.id.downloadSubtitle)
        private val bar: LinearProgressIndicator = view.findViewById(R.id.downloadProgress)

        fun bind(item: DownloadProgress) {
            title.text = item.title
            subtitle.text = item.subtitle.ifBlank { item.appName }
            bar.isIndeterminate = item.stage == DownloadProgress.Stage.PREPARING || (item.isActive && item.totalBytes <= 0)
            if (!bar.isIndeterminate) {
                val value = if (item.stage == DownloadProgress.Stage.READY_TO_INSTALL) 100 else item.overallPercent
                bar.setProgressCompat(value, false)
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
