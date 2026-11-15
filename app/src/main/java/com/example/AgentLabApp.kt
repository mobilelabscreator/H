package com.example

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentLabApp : Application() {
    lateinit var container: AgentLabContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AgentLabContainer(this)
        appScope.launch {
            container.skillFileManager.ensureStarterSkillsSeeded()
            container.autonomousAgentEngine.ensureStarterEvalQuestionsSeeded()
        }
    }
}
