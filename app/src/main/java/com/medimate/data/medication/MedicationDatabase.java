package com.medimate.data.medication;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.medimate.medication.IntakeRecord;
import com.medimate.medication.MedicationSchedule;
import com.medimate.medication.ScheduledAlarm;

/** 기기 안의 복약 데이터. 네트워크 없이도 알림과 복용 체크가 되도록 이곳을 먼저 읽고 쓴다 (README 9-6). */
@Database(entities = {MedicationSchedule.class, IntakeRecord.class, ScheduledAlarm.class},
        version = 1, exportSchema = false)
@TypeConverters(MedicationConverters.class)
public abstract class MedicationDatabase extends RoomDatabase {

    /** 자동 백업에서 제외한다 (res/xml/backup_rules.xml, data_extraction_rules.xml) */
    private static final String NAME = "medication.db";

    private static volatile MedicationDatabase instance;

    public abstract MedicationDao dao();

    public static MedicationDatabase get(Context context) {
        if (instance == null) {
            synchronized (MedicationDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            MedicationDatabase.class, NAME).build();
                }
            }
        }
        return instance;
    }
}
