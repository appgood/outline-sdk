# VPN 功能集成指南 - 集成到现有 Android 项目

## 🎯 **集成概述**

将 Outline SDK VPN 功能集成到现有 Android 项目的完整指南。

## 📁 **第一步：项目结构准备**

### 1.1 创建 VPN 模块目录

在你的项目中创建以下目录结构：

```
your-android-project/
├── app/
│   ├── src/main/java/com/yourapp/
│   │   ├── vpn/                    # 新建 VPN 模块
│   │   │   ├── OutlineVpnService.kt
│   │   │   ├── VpnManager.kt       # VPN 管理接口
│   │   │   ├── VpnData.kt          # 数据类
│   │   │   └── VpnCallback.kt      # 回调接口
│   │   └── libs/
│   │       └── SharedBackend.aar   # Go 后端库
│   └── src/main/res/
│       └── layout/
└── build.gradle
```

## 🔧 **第二步：Go 后端集成**

### 2.1 复制 SharedBackend.aar

```bash
# 从 demo 项目复制 AAR 文件
cp outline-sdk/x/examples/connectivity-app/shared_backend/output/SharedBackend.aar \
   your-project/app/libs/
```

### 2.2 修改 app/build.gradle

```gradle
android {
    compileSdk 34

    defaultConfig {
        minSdk 22
        targetSdk 34
        // ... 其他配置
    }

    // 添加 AAR 支持
    repositories {
        flatDir {
            dirs 'libs'
        }
    }
}

dependencies {
    // 现有依赖...
    
    // Outline VPN 依赖
    implementation files('libs/SharedBackend.aar')
    implementation "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0"
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.8.2"
}
```

### 2.3 启用序列化插件

在 app/build.gradle 顶部添加：

```gradle
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlinx-serialization'  // 添加这一行
}
```

在项目根目录的 build.gradle 中添加：

```gradle
plugins {
    // 现有插件...
    id 'org.jetbrains.kotlin.plugin.serialization' version '1.8.0' apply false
}
```

## 📄 **第三步：复制核心文件**

### 3.1 创建数据类 (VpnData.kt)

```kotlin
package com.yourapp.vpn

import kotlinx.serialization.Serializable

@Serializable
data class VPNDeviceRequest(
    val transportConfig: String
)

@Serializable
data class VPNDeviceResponse(
    val deviceId: String,
    val status: String
)

@Serializable
data class StopVPNDeviceRequest(
    val deviceId: String
)

@Serializable
data class FrontendRequest(
    val resourceName: String,
    val parameters: String
)

@Serializable
data class FrontendResponse(
    val body: String,
    val error: String
)
```

### 3.2 创建回调接口 (VpnCallback.kt)

```kotlin
package com.yourapp.vpn

interface VpnCallback {
    fun onConnecting()
    fun onConnected(deviceId: String)
    fun onDisconnected()
    fun onError(error: String)
    fun onTrafficUpdate(uploadBytes: Long, downloadBytes: Long)
}

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}
```

### 3.3 复制 VPN 服务 (OutlineVpnService.kt)

[复制完整的 OutlineVpnService.kt 文件，但需要修改包名]

```kotlin
package com.yourapp.vpn  // 修改为你的包名

// ... 其余代码保持不变
```

### 3.4 创建 VPN 管理器 (VpnManager.kt)

```kotlin
package com.yourapp.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher

class VpnManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var INSTANCE: VpnManager? = null
        
        fun getInstance(context: Context): VpnManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VpnManager(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        const val VPN_REQUEST_CODE = 1001
    }
    
    private var callback: VpnCallback? = null
    private var currentStatus = VpnStatus.DISCONNECTED
    
    /**
     * 设置 VPN 状态回调
     */
    fun setCallback(callback: VpnCallback?) {
        this.callback = callback
    }
    
    /**
     * 获取当前 VPN 状态
     */
    fun getStatus(): VpnStatus = currentStatus
    
    /**
     * 请求 VPN 权限并连接
     */
    fun requestVpnPermission(activity: Activity): Intent? {
        return VpnService.prepare(activity)
    }
    
    /**
     * 连接 VPN
     */
    fun connect(accessKey: String) {
        if (currentStatus == VpnStatus.CONNECTING || currentStatus == VpnStatus.CONNECTED) {
            return
        }
        
        updateStatus(VpnStatus.CONNECTING)
        
        val intent = Intent(context, OutlineVpnService::class.java).apply {
            action = OutlineVpnService.ACTION_CONNECT
            putExtra("ACCESS_KEY", accessKey)
        }
        context.startService(intent)
    }
    
    /**
     * 断开 VPN
     */
    fun disconnect() {
        if (currentStatus == VpnStatus.DISCONNECTED) {
            return
        }
        
        updateStatus(VpnStatus.DISCONNECTING)
        
        val intent = Intent(context, OutlineVpnService::class.java).apply {
            action = OutlineVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }
    
    /**
     * 更新状态
     */
    private fun updateStatus(status: VpnStatus) {
        currentStatus = status
        when (status) {
            VpnStatus.CONNECTING -> callback?.onConnecting()
            VpnStatus.CONNECTED -> callback?.onConnected("device-id")
            VpnStatus.DISCONNECTED -> callback?.onDisconnected()
            VpnStatus.ERROR -> callback?.onError("连接错误")
            else -> {}
        }
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean = currentStatus == VpnStatus.CONNECTED
    
    /**
     * 验证访问密钥格式
     */
    fun validateAccessKey(accessKey: String): Boolean {
        return accessKey.isNotBlank() && 
               (accessKey.startsWith("ss://") || accessKey.contains("@"))
    }
}
```

