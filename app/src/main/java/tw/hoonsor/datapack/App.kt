package tw.hoonsor.datapack

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tw.hoonsor.datapack.ai.Llm
import tw.hoonsor.datapack.capture.CaptureWorker
import tw.hoonsor.datapack.data.KeyVault
import tw.hoonsor.datapack.data.PackDb
import tw.hoonsor.datapack.data.Repository
import tw.hoonsor.datapack.data.Settings
import tw.hoonsor.datapack.log.AppLog
import tw.hoonsor.datapack.query.QueryEngine
import tw.hoonsor.datapack.sync.Supabase
import tw.hoonsor.datapack.sync.SyncEngine
import tw.hoonsor.datapack.sync.SyncWorker

/** 全 APP 共用的單例 */
object Graph {
    lateinit var app: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var api: Supabase
        private set
    lateinit var db: PackDb
        private set
    lateinit var repo: Repository
        private set
    lateinit var sync: SyncEngine
        private set
    lateinit var llm: Llm
        private set
    lateinit var query: QueryEngine
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        app = context.applicationContext
        AppLog.init(app)
        settings = Settings(app)
        api = Supabase(KeyVault(app))
        llm = Llm(settings, KeyVault(app, "ai_vault"))
        db = PackDb.create(app)
        repo = Repository(db) { SyncWorker.syncSoon(app) }
        sync = SyncEngine(api, db.dao(), repo, settings) {}
        query = QueryEngine(app)
    }

    suspend fun signOut() {
        api.signOut()
        repo.wipeLocal()
        settings.clearSync()
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        SyncWorker.schedulePeriodic(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Graph.scope.launch { Graph.sync.sync() }
                CaptureWorker.start(this@App)
            }
        })
    }
}
