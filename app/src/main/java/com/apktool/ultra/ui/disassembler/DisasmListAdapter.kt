package com.apktool.ultra.ui.disassembler

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.apktool.ultra.databinding.ItemDisasmBinding

class DisasmListAdapter(
    private val items: List<DisassemblerActivity.DisasmListItem>
) : RecyclerView.Adapter<DisasmListAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemDisasmBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDisasmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.itemTitle.text = item.title
        holder.binding.itemSubtitle.text = item.subtitle
        holder.binding.itemDetail.text = item.detail
        holder.binding.root.setOnClickListener { item.onClick() }
    }

    override fun getItemCount() = items.size
}
