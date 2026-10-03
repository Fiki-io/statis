package com.statis.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.statis.app.databinding.ActivityMainBinding
import com.statis.app.engine.EngineManager
import com.statis.app.model.NetworkMode
import com.statis.app.service.LatencyEngineService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engineManager: EngineManager

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engineManager = EngineManager.getInstance(applicationContext)

        checkPermissions()
        setupListeners()
        observeEngineState()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnModeWifi.setOnClickListener {
            if (!engineManager.isRunning.value) {
                engineManager.setMode(NetworkMode.WIFI)
                updateModeUi(NetworkMode.WIFI)
            }
        }

        binding.btnModeCellular.setOnClickListener {
            if (!engineManager.isRunning.value) {
                engineManager.setMode(NetworkMode.CELLULAR)
                updateModeUi(NetworkMode.CELLULAR)
            }
        }

        binding.btnToggleEngine.setOnClickListener {
            if (engineManager.isRunning.value) {
                LatencyEngineService.stopService(this)
            } else {
                LatencyEngineService.startService(this)
            }
        }
    }

    private fun updateModeUi(mode: NetworkMode) {
        if (mode == NetworkMode.WIFI) {
            binding.btnModeWifi.setBackgroundResource(R.drawable.bg_card_selected)
            binding.tvModeWifi.setTextColor(ContextCompat.getColor(this, R.color.text_high_contrast))

            binding.btnModeCellular.setBackgroundResource(R.drawable.bg_card)
            binding.tvModeCellular.setTextColor(ContextCompat.getColor(this, R.color.text_medium_contrast))
        } else {
            binding.btnModeCellular.setBackgroundResource(R.drawable.bg_card_selected)
            binding.tvModeCellular.setTextColor(ContextCompat.getColor(this, R.color.text_high_contrast))

            binding.btnModeWifi.setBackgroundResource(R.drawable.bg_card)
            binding.tvModeWifi.setTextColor(ContextCompat.getColor(this, R.color.text_medium_contrast))
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engineManager.isRunning.collectLatest { running ->
                if (running) {
                    binding.tvEngineStatus.text = getString(R.string.status_active)
                    binding.tvEngineStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
                    binding.btnToggleEngine.setBackgroundResource(R.drawable.bg_button_stop)
                    binding.tvButtonAction.text = getString(R.string.action_stop)

                    binding.btnModeWifi.isClickable = false
                    binding.btnModeCellular.isClickable = false
                } else {
                    binding.tvEngineStatus.text = getString(R.string.status_inactive)
                    binding.tvEngineStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                    binding.btnToggleEngine.setBackgroundResource(R.drawable.bg_button)
                    binding.tvButtonAction.text = getString(R.string.action_start)

                    binding.btnModeWifi.isClickable = true
                    binding.btnModeCellular.isClickable = true
                    updateModeUi(engineManager.networkMode.value)
                }
            }
        }

        lifecycleScope.launch {
            engineManager.networkMode.collectLatest { mode ->
                updateModeUi(mode)
            }
        }

        lifecycleScope.launch {
            engineManager.pingMs.collectLatest { ping ->
                if (ping > 0.0 && engineManager.isRunning.value) {
                    binding.tvPingValue.text = String.format(Locale.US, "%.1f ms", ping)
                } else {
                    binding.tvPingValue.text = getString(R.string.metric_ping_default)
                }
            }
        }

        lifecycleScope.launch {
            engineManager.jitterMs.collectLatest { jitter ->
                if (jitter > 0.0 && engineManager.isRunning.value) {
                    binding.tvJitterValue.text = String.format(Locale.US, "%.1f ms", jitter)
                } else {
                    binding.tvJitterValue.text = getString(R.string.metric_jitter_default)
                }
            }
        }

        lifecycleScope.launch {
            engineManager.hardwareLockActive.collectLatest { active ->
                if (active) {
                    binding.tvParamHardwareLock.text = getString(R.string.state_on)
                    binding.tvParamHardwareLock.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
                } else {
                    binding.tvParamHardwareLock.text = getString(R.string.state_off)
                    binding.tvParamHardwareLock.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                }
            }
        }

        lifecycleScope.launch {
            engineManager.acVoActive.collectLatest { active ->
                if (active) {
                    binding.tvParamAcVo.text = getString(R.string.state_on)
                    binding.tvParamAcVo.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
                } else {
                    binding.tvParamAcVo.text = getString(R.string.state_off)
                    binding.tvParamAcVo.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                }
            }
        }

        lifecycleScope.launch {
            engineManager.isRunning.collectLatest { running ->
                val isCellular = engineManager.networkMode.value == NetworkMode.CELLULAR
                if (running && isCellular) {
                    binding.tvParamAntiDrx.text = getString(R.string.state_on)
                    binding.tvParamAntiDrx.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_active))
                } else {
                    binding.tvParamAntiDrx.text = getString(R.string.state_off)
                    binding.tvParamAntiDrx.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_dim))
                }
            }
        }
    }
}
