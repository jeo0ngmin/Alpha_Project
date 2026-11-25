package com.medimate;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.List;

public class MedicineConfirmAdapter
        extends RecyclerView.Adapter<MedicineConfirmAdapter.MedicineViewHolder> {


    public interface OnSelectionChangedListener {

        void onSelectionChanged();
    }


    private final List<MedicineConfirmItem> medicineList;

    private final OnSelectionChangedListener listener;


    public MedicineConfirmAdapter(
            List<MedicineConfirmItem> medicineList,
            OnSelectionChangedListener listener
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

        View view =
                LayoutInflater.from(
                        parent.getContext()
                ).inflate(
                        R.layout.item_medicine_confirm,
                        parent,
                        false
                );


        return new MedicineViewHolder(view);
    }


    @Override
    public void onBindViewHolder(
            @NonNull MedicineViewHolder holder,
            int position
    ) {

        MedicineConfirmItem medicine =
                medicineList.get(position);


        holder.tvMedicineName.setText(
                medicine.getName()
        );

        holder.tvMedicineInfo.setText(
                medicine.getInfo()
        );


        holder.checkMedicine.setOnCheckedChangeListener(
                null
        );


        holder.checkMedicine.setChecked(
                medicine.isSelected()
        );


        holder.checkMedicine.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {

                    medicine.setSelected(
                            isChecked
                    );

                    listener.onSelectionChanged();
                }
        );


        holder.cardMedicine.setOnClickListener(v -> {

            boolean newState =
                    !medicine.isSelected();

            medicine.setSelected(
                    newState
            );

            holder.checkMedicine.setChecked(
                    newState
            );

            listener.onSelectionChanged();
        });
    }


    @Override
    public int getItemCount() {

        return medicineList.size();
    }


    static class MedicineViewHolder
            extends RecyclerView.ViewHolder {

        MaterialCardView cardMedicine;

        MaterialCheckBox checkMedicine;

        TextView tvMedicineName;
        TextView tvMedicineInfo;


        public MedicineViewHolder(
                @NonNull View itemView
        ) {

            super(itemView);


            cardMedicine =
                    itemView.findViewById(
                            R.id.card_confirm_medicine
                    );

            checkMedicine =
                    itemView.findViewById(
                            R.id.check_medicine
                    );

            tvMedicineName =
                    itemView.findViewById(
                            R.id.tv_confirm_medicine_name
                    );

            tvMedicineInfo =
                    itemView.findViewById(
                            R.id.tv_confirm_medicine_info
                    );
        }
    }
}