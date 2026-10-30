package com.medimate.ui.recommend;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;
import com.medimate.recommend.ProductLinks;

import java.util.ArrayList;
import java.util.List;

class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    /** 링크 버튼을 누르면 화면이 연다 */
    interface LinkListener {
        void onLinkClick(ProductLinks.Link link);
    }

    private static final int TYPE_USER = 0;
    private static final int TYPE_BOT = 1;
    private static final int MIN_TOUCH_DP = 48;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final LinkListener linkListener;

    ChatAdapter(LinkListener linkListener) {
        this.linkListener = linkListener;
    }

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
        ChatMessage message = messages.get(position);
        holder.text.setText(message.text);
        if (holder.links == null) {
            return;
        }
        holder.links.removeAllViews();
        for (ProductLinks.Link link : message.links) {
            holder.links.addView(linkButton(holder.links, link));
        }
    }

    /** 고령층·TalkBack 사용자를 위해 48dp 이상, 18sp 글자, 버튼 이름을 그대로 읽게 한다 */
    private Button linkButton(ViewGroup parent, ProductLinks.Link link) {
        Button button = new Button(parent.getContext());
        button.setText(link.label);
        button.setContentDescription(link.label + ", 쿠팡 열기");
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setAllCaps(false);
        int minPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, MIN_TOUCH_DP,
                parent.getResources().getDisplayMetrics());
        button.setMinHeight(minPx);
        button.setMinimumHeight(minPx);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = minPx / 6;
        button.setLayoutParams(params);
        button.setOnClickListener(v -> linkListener.onLinkClick(link));
        return button;
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView text;
        /** 봇 말풍선에만 있다 */
        final LinearLayout links;

        Holder(View view) {
            super(view);
            text = view.findViewById(R.id.text_message);
            links = view.findViewById(R.id.links_container);
        }
    }
}
