package com.apktool.ultra.ui.decompile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.apktool.ultra.R
import com.apktool.ultra.databinding.ItemFileBinding
import com.apktool.ultra.utils.FileUtils
import java.io.File

class FileListAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.binding.fileName.text = file.name
        holder.binding.fileSize.text = if (file.isDirectory) {
            "${file.listFiles()?.size ?: 0} items"
        } else {
            FileUtils.getFileSize(file)
        }
        holder.binding.fileIcon.setImageResource(
            if (file.isDirectory) R.drawable.ic_folder
            else when (file.extension.lowercase()) {
                "smali" -> R.drawable.ic_code
                "xml" -> R.drawable.ic_xml
                "so" -> R.drawable.ic_lib
                "dex" -> R.drawable.ic_dex
                "png", "jpg", "jpeg", "webp" -> R.drawable.ic_image
                else -> R.drawable.ic_file
            }
        )
        holder.binding.root.setOnClickListener { onClick(file) }
    }

    override fun getItemCount() = files.size
}
