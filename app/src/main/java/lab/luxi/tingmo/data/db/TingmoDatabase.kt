package lab.luxi.tingmo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CorrectionRuleEntity::class,
        TranscriptHistoryEntity::class,
        ModelInstallEntity::class,
        PromptEntity::class,
        PendingCorrectionEntity::class,
        RecordingEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class TingmoDatabase : RoomDatabase() {
    abstract fun correctionDao(): CorrectionDao
    abstract fun historyDao(): HistoryDao
    abstract fun modelInstallDao(): ModelInstallDao
    abstract fun promptDao(): PromptDao
    abstract fun pendingCorrectionDao(): PendingCorrectionDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile private var instance: TingmoDatabase? = null

        fun get(context: Context): TingmoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TingmoDatabase::class.java,
                    "tingmo.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
