package com.apktool.ultra.ui.archive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.apktool.ultra.R
import com.apktool.ultra.core.ArchiveManager
import com.apktool.ultra.databinding.ItemArchiveEntryBinding

class ArchiveEntryAdapter(
    private val entries: List<ArchiveManager.ArchiveEntry>,
    private val onClick: (ArchiveManager.ArchiveEntry) -> Unit,
    private val onLongClick: (ArchiveManager.ArchiveEntry) -> Unit
) : RecyclerView.Adapter<ArchiveEntryAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemArchiveEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemArchiveEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]

        holder.binding.entryName.text = entry.name
        holder.binding.entrySize.text = if (entry.isDirectory) "DIR" else entry.displaySize
        holder.binding.entryType.text = entry.icon
        holder.binding.entryCompression.text = if (entry.isDirectory) "" else entry.compressionRatio

        holder.binding.entryIcon.setImageResource(
            if (entry.isDirectory) R.drawable.ic_folder
            else when (entry.extension.lowercase()) {
                "dex" -> R.drawable.ic_dex
                "xml" -> R.drawable.ic_xml
                "so" -> R.drawable.ic_lib
                "png", "jpg", "jpeg", "webp" -> R.drawable.ic_image
                "smali" -> R.drawable.ic_code
                "arsc" -> R.drawable.ic_file
                else -> R.drawable.ic_file
            }
        )

        holder.binding.root.setOnClickListener { onClick(entry) }
        holder.binding.root.setOnLongClickListener { onLongClick(entry); true }
    }

    override fun getItemCount() = entries.size
}
