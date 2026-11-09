package com.medimate.ui.drug;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;
import com.medimate.model.Drug;

import java.util.List;

class DrugAdapter extends RecyclerView.Adapter<DrugAdapter.Holder> {

    interface OnDrugClickListener {
        void onDrugClick(Drug drug);
    }

    private final List<Drug> drugs;
    private final OnDrugClickListener listener;

    DrugAdapter(List<Drug> drugs, OnDrugClickListener listener) {
        this.drugs = drugs;
        this.listener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_drug, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Drug drug = drugs.get(position);
        holder.name.setText(drug.getName());
        holder.entp.setText(drug.getEntpName());
        holder.entp.setVisibility(drug.getEntpName().isEmpty() ? View.GONE : View.VISIBLE);
        holder.dosage.setText(drug.getDosage());
        holder.dosage.setVisibility(drug.getDosage().isEmpty() ? View.GONE : View.VISIBLE);
        holder.description.setText(drug.getDescription());
        holder.itemView.setOnClickListener(v -> listener.onDrugClick(drug));
    }

    @Override
    public int getItemCount() {
        return drugs.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView entp;
        final TextView dosage;
        final TextView description;

        Holder(View view) {
            super(view);
            name = view.findViewById(R.id.text_name);
            entp = view.findViewById(R.id.text_entp);
            dosage = view.findViewById(R.id.text_dosage);
            description = view.findViewById(R.id.text_description);
        }
    }
}
