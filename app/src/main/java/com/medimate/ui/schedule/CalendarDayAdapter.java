package com.medimate.ui.schedule;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.medimate.R;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Consumer;

/** 주간·월간 달력의 날짜 칸. 오늘은 테두리, 선택한 날짜는 채운 원으로 구분한다. */
class CalendarDayAdapter extends ListAdapter<CalendarDayAdapter.Day, CalendarDayAdapter.Holder> {

    static final class Day {
        final LocalDate date;
        final boolean today;
        final boolean selected;
        final boolean hasDose;
        /** 월간 달력에서 앞뒤 달의 날짜 */
        final boolean outsideMonth;
        final boolean showWeekday;

        Day(LocalDate date, boolean today, boolean selected, boolean hasDose, boolean outsideMonth,
            boolean showWeekday) {
            this.date = date;
            this.today = today;
            this.selected = selected;
            this.hasDose = hasDose;
            this.outsideMonth = outsideMonth;
            this.showWeekday = showWeekday;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Day)) {
                return false;
            }
            Day other = (Day) o;
            return date.equals(other.date) && today == other.today && selected == other.selected
                    && hasDose == other.hasDose && outsideMonth == other.outsideMonth
                    && showWeekday == other.showWeekday;
        }

        @Override
        public int hashCode() {
            return Objects.hash(date, today, selected, hasDose, outsideMonth, showWeekday);
        }
    }

    private static final DiffUtil.ItemCallback<Day> DIFF = new DiffUtil.ItemCallback<Day>() {
        @Override
        public boolean areItemsTheSame(@NonNull Day a, @NonNull Day b) {
            return a.date.equals(b.date);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Day a, @NonNull Day b) {
            return a.equals(b);
        }
    };

    private final Consumer<LocalDate> onClick;

    CalendarDayAdapter(Consumer<LocalDate> onClick) {
        super(DIFF);
        this.onClick = onClick;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_calendar_day, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position), onClick);
    }

    static class Holder extends RecyclerView.ViewHolder {
        private final TextView weekday;
        private final TextView number;
        private final View dot;
        private final ColorStateList defaultColor;

        Holder(View view) {
            super(view);
            weekday = view.findViewById(R.id.text_weekday);
            number = view.findViewById(R.id.text_day_number);
            dot = view.findViewById(R.id.view_dose_dot);
            defaultColor = number.getTextColors();
        }

        void bind(Day day, Consumer<LocalDate> onClick) {
            weekday.setVisibility(day.showWeekday ? View.VISIBLE : View.GONE);
            weekday.setText(DoseText.weekday(day.date));
            number.setText(String.valueOf(day.date.getDayOfMonth()));
            number.setTypeface(null, day.today || day.selected ? Typeface.BOLD : Typeface.NORMAL);
            number.setTextColor(day.selected
                    ? ColorStateList.valueOf(ContextCompat.getColor(itemView.getContext(), R.color.calendar_on_selected))
                    : defaultColor);
            number.setBackground(circle(day));
            itemView.setAlpha(day.outsideMonth ? 0.6f : 1f);
            dot.setVisibility(day.hasDose ? View.VISIBLE : View.INVISIBLE);
            itemView.setContentDescription(description(day));
            itemView.setOnClickListener(v -> onClick.accept(day.date));
        }

        private GradientDrawable circle(Day day) {
            if (!day.today && !day.selected) {
                return null;
            }
            float density = itemView.getResources().getDisplayMetrics().density;
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            if (day.selected) {
                circle.setColor(ContextCompat.getColor(itemView.getContext(), R.color.calendar_selected));
            }
            if (day.today) {
                circle.setStroke((int) (3 * density),
                        ContextCompat.getColor(itemView.getContext(), R.color.calendar_today_ring));
            }
            return circle;
        }

        private static String description(Day day) {
            StringBuilder text = new StringBuilder(DoseText.date(day.date));
            if (day.today) {
                text.append(", 오늘");
            }
            if (day.selected) {
                text.append(", 선택됨");
            }
            if (day.hasDose) {
                text.append(", 복약 일정 있음");
            }
            return text.toString();
        }
    }
}