## 🔒 **第四步：权限和配置**

### 4.1 修改 AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- VPN 相关权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.BIND_VPN_SERVICE" />

    <application>
        <!-- 现有的 Activity 和其他组件 -->
        
        <!-- VPN 服务 -->
        <service
            android:name=".vpn.OutlineVpnService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:permission="android.permission.BIND_VPN_SERVICE">
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
        
    </application>
</manifest>
```

## 💻 **第五步：在 Activity 中使用**

### 5.1 基本使用示例

```kotlin
class MainActivity : AppCompatActivity(), VpnCallback {
    
    private lateinit var vpnManager: VpnManager
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 初始化 VPN 管理器
        vpnManager = VpnManager.getInstance(this)
        vpnManager.setCallback(this)
        
        // 注册 VPN 权限请求
        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                // 权限获取成功，开始连接
                connectVpn()
            } else {
                showToast("需要 VPN 权限才能继续")
            }
        }
        
        setupUI()
    }
    
    private fun setupUI() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val accessKey = findViewById<EditText>(R.id.etAccessKey).text.toString()
            
            if (!vpnManager.validateAccessKey(accessKey)) {
                showToast("请输入有效的访问密钥")
                return@setOnClickListener
            }
            
            // 请求 VPN 权限
            val vpnIntent = vpnManager.requestVpnPermission(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                // 已有权限，直接连接
                connectVpn()
            }
        }
        
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            vpnManager.disconnect()
        }
    }
    
    private fun connectVpn() {
        val accessKey = findViewById<EditText>(R.id.etAccessKey).text.toString()
        vpnManager.connect(accessKey)
    }
    
    // VPN 回调实现
    override fun onConnecting() {
        runOnUiThread {
            showToast("正在连接 VPN...")
            updateUI(VpnStatus.CONNECTING)
        }
    }
    
    override fun onConnected(deviceId: String) {
        runOnUiThread {
            showToast("VPN 连接成功")
            updateUI(VpnStatus.CONNECTED)
        }
    }
    
    override fun onDisconnected() {
        runOnUiThread {
            showToast("VPN 已断开")
            updateUI(VpnStatus.DISCONNECTED)
        }
    }
    
    override fun onError(error: String) {
        runOnUiThread {
            showToast("VPN 错误: $error")
            updateUI(VpnStatus.ERROR)
        }
    }
    
    override fun onTrafficUpdate(uploadBytes: Long, downloadBytes: Long) {
        // 可选：更新流量统计 UI
    }
    
    private fun updateUI(status: VpnStatus) {
        val connectBtn = findViewById<Button>(R.id.btnConnect)
        val disconnectBtn = findViewById<Button>(R.id.btnDisconnect)
        
        when (status) {
            VpnStatus.DISCONNECTED -> {
                connectBtn.isEnabled = true
                disconnectBtn.isEnabled = false
                connectBtn.text = "连接 VPN"
            }
            VpnStatus.CONNECTING -> {
                connectBtn.isEnabled = false
                disconnectBtn.isEnabled = false
                connectBtn.text = "连接中..."
            }
            VpnStatus.CONNECTED -> {
                connectBtn.isEnabled = false
                disconnectBtn.isEnabled = true
                connectBtn.text = "已连接"
            }
            else -> {
                connectBtn.isEnabled = true
                disconnectBtn.isEnabled = false
                connectBtn.text = "连接 VPN"
            }
        }
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        vpnManager.setCallback(null)
    }
}
```

### 5.2 布局文件示例 (activity_main.xml)

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Outline VPN"
        android:textSize="24sp"
        android:textStyle="bold"
        android:gravity="center"
        android:layout_marginBottom="32dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="16dp">

        <EditText
            android:id="@+id/etAccessKey"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:hint="输入 Outline 访问密钥"
            android:inputType="textUri" />

    </com.google.android.material.textfield.TextInputLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginBottom="24dp">

        <Button
            android:id="@+id/btnConnect"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginEnd="8dp"
            android:text="连接 VPN"
            android:backgroundTint="@color/design_default_color_primary" />

        <Button
            android:id="@+id/btnDisconnect"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="8dp"
            android:text="断开 VPN"
            android:enabled="false"
            android:backgroundTint="@color/design_default_color_error" />

    </LinearLayout>

</LinearLayout>
```

