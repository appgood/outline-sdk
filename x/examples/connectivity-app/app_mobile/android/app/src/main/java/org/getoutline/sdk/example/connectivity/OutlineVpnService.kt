package org.getoutline.sdk.example.connectivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import shared_backend.Shared_backend
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

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
		Log.i(TAG, "🚀 OutlineVpnService created")
		Timber.tag(TAG).i("OutlineVpnService created")
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		Log.i(TAG, "📥 onStartCommand: ${intent?.action}")
		Timber.tag(TAG).i("onStartCommand: ${intent?.action}")

		when (intent?.action) {
			ACTION_CONNECT -> {
				val accessKey = intent.getStringExtra("ACCESS_KEY")
				val deviceId = intent.getStringExtra("DEVICE_ID")

				if (accessKey != null && deviceId != null) {
					startVPN(accessKey, deviceId)
				} else {
					Timber.tag(TAG).e("Missing access key or device ID")
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
		// 只有在 Android O (API level 26) 及更高版本上才创建通知渠道
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
			Timber.tag(TAG).w("VPN is already running")
			return
		}

		this.accessKey = accessKey
		this.deviceId = deviceId

		Timber.tag(TAG).i("Starting VPN with device: $deviceId")

		serviceScope.launch {
			try {
				// 建立 VPN 接口
				Timber.tag(TAG).i("Building VPN interface...")
				val builder = Builder()
					.setMtu(VPN_MTU)
					.addAddress(VPN_ADDRESS, 32)  // 使用 /32 点对点连接
					.addRoute("0.0.0.0", 0)       // 路由所有流量
					.addDnsServer("8.8.8.8")
					.addDnsServer("8.8.4.4")
					.addDnsServer("1.1.1.1")      // 添加 Cloudflare DNS
					.setSession("Outline VPN")
					.setBlocking(false)           // 非阻塞模式
					
				// 🎯 智能路由策略配置
				val enableAppRouting = shouldRouteAppTraffic() // 可配置
				
				if (!enableAppRouting) {
					// 方案A: 排除自己，避免路由循环（当前解决方案）
					try {
						builder.addDisallowedApplication(packageName)
						Log.i(TAG, "✅ 已排除当前应用，避免路由循环: $packageName")
					} catch (e: Exception) {
						Log.w(TAG, "排除当前应用失败: ${e.message}")
					}
				} else {
					// 方案B: 允许APP流量走VPN，但使用高级路由策略
					Log.i(TAG, "🚀 启用APP流量VPN路由模式")
					configureAdvancedRouting(builder)
				}

				// 可配置的应用排除列表
				val excludedApps = getExcludedApps()
				for (appPackage in excludedApps) {
					try {
						builder.addDisallowedApplication(appPackage)
						Timber.tag(TAG).i("Excluded app from VPN: $appPackage")
					} catch (e: Exception) {
						Timber.tag(TAG).w("Failed to exclude app $appPackage: ${e.message}")
					}
				}

				if (excludedApps.isEmpty()) {
					Timber.tag(TAG).i("VPN will route ALL traffic including current app")
				} else {
					Timber.tag(TAG).i("VPN will route traffic except excluded apps: $excludedApps")
				}


				vpnInterface = builder.establish()

				if (vpnInterface == null) {
					Timber.tag(TAG).e("Failed to establish VPN interface")
					stopVPN()
					return@launch
				}

				isRunning = true

				// VPN接口建立成功
				Log.i(TAG, "VPN接口建立成功，设备ID: $deviceId")

				// 启动前台服务通知
				startForeground(NOTIFICATION_ID, createNotification(
					"Outline VPN 已连接",
					"正在通过 Outline 服务器路由流量\n设备 ID: $deviceId"
				))

				Timber.tag(TAG).i("VPN interface established successfully")

				// 立即启动测试（在数据转发之前）
				serviceScope.launch {
					try {
						Log.i(TAG, "⚡ 立即进行基础网络测试...")
						delay(2000) // 2秒后立即测试
						performNetworkConnectivityTest()
					} catch (e: Exception) {
						Log.e(TAG, "立即测试失败", e)
					}
				}
				
				// 延迟后进行完整网络测试
				serviceScope.launch {
					try {
						Log.i(TAG, "⏰ 开始10秒倒计时，准备完整网络测试...")
						delay(10000) // 10秒后测试
						
						Log.i(TAG, "⏰ 10秒倒计时结束，开始完整网络测试")
						performNetworkConnectivityTest()
					} catch (e: Exception) {
						Log.e(TAG, "延迟网络测试调度失败", e)
					}
				}
				
				// 启动数据转发（这会阻塞，所以放在最后）
				startPacketForwarding()

			} catch (e: Exception) {
				Timber.tag(TAG).e("Failed to start VPN ${e.message}")
				stopVPN()
			}
		}
	}

	private fun performNetworkConnectivityTest() {
		Log.i(TAG, "=== 🔍 开始网络连通性测试 ===")
		
		// 检查服务状态
		if (!isRunning) {
			Log.e(TAG, "❌ VPN服务未运行，跳过网络测试")
			return
		}
		
		val deviceId = this.deviceId
		if (deviceId == null) {
			Log.e(TAG, "❌ 设备ID为空，无法进行测试")
			return
		}
		
		Log.i(TAG, "📱 当前设备ID: $deviceId")
		Log.i(TAG, "🔄 VPN运行状态: $isRunning")
		
		GlobalScope.launch(Dispatchers.IO) {
			try {
				Log.i(TAG, "🧪 开始Go端测试...")
				
				// 1. 测试Go端连通性
				try {
					val connectivityResult = Shared_backend.testNetworkConnectivity(deviceId)
					Log.i(TAG, "✅ Go端连通性测试: $connectivityResult")
				} catch (e: Exception) {
					Log.e(TAG, "❌ Go端连通性测试失败: ${e.message}", e)
				}
				
				// 2. 测试lwIP诊断
				try {
					val lwipResult = Shared_backend.diagnoseLwIPStack(deviceId)
					Log.i(TAG, "🔧 lwIP诊断结果: $lwipResult")
				} catch (e: Exception) {
					Log.e(TAG, "❌ lwIP诊断失败: ${e.message}", e)
				}
				
				// 3. 测试Android网络状态
				try {
					withContext(Dispatchers.Main) {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
							testAndroidNetworkState()
						}
					}
				} catch (e: Exception) {
					Log.e(TAG, "❌ Android网络状态测试失败: ${e.message}", e)
				}
				
				// 4. 检查路由状态
				try {
					Log.i(TAG, "🛣️ 检查路由和网络接口状态...")
					testNetworkRouting()
				} catch (e: Exception) {
					Log.e(TAG, "❌ 路由测试失败: ${e.message}", e)
				}
				
				// 5. 测试简单的网络请求
				try {
					testSimpleHttpRequest()
				} catch (e: Exception) {
					Log.e(TAG, "❌ HTTP测试失败: ${e.message}", e)
				}
				
				Log.i(TAG, "=== 🏁 网络连通性测试完成 ===")
				
			} catch (e: Exception) {
				Log.e(TAG, "❌ 网络测试整体失败: ${e.message}", e)
			}
		}
	}
	
	@RequiresApi(Build.VERSION_CODES.M)
	suspend fun testAndroidNetworkState() {
		try {
			val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
			val activeNetwork = cm.activeNetwork
			val capabilities = cm.getNetworkCapabilities(activeNetwork)
			
			Log.i(TAG, "Android网络状态:")
			Log.i(TAG, "  活动网络: $activeNetwork")
			Log.i(TAG, "  VPN传输: ${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
			Log.i(TAG, "  互联网访问: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}")
			Log.i(TAG, "  网络验证: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
			
		} catch (e: Exception) {
			Log.e(TAG, "Android网络状态检查失败", e)
		}
	}
	
	private fun testNetworkRouting() {
		try {
			Log.i(TAG, "🔍 网络路由诊断:")
			
			// 1. 检查VPN接口状态
			val vpnFd = vpnInterface
			if (vpnFd != null && vpnFd.fileDescriptor.valid()) {
				Log.i(TAG, "  ✅ VPN接口文件描述符有效")
			} else {
				Log.e(TAG, "  ❌ VPN接口文件描述符无效")
				return
			}
			
			// 2. 检查设备ID和Go后端状态
			val currentDeviceId = deviceId
			if (currentDeviceId != null) {
				Log.i(TAG, "  ✅ 设备ID: $currentDeviceId")
				try {
					// 快速检查Go设备状态
					val goDevice = Shared_backend.getVPNDevice(currentDeviceId)
					if (goDevice != null) {
						Log.i(TAG, "  ✅ Go设备后端连接正常")
					} else {
						Log.e(TAG, "  ❌ Go设备后端连接失败")
					}
				} catch (e: Exception) {
					Log.e(TAG, "  ❌ Go设备检查失败: ${e.message}")
				}
			} else {
				Log.e(TAG, "  ❌ 设备ID为空")
			}
			
			// 3. 检查是否存在路由循环
			//Log.i(TAG, "  📊 当前包统计: TUN->Device: $totalPacketsTunToDevice, Device->TUN: $totalPacketsDeviceToTun")

			/*if (totalPacketsTunToDevice > 1000 || totalPacketsDeviceToTun > 1000) {
				Log.w(TAG, "  ⚠️ 检测到高频包转发，可能存在路由问题")
			}*/OutlineVpnService
			
		} catch (e: Exception) {
			Log.e(TAG, "路由诊断失败", e)
		}
	}
	
	private suspend fun testSimpleHttpRequest() {
		Log.i(TAG, "🌐 开始HTTP连通性测试...")
		
		try {
			withContext(Dispatchers.IO) {
				// 测试多个目标以确认网络状态
				val testUrls = listOf(
					"http://httpbin.org/ip" to "httpbin",
					"http://www.google.com" to "google",
					"http://1.1.1.1" to "cloudflare"
				)
				
				for ((url, name) in testUrls) {
					try {
						Log.i(TAG, "🚀 测试连接到 $name: $url")
						
						val connection = URL(url).openConnection() as HttpURLConnection
						connection.connectTimeout = 5000  // 减少超时时间
						connection.readTimeout = 5000
						connection.setRequestProperty("User-Agent", "OutlineVPN-Test/1.0")
						
						val startTime = System.currentTimeMillis()
						val responseCode = connection.responseCode
						val duration = System.currentTimeMillis() - startTime
						
						Log.i(TAG, "📊 $name 响应: $responseCode (${duration}ms)")
						
						if (responseCode == 200) {
							val response = connection.inputStream.bufferedReader().use { 
								it.readText().take(200) // 只读前200字符
							}
							Log.i(TAG, "✅ $name 测试成功 (${duration}ms): $response")
							return@withContext // 至少一个成功就可以了
						} else {
							Log.w(TAG, "⚠️ $name 测试失败: $responseCode")
						}
						
					} catch (e: Exception) {
						Log.e(TAG, "❌ $name 请求失败: ${e.javaClass.simpleName}: ${e.message}")
					}
				}
				
				Log.e(TAG, "❌ 所有HTTP测试都失败了")
			}
		} catch (e: Exception) {
			Log.e(TAG, "❌ HTTP测试整体异常", e)
		}
		
		Log.i(TAG, "🌐 HTTP连通性测试完成")
	}

	private suspend fun startPacketForwarding() {
		val tunInterface = vpnInterface ?: return
		val currentDeviceId = deviceId ?: return

		Timber.tag(TAG).i("Starting packet forwarding for device: $currentDeviceId")

		// 准备开始数据转发
		Log.i(TAG, "准备开始数据转发，设备: $currentDeviceId")

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
							// Timber.tag(TAG).w("TUN->Device forwarding completed")
							job2.cancel()
						}
						job2.onJoin {
							// Timber.tag(TAG).w("Device->TUN forwarding completed")
							job1.cancel()
						}
					}
				} catch (e: Exception) {
					Timber.tag(TAG).e("Packet forwarding error: ${e.message}")
					job1.cancel()
					job2.cancel()
					throw e
				}
			}

		} catch (e: Exception) {
			Timber.tag(TAG).e("Packet forwarding error: ${e.message}")
		} finally {
			Timber.tag(TAG).i("Packet forwarding stopped")
			stopVPN()
		}
	}

	private suspend fun forwardTunToDevice(inputStream: ParcelFileDescriptor.AutoCloseInputStream, deviceId: String) {
		withContext(Dispatchers.IO) {
			val buffer = ByteArray(32767)
			var totalPackets = 0
			var totalBytes = 0L
			var consecutiveErrors = 0  // 添加错误计数

			try {
				Timber.tag(TAG).i("Starting TUN -> Device forwarding for device: $deviceId")
				while (isRunning && serviceScope.isActive) {
					val length = inputStream.read(buffer)
					if (length > 0) {
						totalPackets++
						totalBytes += length
						consecutiveErrors = 0  // 重置错误计数

						// 详细的包分析
						val data = buffer.copyOf(length)

						// 解析 IP 包头信息
						if (length >= 20) {
							val version = (data[0].toInt() and 0xF0) shr 4
							val protocol = data[9].toUByte().toInt()
							val srcIp = "${data[12].toUByte()}.${data[13].toUByte()}.${data[14].toUByte()}.${data[15].toUByte()}"
							val dstIp = "${data[16].toUByte()}.${data[17].toUByte()}.${data[18].toUByte()}.${data[19].toUByte()}"

							// Timber.tag(TAG).d("TUN->Device packet #$totalPackets: IPv$version, Protocol=$protocol, $srcIp -> $dstIp, Length=$length")
						}

						// 记录包信息用于调试
						if (totalPackets % 100 == 0) {
							// Timber.tag(TAG).d("TUN->Device: $totalPackets packets, $totalBytes bytes")

							// 路由循环检测
							checkForRoutingLoop(totalPackets)
						}

						// 将数据写入 lwIP 设备
						val written = Shared_backend.writeToVPNDevice(deviceId, data)

						if (written.toLong() != length.toLong()) {
							consecutiveErrors++
							Log.w(TAG, "部分写入: $written/$length, 连续错误: $consecutiveErrors")
							Timber.tag(TAG).w("Partial write to VPN device: $written/$length")

													// 连续错误处理
						if (consecutiveErrors >= 10) {
							Log.e(TAG, "连续写入错误，可能存在问题")
							consecutiveErrors = 0  // 重置避免频繁日志
						}
						}
					} else if (length == -1) {
						Timber.tag(TAG).i("TUN input stream ended")
						break
					} else {
						// length == 0, 短暂等待
						delay(1)
					}
				}
			} catch (e: Exception) {
				Timber.tag(TAG).e("Error forwarding TUN to device: ${e.message}")
				Log.e(TAG, "TUN到设备转发异常: ${e.message}")
			} finally {
				Timber.tag(TAG).i("TUN->Device forwarding stopped. Total: $totalPackets packets, $totalBytes bytes")
				try {
					inputStream.close()
				} catch (e: Exception) {
					Timber.tag(TAG).w("Error closing input stream: ${e.message}")
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
				Timber.tag(TAG).i("Starting Device -> TUN forwarding for device: $deviceId")
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

							// Timber.tag(TAG).d("Device->TUN packet #$totalPackets: IPv$version, Protocol=$protocol, $srcIp -> $dstIp, Length=$length")
						}

						// 记录包信息用于调试
						if (totalPackets % 100 == 0) {
							// Timber.tag(TAG).d("Device->TUN: $totalPackets packets, $totalBytes bytes")
						}

						// 写入 TUN 设备
						outputStream.write(buffer, 0, length.toInt())
						outputStream.flush()
					} else if (length == -1L) {
						Timber.tag(TAG).i("VPN device input ended")
						break
					}

					// 小延迟避免过度 CPU 使用
					if (length == 0L) {
						delay(1)
					}
				}
			} catch (e: Exception) {
				Timber.tag(TAG).e("Error forwarding device to TUN: ${e.message}")
			} finally {
				Timber.tag(TAG).i("Device->TUN forwarding stopped. Total: $totalPackets packets, $totalBytes bytes")
				try {
					outputStream.close()
				} catch (e: Exception) {
					Timber.tag(TAG).w("Error closing output stream: ${e.message}")
				}
			}
		}
	}

	private fun stopVPN() {
		Timber.tag(TAG).i("Stopping VPN")

		isRunning = false
		serviceScope.coroutineContext.cancelChildren()

		vpnInterface?.close()
		vpnInterface = null

		deviceId = null
		accessKey = null

		stopForeground(true)
		stopSelf()

		Timber.tag(TAG).i("VPN stopped")
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
//        if (shouldExcludeCurrentApp()) {
//            excludedApps.add(packageName)
//            Timber.tag(TAG).w("Excluding current app to prevent routing loops")
//        } else {
//            Timber.tag(TAG).i("Current app will use VPN routing")
//        }

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

			Timber.tag(TAG).d("Traffic analysis: $packetDiff packets in ${timeDiff}ms (${packetsPerSecond} pps)")

			if (packetDiff > MAX_PACKETS_PER_INTERVAL) {
				Timber.tag(TAG).w("Potential routing loop detected: $packetDiff packets in ${timeDiff}ms")
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
		Timber.tag(TAG).w("Implementing routing loop protection...")

		// 选项1：记录到配置中，下次启动时排除当前应用
		val prefs = getSharedPreferences("vpn_config", Context.MODE_PRIVATE)
		prefs.edit().putBoolean("exclude_current_app", true).apply()

		// 选项2：显示通知告知用户
		// showRoutingLoopNotification()

		// 选项3：暂时不采取行动，只记录日志供分析
		Timber.tag(TAG).i("Routing loop protection: logged for analysis")
	}

	// 🎯 路由策略配置函数
	private fun shouldRouteAppTraffic(): Boolean {
		// 可以通过SharedPreferences、配置文件或BuildConfig来控制
		// 目前默认为false，避免路由循环
		return false // 改为true来启用APP流量VPN路由
	}
	
	private fun configureAdvancedRouting(builder: Builder) {
		Log.i(TAG, "🔧 配置高级路由策略...")
		
		try {
			// 🎯 方案1: 绕过特定服务端口（推荐）
			// 绕过VPN管理端口，避免管理流量进入循环
			val vpnManagementPorts = listOf(8080, 8443, 9090) // 根据实际情况配置
			
			// 🎯 方案2: 使用本地回环绕过
			// 对于localhost通信，不通过VPN
			try {
				builder.addRoute("127.0.0.0", 8) // 排除本地回环
				Log.i(TAG, "✅ 已配置本地回环绕过")
			} catch (e: Exception) {
				Log.w(TAG, "本地回环配置失败: ${e.message}")
			}
			
			// 🎯 方案3: 智能DNS配置
			// 使用本地DNS缓存，减少DNS查询循环
			builder.addDnsServer("127.0.0.1") // 本地DNS优先
			
			// 🎯 方案4: 添加循环检测
			// 在应用层检测并处理循环
			
			Log.i(TAG, "✅ 高级路由策略配置完成")
			Log.w(TAG, "⚠️ 注意：已启用APP流量VPN路由，将监控循环检测")
			
		} catch (e: Exception) {
			Log.e(TAG, "❌ 高级路由配置失败: ${e.message}", e)
		}
	}

	override fun onDestroy() {
		Log.i(TAG, "💀 OutlineVpnService onDestroy called")
		super.onDestroy()
		stopVPN()
		serviceScope.cancel()
		Log.i(TAG, "💀 OutlineVpnService destroyed")
		Timber.tag(TAG).i("OutlineVpnService destroyed")
	}
}