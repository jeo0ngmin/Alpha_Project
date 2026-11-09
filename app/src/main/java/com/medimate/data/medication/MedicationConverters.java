package com.medimate.data.medication;

import androidx.room.TypeConverter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.medimate.medication.TimeSlot;

import java.util.ArrayList;
import java.util.List;

/** 요일 목록과 시간 슬롯을 Room 문자열 칸에 저장한다. */
public final class MedicationConverters {

    private static final Gson GSON = new Gson();

    private MedicationConverters() {
    }

    @TypeConverter
    public static String daysToText(List<Integer> days) {
        StringBuilder text = new StringBuilder();
        for (int day : days) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(day);
        }
        return text.toString();
    }

    @TypeConverter
    public static List<Integer> textToDays(String text) {
        List<Integer> days = new ArrayList<>();
        if (!text.isEmpty()) {
            for (String part : text.split(",")) {
                days.add(Integer.parseInt(part));
            }
        }
        return days;
    }

    @TypeConverter
    public static String slotsToText(List<TimeSlot> slots) {
        return GSON.toJson(slots);
    }

    @TypeConverter
    public static List<TimeSlot> textToSlots(String text) {
        return GSON.fromJson(text, new TypeToken<ArrayList<TimeSlot>>() { }.getType());
    }
}
