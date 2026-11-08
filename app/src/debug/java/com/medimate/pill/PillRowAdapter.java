package com.medimate.pill;

import java.util.ArrayList;
import java.util.List;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.medimate.R;

/** 약 한 줄(사진·이름·업체·부가 정보). 식별 후보와 검색 결과에서 같이 쓴다. */
final class PillRowAdapter<T> extends RecyclerView.Adapter<PillRowAdapter.Holder> {

    /** 항목 → 화면에 그릴 값. */
    static final class Row {
        final String title;
        final String subtitle;
        final String detail;
        final String imageUrl;
        final boolean badge;
        final String contentDescription;

        Row(String title, String subtitle, String detail, String imageUrl, boolean badge, String contentDescription) {
            this.title = title;
            this.subtitle = subtitle;
            this.detail = detail;
            this.imageUrl = imageUrl;
            this.badge = badge;
            this.contentDescription = contentDescription;
        }
    }

    interface Binder<T> {
        Row bind(T item);
    }

    interface OnClick<T> {
        void onClick(T item);
    }

    private final List<T> items = new ArrayList<>();
    private final Binder<T> binder;
    private final OnClick<T> onClick;

    PillRowAdapter(Binder<T> binder, OnClick<T> onClick) {
        this.binder = binder;
        this.onClick = onClick;
    }

    void submit(List<T> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pill_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        T item = items.get(position);
        Row row = binder.bind(item);
        h.title.setText(row.title);
        h.subtitle.setText(row.subtitle);
        h.subtitle.setVisibility(row.subtitle == null || row.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        h.detail.setText(row.detail);
        h.detail.setVisibility(row.detail == null || row.detail.isEmpty() ? View.GONE : View.VISIBLE);
        h.badge.setVisibility(row.badge ? View.VISIBLE : View.GONE);
        h.itemView.setContentDescription(row.contentDescription);
        Glide.with(h.image)
                .load(row.imageUrl)
                .placeholder(R.drawable.ic_pill_placeholder)
                .error(R.drawable.ic_pill_placeholder)
                .fallback(R.drawable.ic_pill_placeholder)
                .into(h.image);
        h.itemView.setOnClickListener(v -> onClick.onClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView subtitle;
        final TextView detail;
        final TextView badge;

        Holder(View v) {
            super(v);
            image = v.findViewById(R.id.ivPill);
            title = v.findViewById(R.id.tvTitle);
            subtitle = v.findViewById(R.id.tvSubtitle);
            detail = v.findViewById(R.id.tvDetail);
            badge = v.findViewById(R.id.tvBadge);
        }
    }
}
