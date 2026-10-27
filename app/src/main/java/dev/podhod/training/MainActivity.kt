package dev.podhod.training

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import dev.podhod.training.ui.PodhodRoot
import dev.podhod.training.ui.PodhodTheme

class MainActivity : ComponentActivity() {
    private var activeModel: TrainingViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val version by (application as PodhodApplication).workspaceVersion.collectAsStateWithLifecycle()
            val model = remember(version) {
                if (activeModel != null) viewModelStore.clear()
                ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(application))["training-$version", TrainingViewModel::class.java]
            }
            SideEffect { activeModel = model }
            key(version) { PodhodTheme { PodhodRoot(model) } }
        }
    }
    override fun onStop() { activeModel?.reconcile(); super.onStop() }
}
