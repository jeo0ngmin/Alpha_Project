package com.medimate.ui.recommend;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;

import java.util.ArrayList;
import java.util.List;

class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_BOT = 1;

    private final List<ChatMessage> messages = new ArrayList<>();

    void submit(List<ChatMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).role == ChatMessage.Role.USER ? TYPE_USER : TYPE_BOT;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_USER ? R.layout.item_chat_user : R.layout.item_chat_bot;
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.text.setText(messages.get(position).text);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView text;

        Holder(View view) {
            super(view);
            text = view.findViewById(R.id.text_message);
        }
    }
}
