package org.getoutline.sdk.example.connectivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import shared_backend.Shared_backend

class OutlineVpnService : VpnService() {

    companion object {
        private const val TAG = "OutlineVpnService"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "outline_vpn_channel"

        const val ACTION_CONNECT = "org.getoutline.sdk.CONNECT"
        const val ACTION_DISCONNECT = "org.getoutline.sdk.DISCONNECT"

        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "26.26.26.1"  // 使用不冲突的地址
        private const val VPN_ROUTE = "0.0.0.0"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var deviceId: String? = null
    private var accessKey: String? = null
    private var isRunning = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 路由循环检测
    private var lastPacketCount = 0
    private var loopDetectionStartTime = 0L
    private val LOOP_DETECTION_INTERVAL = 10000L // 10秒检测间隔
    private val MAX_PACKETS_PER_INTERVAL = 1000   // 异常包数量阈值

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "OutlineVpnService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_CONNECT -> {
                val accessKey = intent.getStringExtra("ACCESS_KEY")
                val deviceId = intent.getStringExtra("DEVICE_ID")

                if (accessKey != null && deviceId != null) {
                    startVPN(accessKey, deviceId)
                } else {
                    Log.e(TAG, "Missing access key or device ID")
                    stopVPN()
                }
            }
            ACTION_DISCONNECT -> {
                stopVPN()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Outline VPN 服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Outline VPN 连接状态"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(title: String, message: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startVPN(accessKey: String, deviceId: String) {
        if (isRunning) {
            Log.w(TAG, "VPN is already running")
            return
        }

        this.accessKey = accessKey
        this.deviceId = deviceId

        Log.i(TAG, "Starting VPN with device: $deviceId")

        serviceScope.launch {
            try {
                // 建立 VPN 接口
                Log.i(TAG, "Building VPN interface...")
                val builder = Builder()
                    .setMtu(VPN_MTU)
                    .addAddress(VPN_ADDRESS, 32)  // 使用 /32 点对点连接
                    .addRoute("0.0.0.0", 0)       // 路由所有流量
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")
                    .addDnsServer("1.1.1.1")      // 添加 Cloudflare DNS
                    .setSession("Outline VPN")
                    .setBlocking(false)           // 非阻塞模式

                // 可配置的应用排除列表
                val excludedApps = getExcludedApps()
                for (appPackage in excludedApps) {
                    try {
                        builder.addDisallowedApplication(appPackage)
                        Log.i(TAG, "Excluded app from VPN: $appPackage")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to exclude app $appPackage: ${e.message}")
                    }
                }

                if (excludedApps.isEmpty()) {
                    Log.i(TAG, "VPN will route ALL traffic including current app")
                } else {
                    Log.i(TAG, "VPN will route traffic except excluded apps: $excludedApps")
                }


                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    stopVPN()
                    return@launch
                }

                isRunning = true

                // 启动前台服务通知
                startForeground(NOTIFICATION_ID, createNotification(
                    "Outline VPN 已连接",
                    "正在通过 Outline 服务器路由流量\n设备 ID: $deviceId"
                ))

                Log.i(TAG, "VPN interface established successfully")

                // 启动数据转发
                startPacketForwarding()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                stopVPN()
            }
        }
    }

    private suspend fun startPacketForwarding() {
        val tunInterface = vpnInterface ?: return
        val currentDeviceId = deviceId ?: return

        Log.i(TAG, "Starting packet forwarding for device: $currentDeviceId")

        try {
            // 创建读写流
            val inputStream = ParcelFileDescriptor.AutoCloseInputStream(tunInterface)
            val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(tunInterface)

            // 启动双向数据转发
            val job1 = serviceScope.async {
                // 从 TUN 读取，发送到 lwIP 设备
                forwardTunToDevice(inputStream, currentDeviceId)
            }

            val job2 = serviceScope.async {
                // 从 lwIP 设备读取，写入 TUN
                forwardDeviceToTun(outputStream, currentDeviceId)
            }

            // 等待任一方向完成或出错
            supervisorScope {
                try {
                    select<Unit> {
                        job1.onJoin {
                            Log.w(TAG, "TUN->Device forwarding completed")
                            job2.cancel()
                        }
                        job2.onJoin {
                            Log.w(TAG, "Device->TUN forwarding completed")
                            job1.cancel()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Packet forwarding error", e)
                    job1.cancel()
                    job2.cancel()
                    throw e
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Packet forwarding error", e)
        } finally {
            Log.i(TAG, "Packet forwarding stopped")
            stopVPN()
        }
    }

    private suspend fun forwardTunToDevice(inputStream: ParcelFileDescriptor.AutoCloseInputStream, deviceId: String) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(32767)
            var totalPackets = 0
            var totalBytes = 0L

            try {
                Log.i(TAG, "Starting TUN -> Device forwarding for device: $deviceId")
                while (isRunning && serviceScope.isActive) {
                    val length = inputStream.read(buffer)
                    if (length > 0) {
                        totalPackets++
                        totalBytes += length

                        // 详细的包分析
                        val data = buffer.copyOf(length)

                        // 解析 IP 包头信息
                        if (length >= 20) {
                            val version = (data[0].toInt() and 0xF0) shr 4
                            val protocol = data[9].toUByte().toInt()
                            val srcIp = "${data[12].toUByte()}.${data[13].toUByte()}.${data[14].toUByte()}.${data[15].toUByte()}"
                            val dstIp = "${data[16].toUByte()}.${data[17].toUByte()}.${data[18].toUByte()}.${data[19].toUByte()}"

                            Log.d(TAG, "TUN->Device packet #$totalPackets: IPv$version, Protocol=$protocol, $srcIp -> $dstIp, Length=$length")
                        }

                        // 记录包信息用于调试
                        if (totalPackets % 100 == 0) {
                            Log.d(TAG, "TUN->Device: $totalPackets packets, $totalBytes bytes")

                            // 路由循环检测
                            checkForRoutingLoop(totalPackets)
                        }

                        // 将数据写入 lwIP 设备
                        val written = Shared_backend.writeToVPNDevice(deviceId, data)

                        if (written.toLong() != length.toLong()) {
                            Log.w(TAG, "Partial write to VPN device: $written/$length")
                        }
                    } else if (length == -1) {
                        Log.i(TAG, "TUN input stream ended")
                        break
                    } else {
                        // length == 0, 短暂等待
                        delay(1)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding TUN to device", e)
            } finally {
                Log.i(TAG, "TUN->Device forwarding stopped. Total: $totalPackets packets, $totalBytes bytes")
                try {
                    inputStream.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing input stream", e)
                }
            }
        }
    }

    private suspend fun forwardDeviceToTun(outputStream: ParcelFileDescriptor.AutoCloseOutputStream, deviceId: String) {
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(32767)
            var totalPackets = 0
            var totalBytes = 0L

            try {
                Log.i(TAG, "Starting Device -> TUN forwarding for device: $deviceId")
                while (isRunning && serviceScope.isActive) {
                    // 从 lwIP 设备读取数据
                    val length = Shared_backend.readFromVPNDevice(deviceId, buffer)

                    if (length > 0L) {
                        totalPackets++
                        totalBytes += length

                        // 详细的包分析
                        if (length >= 20) {
                            val version = (buffer[0].toInt() and 0xF0) shr 4
                            val protocol = buffer[9].toUByte().toInt()
                            val srcIp = "${buffer[12].toUByte()}.${buffer[13].toUByte()}.${buffer[14].toUByte()}.${buffer[15].toUByte()}"
                            val dstIp = "${buffer[16].toUByte()}.${buffer[17].toUByte()}.${buffer[18].toUByte()}.${buffer[19].toUByte()}"

                            Log.d(TAG, "Device->TUN packet #$totalPackets: IPv$version, Protocol=$protocol, $srcIp -> $dstIp, Length=$length")
                        }

                        // 记录包信息用于调试
                        if (totalPackets % 100 == 0) {
                            Log.d(TAG, "Device->TUN: $totalPackets packets, $totalBytes bytes")
                        }

                        // 写入 TUN 设备
                        outputStream.write(buffer, 0, length.toInt())
                        outputStream.flush()
                    } else if (length == -1L) {
                        Log.i(TAG, "VPN device input ended")
                        break
                    }

                    // 小延迟避免过度 CPU 使用
                    if (length == 0L) {
                        delay(1)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding device to TUN", e)
            } finally {
                Log.i(TAG, "Device->TUN forwarding stopped. Total: $totalPackets packets, $totalBytes bytes")
                try {
                    outputStream.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing output stream", e)
                }
            }
        }
    }

    private fun stopVPN() {
        Log.i(TAG, "Stopping VPN")

        isRunning = false
        serviceScope.coroutineContext.cancelChildren()

        vpnInterface?.close()
        vpnInterface = null

        deviceId = null
        accessKey = null

        stopForeground(true)
        stopSelf()

        Log.i(TAG, "VPN stopped")
    }

    /**
     * 获取需要排除在 VPN 之外的应用列表
     * 策略：允许当前应用走 VPN，但排除可能导致路由循环的系统服务
     */
    private fun getExcludedApps(): List<String> {
        val excludedApps = mutableListOf<String>()

        // 排除可能影响 VPN 稳定性的系统应用
        excludedApps.addAll(listOf(
            "com.android.systemui",           // 系统UI - 避免影响VPN通知
            "com.android.phone",              // 电话应用 - 保证通话质量
        ))

        // 可选：如果检测到路由循环问题，可以临时排除当前应用
        // 通过配置或运行时检测来决定
        if (shouldExcludeCurrentApp()) {
            excludedApps.add(packageName)
            Log.w(TAG, "Excluding current app to prevent routing loops")
        } else {
            Log.i(TAG, "Current app will use VPN routing")
        }

        return excludedApps
    }

    /**
     * 检测是否应该排除当前应用
     * 可以基于配置、运行时检测或用户设置
     */
    private fun shouldExcludeCurrentApp(): Boolean {
        // 方案 1: 基于配置
        val prefs = getSharedPreferences("vpn_config", Context.MODE_PRIVATE)
        val forceExcludeSelf = prefs.getBoolean("exclude_current_app", false)

        if (forceExcludeSelf) {
            return true
        }

        // 方案 2: 自动检测（暂时返回 false，让当前应用走 VPN）
        // 如果后续发现路由循环问题，可以改为 true
        return false

		 // 方案 3: 基于网络环境检测
         // 可以检测当前网络环境，在某些情况下排除自己
         // return isComplexNetworkEnvironment()
     }

     /**
      * 检测路由循环
      * 如果在短时间内有大量数据包通过，可能存在路由循环
      */
     private fun checkForRoutingLoop(currentPacketCount: Int) {
         val currentTime = System.currentTimeMillis()

         if (loopDetectionStartTime == 0L) {
             loopDetectionStartTime = currentTime
             lastPacketCount = currentPacketCount
             return
         }

         val timeDiff = currentTime - loopDetectionStartTime
         if (timeDiff >= LOOP_DETECTION_INTERVAL) {
             val packetDiff = currentPacketCount - lastPacketCount
             val packetsPerSecond = packetDiff * 1000 / timeDiff

             Log.d(TAG, "Traffic analysis: $packetDiff packets in ${timeDiff}ms (${packetsPerSecond} pps)")

             if (packetDiff > MAX_PACKETS_PER_INTERVAL) {
                 Log.w(TAG, "Potential routing loop detected: $packetDiff packets in ${timeDiff}ms")
                 // 可以在这里触发保护措施，比如暂时排除当前应用
                 handlePotentialRoutingLoop()
             }

             // 重置检测
             loopDetectionStartTime = currentTime
             lastPacketCount = currentPacketCount
         }
     }

     /**
      * 处理可能的路由循环
      */
     private fun handlePotentialRoutingLoop() {
         Log.w(TAG, "Implementing routing loop protection...")

         // 选项1：记录到配置中，下次启动时排除当前应用
         val prefs = getSharedPreferences("vpn_config", Context.MODE_PRIVATE)
         prefs.edit().putBoolean("exclude_current_app", true).apply()

         // 选项2：显示通知告知用户
         // showRoutingLoopNotification()

         // 选项3：暂时不采取行动，只记录日志供分析
         Log.i(TAG, "Routing loop protection: logged for analysis")
     }

    override fun onDestroy() {
        super.onDestroy()
        stopVPN()
        serviceScope.cancel()
        Log.i(TAG, "OutlineVpnService destroyed")
    }
} 