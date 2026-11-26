package com.medimate;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class MedicineAdapter
        extends RecyclerView.Adapter<MedicineAdapter.MedicineViewHolder> {

    private List<Medicine> medicineList;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(Medicine medicine);
    }

    /** 길게 누르기 (내 약 지우기 등). 없으면 null */
    private OnItemClickListener longClickListener;

    public void setOnItemLongClickListener(OnItemClickListener listener) {
        this.longClickListener = listener;
    }

    public MedicineAdapter(
            List<Medicine> medicineList,
            OnItemClickListener listener
    ) {
        this.medicineList = medicineList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public MedicineViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {

        View view = LayoutInflater
                .from(parent.getContext())
                .inflate(R.layout.item_medicine, parent, false);

        return new MedicineViewHolder(view);
    }

    @Override
    public void onBindViewHolder(
            @NonNull MedicineViewHolder holder,
            int position
    ) {

        Medicine medicine = medicineList.get(position);

        holder.tvName.setText(medicine.getName());
        holder.tvInfo.setText(medicine.getInfo());
        // 복용법이 없으면 빈 줄을 남기지 않는다 (이름이 위로 치우치지 않게)
        boolean noInfo = medicine.getInfo() == null || medicine.getInfo().trim().isEmpty();
        holder.tvInfo.setVisibility(noInfo ? View.GONE : View.VISIBLE);

        holder.itemView.setOnClickListener(v -> {
            listener.onItemClick(medicine);
        });
        if (longClickListener != null) {
            holder.itemView.setOnLongClickListener(v -> {
                longClickListener.onItemClick(medicine);
                return true;
            });
        }
    }

    @Override
    public int getItemCount() {
        return medicineList.size();
    }

    static class MedicineViewHolder extends RecyclerView.ViewHolder {

        TextView tvName;
        TextView tvInfo;

        public MedicineViewHolder(@NonNull View itemView) {
            super(itemView);

            tvName = itemView.findViewById(R.id.tv_medicine_name);
            tvInfo = itemView.findViewById(R.id.tv_medicine_info);
        }
    }
}
