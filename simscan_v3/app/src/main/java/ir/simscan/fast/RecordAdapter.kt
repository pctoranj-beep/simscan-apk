package ir.simscan.fast

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.simscan.fast.databinding.ItemRecordBinding

class RecordAdapter(
    private val onClick: (SimRecord) -> Unit
) : RecyclerView.Adapter<RecordAdapter.Holder>() {

    private var items: List<SimRecord> = emptyList()

    fun submit(list: List<SimRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(record: SimRecord, position: Int) {
            binding.rowNumber.text = (items.size - position).toString()
            binding.phoneText.text = "شماره: ${record.phone.ifBlank { "—" }}"
            binding.barcodeText.text = "بارکد: ${record.barcode}"
            binding.statusBadge.text = if (record.phone.isBlank()) "در انتظار شماره" else "کامل ✓"
            val color = if (record.phone.isBlank()) R.color.warning else R.color.accent
            binding.statusBadge.setTextColor(binding.root.context.getColor(color))
            binding.root.setOnClickListener { onClick(record) }
        }
    }
}
