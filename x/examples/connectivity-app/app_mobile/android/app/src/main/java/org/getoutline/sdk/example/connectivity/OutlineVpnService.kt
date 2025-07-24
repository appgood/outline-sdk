package org.getoutline.sdk.example.connectivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import shared_backend.Shared_backend

/**
 * 本地代理服务 - 不使用 VPN 接口，而是创建本地 HTTP 代理
 * 这是更实用的方案，应用可以配置使用这个代理来访问网络
 */
class OutlineVpnService : Service() {

    companion object {
        const val ACTION_START_PROXY = "start_proxy"
        const val ACTION_STOP_PROXY = "stop_proxy"
        private const val TAG = "OutlineVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "outline_proxy_channel"
    }

    private var isRunning = false
    private var proxyAddress: String? = null
    private var accessKey: String? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): OutlineVpnService = this@OutlineVpnService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROXY -> {
                accessKey = intent.getStringExtra("ACCESS_KEY")
                
                if (accessKey != null) {
                    startProxy()
                } else {
                    Log.e(TAG, "Missing access key")
                    stopSelf()
                }
            }
            ACTION_STOP_PROXY -> {
                stopProxy()
            }
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (isRunning) {
            Log.w(TAG, "Proxy is already running")
            return
        }

        serviceScope.launch {
            try {
                // 创建代理请求
                val proxyRequest = ProxyRequest(
                    transportConfig = accessKey!!,
                    localAddress = "127.0.0.1:0"  // 让系统自动分配端口
                )

                val frontendRequest = FrontendRequest(
                    resourceName = "CreateProxy",
                    parameters = Json.encodeToString(proxyRequest)
                )

                val requestJson = Json.encodeToString(frontendRequest)
                val responseBytes = Shared_backend.handleRequest(requestJson.toByteArray())
                val responseJson = responseBytes.toString(Charsets.UTF_8)
                val response = Json.decodeFromString<FrontendResponse>(responseJson)

                if (response.error.isNotEmpty()) {
                    Log.e(TAG, "Failed to create proxy: ${response.error}")
                    stopProxy()
                    return@launch
                }

                // 解析代理地址
                val proxyResponse = Json.decodeFromString<ProxyResponse>(response.body)
                proxyAddress = proxyResponse.address

                isRunning = true
                
                // 启动前台服务
                startForeground(NOTIFICATION_ID, createNotification(
                    "代理已启动", 
                    "本地代理地址: ${proxyAddress}\n\n使用说明:\n1. 在浏览器或应用中配置 HTTP 代理\n2. 代理地址: $proxyAddress\n3. 或者等待系统级代理支持"
                ))
                
                Log.i(TAG, "Proxy started successfully at: $proxyAddress")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                stopProxy()
            }
        }
    }

    private fun stopProxy() {
        if (!isRunning) {
            return
        }

        isRunning = false
        proxyAddress = null
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.i(TAG, "Proxy stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Outline 代理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Outline 本地代理服务状态"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, ConnectVPNActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("点击查看详情")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_delete,
                "停止代理",
                PendingIntent.getService(
                    this, 0,
                    Intent(this, OutlineVpnService::class.java).apply {
                        action = ACTION_STOP_PROXY
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    fun getProxyAddress(): String? = proxyAddress
    fun isProxyRunning(): Boolean = isRunning

    override fun onDestroy() {
        super.onDestroy()
        stopProxy()
        serviceScope.cancel()
    }
} 