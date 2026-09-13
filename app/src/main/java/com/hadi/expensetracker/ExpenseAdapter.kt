package com.hadi.expensetracker

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hadi.expensetracker.databinding.ItemExpenseBinding
import java.text.DecimalFormat

class ExpenseAdapter(
    private val onEdit: (Expense) -> Unit,
    private val onDelete: (Expense) -> Unit
) : ListAdapter<Expense, ExpenseAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val formatter = DecimalFormat("#,###")

    inner class ViewHolder(val binding: ItemExpenseBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnDelete.applyPressAnimation()
            binding.root.applyPressAnimation(pressedScale = 0.98f)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExpenseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvItemTitle.text = item.title
        holder.binding.tvItemCategory.text = Category.labelOf(item.category)
        holder.binding.tvItemAmount.text = formatter.format(item.amount)
        holder.binding.tvItemCategory.background.mutate().setTint(
            ContextCompat.getColor(holder.binding.root.context, Category.colorResOf(item.category))
        )
        holder.binding.root.setOnClickListener { onEdit(item) }
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Expense>() {
            override fun areItemsTheSame(oldItem: Expense, newItem: Expense): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Expense, newItem: Expense): Boolean =
                oldItem == newItem
        }
    }
}
