package com.santamota.reminder.di

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.room.Room
import com.santamota.reminder.data.alarm.AlarmScheduler
import com.santamota.reminder.data.alarm.AndroidAlarmScheduler
import com.santamota.reminder.data.db.AppDatabase
import com.santamota.reminder.data.db.ChatDao
import com.santamota.reminder.data.db.ReminderDao
import com.santamota.reminder.engine.PreferenceLearner
import com.santamota.reminder.engine.PreferenceProfile
import com.santamota.reminder.engine.ReminderEngine
import com.santamota.reminder.ml.MediaPipeGemmaAdapter
import com.santamota.reminder.nlu.LlmAdapter
import com.santamota.reminder.nlu.RuleBasedParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "reminder_prefs")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "reminders.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun reminderDao(db: AppDatabase): ReminderDao = db.reminderDao()
    @Provides fun chatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides @Singleton
    fun scheduler(@ApplicationContext ctx: Context): AlarmScheduler =
        AndroidAlarmScheduler(ctx)

    @Provides @Singleton
    fun llm(@ApplicationContext ctx: Context): LlmAdapter =
        MediaPipeGemmaAdapter(ctx)

    @Provides @Singleton
    fun parser(clock: Clock): RuleBasedParser = RuleBasedParser(clock)

    @Provides @Singleton
    fun preferenceStore(@ApplicationContext ctx: Context): PreferenceLearner.Store {
        val json = Json { ignoreUnknownKeys = true }
        val key = stringPreferencesKey("profile_json")
        return object : PreferenceLearner.Store {
            override suspend fun read(): PreferenceProfile {
                val text = ctx.appDataStore.data
                    .map { it[key] ?: "" }
                    .first()
                return if (text.isBlank()) PreferenceProfile.EMPTY
                else runCatching { json.decodeFromString(PreferenceProfile.serializer(), text) }
                    .getOrDefault(PreferenceProfile.EMPTY)
            }
            override suspend fun write(profile: PreferenceProfile) {
                val text = json.encodeToString(PreferenceProfile.serializer(), profile)
                ctx.appDataStore.edit { it[key] = text }
            }
        }
    }

    @Provides @Singleton
    fun learner(clock: Clock, store: PreferenceLearner.Store): PreferenceLearner =
        PreferenceLearner(clock, store)

    @Provides @Singleton
    fun engine(
        clock: Clock,
        parser: RuleBasedParser,
        llm: LlmAdapter,
        reminderDao: ReminderDao,
        chatDao: ChatDao,
        scheduler: AlarmScheduler,
        learner: PreferenceLearner,
    ): ReminderEngine = ReminderEngine(
        clock, parser, llm, reminderDao, chatDao, scheduler, learner,
    )
}
