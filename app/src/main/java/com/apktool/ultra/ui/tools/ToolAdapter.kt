package com.apktool.ultra.ui.tools

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.apktool.ultra.core.ToolDownloader
import com.apktool.ultra.databinding.ItemToolBinding

class ToolAdapter(
    private val tools: List<Pair<ToolDownloader.Tool, Boolean>>,
    private val onDownload: (ToolDownloader.Tool) -> Unit
) : RecyclerView.Adapter<ToolAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (tool, installed) = tools[position]
        holder.binding.toolName.text = tool.displayName
        holder.binding.toolDescription.text = tool.description
        holder.binding.toolSize.text = tool.sizeEstimate

        if (installed) {
            holder.binding.btnAction.text = "Installed"
            holder.binding.btnAction.isEnabled = false
            holder.binding.statusIndicator.setBackgroundColor(0xFF4CAF50.toInt())
        } else {
            holder.binding.btnAction.text = "Download"
            holder.binding.btnAction.isEnabled = true
            holder.binding.statusIndicator.setBackgroundColor(0xFFFF9800.toInt())
        }

        holder.binding.btnAction.setOnClickListener { onDownload(tool) }
    }

    override fun getItemCount() = tools.size
}
