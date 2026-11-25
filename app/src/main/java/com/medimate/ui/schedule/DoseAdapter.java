package com.medimate.ui.schedule;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;
import com.medimate.medication.DoseItem;
import com.medimate.medication.SyncState;

import java.util.function.Consumer;

/** 알약 모양 복약 버튼. 상태는 색뿐 아니라 아이콘과 문구로도 구분하고, 일정 수정은 더보기 버튼으로 분리한다. */
class DoseAdapter extends ListAdapter<DoseItem, DoseAdapter.Holder> {

    /** 연속 터치로 "완료 → 취소 확인"이 바로 이어지지 않게 한다 */
    private static final long TAP_INTERVAL_MS = 800;

    private static final DiffUtil.ItemCallback<DoseItem> DIFF = new DiffUtil.ItemCallback<DoseItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull DoseItem a, @NonNull DoseItem b) {
            return a.occurrenceKey.equals(b.occurrenceKey);
        }

        @Override
        public boolean areContentsTheSame(@NonNull DoseItem a, @NonNull DoseItem b) {
            return a.equals(b);
        }
    };

    private final Consumer<DoseItem> onTap;
    private final Consumer<DoseItem> onMore;
    private long lastTapAt;
    private boolean notificationsBlocked;

    DoseAdapter(Consumer<DoseItem> onTap, Consumer<DoseItem> onMore) {
        super(DIFF);
        this.onTap = onTap;
        this.onMore = onMore;
    }

    void setNotificationsBlocked(boolean blocked) {
        if (notificationsBlocked != blocked) {
            notificationsBlocked = blocked;
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_medication_dose, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        DoseItem dose = getItem(position);
        holder.bind(dose, notificationsBlocked);
        holder.body.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastTapAt >= TAP_INTERVAL_MS) {
                lastTapAt = now;
                onTap.accept(dose);
            }
        });
        holder.more.setOnClickListener(v -> onMore.accept(dose));
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View body;
        final ImageButton more;
        private final ImageView pillIcon;
        private final ImageView statusIcon;
        private final TextView name;
        private final TextView detail;
        private final TextView status;
        private final TextView note;

        Holder(View view) {
            super(view);
            body = view.findViewById(R.id.dose_body);
            more = view.findViewById(R.id.button_dose_more);
            pillIcon = view.findViewById(R.id.image_pill);
            statusIcon = view.findViewById(R.id.image_status);
            name = view.findViewById(R.id.text_dose_name);
            detail = view.findViewById(R.id.text_dose_detail);
            status = view.findViewById(R.id.text_dose_status);
            note = view.findViewById(R.id.text_dose_note);
        }

        void bind(DoseItem dose, boolean notificationsBlocked) {
            Context context = itemView.getContext();
            int background;
            int foreground;
            int icon;
            if (dose.status == DoseItem.Status.TAKEN) {
                background = R.color.dose_taken_bg;
                foreground = R.color.dose_taken_on;
                icon = R.drawable.ic_check;
            } else if (dose.status == DoseItem.Status.NEEDS_CHECK) {
                background = R.color.dose_check_bg;
                foreground = R.color.dose_check_on;
                icon = R.drawable.ic_help;
            } else {
                background = R.color.dose_scheduled_bg;
                foreground = R.color.dose_scheduled_on;
                icon = R.drawable.ic_schedule;
            }
            int color = ContextCompat.getColor(context, foreground);
            ColorStateList tint = ColorStateList.valueOf(color);

            itemView.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, background)));
            pillIcon.setImageTintList(tint);
            statusIcon.setImageResource(icon);
            statusIcon.setImageTintList(tint);
            more.setImageTintList(tint);

            String timeText = DoseText.time(dose.time);
            String detailText = timeText + " · 1회 " + dose.dosage;
            String noteText = noteText(dose, notificationsBlocked);
            name.setText(dose.drugName);
            name.setTextColor(color);
            detail.setText(detailText);
            detail.setTextColor(color);
            status.setText(dose.status.label);
            status.setTextColor(color);
            note.setText(noteText);
            note.setTextColor(color);
            note.setVisibility(noteText.isEmpty() ? View.GONE : View.VISIBLE);

            String action = dose.status == DoseItem.Status.TAKEN
                    ? "누르면 복용 완료를 취소할 수 있어요"
                    : "누르면 복용 완료로 기록해요";
            body.setContentDescription(dose.drugName + ", " + detailText + ", " + dose.status.label
                    + (noteText.isEmpty() ? "" : ", " + noteText) + ". " + action);
            more.setContentDescription(context.getString(R.string.schedule_more_desc, dose.drugName, timeText));
        }

        private static String noteText(DoseItem dose, boolean notificationsBlocked) {
            if (dose.syncState == SyncState.FAILED) {
                return "저장 실패";
            }
            if (dose.syncState == SyncState.PENDING) {
                return "동기화 대기";
            }
            if (dose.status == DoseItem.Status.SCHEDULED && (notificationsBlocked || !dose.remindersEnabled)) {
                return "알림 꺼짐";
            }
            return "";
        }
    }
}