## 🧪 **第六步：测试和验证**

### 6.1 基本功能测试

```kotlin
// 可以创建一个测试 Activity 或在现有 Activity 中添加测试代码
class VpnTestActivity : AppCompatActivity() {
    
    private lateinit var vpnManager: VpnManager
    
    private fun testVpnFunctionality() {
        // 测试访问密钥验证
        val validKey = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzd29yZA@server.com:443"
        assert(vpnManager.validateAccessKey(validKey))
        
        // 测试状态管理
        assert(vpnManager.getStatus() == VpnStatus.DISCONNECTED)
        
        // 测试连接流程
        vpnManager.connect(validKey)
        // 应该变为 CONNECTING 状态
    }
    
    private fun testNetworkConnectivity() {
        // VPN 连接后测试网络连接
        Thread {
            try {
                val url = URL("https://api.ipify.org?format=json")
                val connection = url.openConnection()
                val response = connection.getInputStream().bufferedReader().readText()
                Log.d("VPN_TEST", "Public IP: $response")
            } catch (e: Exception) {
                Log.e("VPN_TEST", "Network test failed", e)
            }
        }.start()
    }
}
```

### 6.2 性能监控

```kotlin
class VpnPerformanceMonitor : VpnCallback {
    private var startTime = 0L
    private var totalUpload = 0L
    private var totalDownload = 0L
    
    override fun onConnecting() {
        startTime = System.currentTimeMillis()
    }
    
    override fun onConnected(deviceId: String) {
        val connectionTime = System.currentTimeMillis() - startTime
        Log.d("VPN_PERF", "Connection established in ${connectionTime}ms")
    }
    
    override fun onTrafficUpdate(uploadBytes: Long, downloadBytes: Long) {
        totalUpload += uploadBytes
        totalDownload += downloadBytes
        Log.d("VPN_PERF", "Traffic: ↑${totalUpload} ↓${totalDownload}")
    }
    
    override fun onDisconnected() {
        Log.d("VPN_PERF", "Total session: ↑${totalUpload} ↓${totalDownload}")
    }
    
    override fun onError(error: String) {
        Log.e("VPN_PERF", "VPN Error: $error")
    }
}
```

## ⚠️ **注意事项和最佳实践**

### 7.1 重要注意事项

1. **权限处理**
   - VPN 权限需要用户明确授权
   - 首次使用时会弹出系统权限对话框
   - 建议在 UI 中说明为什么需要 VPN 权限

2. **生命周期管理**
   - VPN 服务独立于 Activity 运行
   - 应用关闭时 VPN 可以继续运行
   - 需要提供断开 VPN 的方式

3. **错误处理**
   - 网络异常、服务器不可达
   - 访问密钥无效
   - 系统资源不足

4. **性能考虑**
   - VPN 会消耗额外的电池和 CPU
   - 建议提供流量统计功能
   - 考虑添加省电模式

### 7.2 调试技巧

```bash
# 查看 VPN 相关日志
adb logcat | grep -E "(OutlineVpnService|VPN|TUN)"

# 检查网络接口
adb shell ip addr show

# 检查路由表
adb shell ip route show

# 测试网络连接
adb shell ping -c 3 8.8.8.8
```

### 7.3 发布前检查清单

- [ ] 所有权限已正确声明
- [ ] VPN 服务已在 manifest 中注册
- [ ] SharedBackend.aar 已包含在构建中
- [ ] 错误处理覆盖所有场景
- [ ] UI 状态正确反映 VPN 状态
- [ ] 内存泄漏检查完成
- [ ] 不同 Android 版本兼容性测试

## 🚀 **高级配置**

### 8.1 自定义配置

```kotlin
class VpnConfig {
    companion object {
        // 排除特定应用
        val EXCLUDED_APPS = listOf(
            "com.android.systemui",
            "com.android.phone"
        )
        
        // VPN 参数
        const val VPN_MTU = 1500
        const val VPN_ADDRESS = "26.26.26.1"
        
        // 连接超时
        const val CONNECTION_TIMEOUT = 30000L // 30秒
        
        // 流量统计间隔
        const val TRAFFIC_UPDATE_INTERVAL = 1000L // 1秒
    }
}
```

### 8.2 多服务器支持

```kotlin
data class VpnServer(
    val name: String,
    val accessKey: String,
    val location: String,
    val ping: Int = -1
)

class VpnServerManager {
    private val servers = mutableListOf<VpnServer>()
    
    fun addServer(server: VpnServer) {
        servers.add(server)
    }
    
    fun getBestServer(): VpnServer? {
        return servers.minByOrNull { it.ping }
    }
    
    suspend fun testServerLatency(server: VpnServer): Int {
        // 实现延迟测试逻辑
        return 0
    }
}
```

这个集成指南提供了完整的步骤和代码示例，帮助你将 VPN 功能无缝集成到现有项目中。如果在集成过程中遇到问题，请告诉我具体的错误信息！ 