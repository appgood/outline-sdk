package org.getoutline.sdk.example.connectivity

import android.annotation.SuppressLint
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import shared_backend.Shared_backend

@SuppressLint("SetTextI18n")
class ConnectVPNActivity : AppCompatActivity() {

    companion object {
        private const val VPN_REQUEST_CODE = 1001
    }

    private lateinit var accessKeyEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var vpnInfoTextView: TextView

    private var isConnected = false
    private var deviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect_vpn)
        
        initViews()
        setupClickListeners()
        updateUI()
    }

    private fun initViews() {
        accessKeyEditText = findViewById(R.id.editTextVPNAccessKey)
        connectButton = findViewById(R.id.buttonConnect)
        disconnectButton = findViewById(R.id.buttonDisconnect)
        statusTextView = findViewById(R.id.textViewStatus)
        vpnInfoTextView = findViewById(R.id.textViewProxyInfo)

		accessKeyEditText.setText("ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpvbE9yMHBrUFZOWE5BOVFBamlqS1dK@27.106.118.170:60042/?outline=1")
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            requestVpnPermissionAndConnect()
        }

        disconnectButton.setOnClickListener {
            disconnectVPN()
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // 需要用户授权 VPN 权限
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // 已有权限，直接连接
            connectVPN()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                connectVPN()
            } else {
                Toast.makeText(this, "需要 VPN 权限才能继续", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectVPN() {
        val accessKey = accessKeyEditText.text.toString().trim()
        
        if (accessKey.isEmpty()) {
            Toast.makeText(this, "请输入 Outline 访问密钥", Toast.LENGTH_SHORT).show()
            return
        }

        connectButton.isEnabled = false
        statusTextView.text = "正在建立 VPN 连接..."

        lifecycleScope.launch {
            try {
                // 首先验证访问密钥
                val isValid = withContext(Dispatchers.IO) {
                    testConnectivityWithAccessKey(accessKey)
                }
                
                if (!isValid) {
                    throw Exception("访问密钥无效或服务器不可达")
                }

                // 创建 VPN 设备
                val response = withContext(Dispatchers.IO) {
                    createVPNDevice(accessKey)
                }

                deviceId = response.deviceId
                isConnected = true
                updateUI()
                Toast.makeText(this@ConnectVPNActivity, "VPN 连接成功！", Toast.LENGTH_SHORT).show()

                // 启动 VPN 服务
                startVPNService(accessKey, response.deviceId)

            } catch (e: Exception) {
                isConnected = false
                deviceId = null
                updateUI()
                statusTextView.text = "连接失败: ${e.message}"
                Toast.makeText(this@ConnectVPNActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                connectButton.isEnabled = true
            }
        }
    }

    private fun testConnectivityWithAccessKey(accessKey: String): Boolean {
        return try {
            val testRequest = ConnectivityTestRequest(
                accessKey = accessKey,
                domain = "www.google.com",
                resolvers = listOf("8.8.8.8"),
                protocols = ConnectivityTestProtocolConfig(tcp = true, udp = false)
            )

            val frontendRequest = FrontendRequest(
                resourceName = "ConnectivityTest",
                parameters = Json.encodeToString(testRequest)
            )

            val requestJson = Json.encodeToString(frontendRequest)
            val responseBytes = Shared_backend.handleRequest(requestJson.toByteArray())
            val responseJson = responseBytes.toString(Charsets.UTF_8)
            val response = Json.decodeFromString<FrontendResponse>(responseJson)

            if (response.error.isNotEmpty()) {
                return false
            }

            val results = Json.decodeFromString<List<ConnectivityTestResult>>(response.body)
            return results.any { it.error == null }

        } catch (e: Exception) {
            false
        }
    }

    private fun createVPNDevice(accessKey: String): VPNDeviceResponse {
        val vpnRequest = VPNDeviceRequest(
            transportConfig = accessKey
        )

        val frontendRequest = FrontendRequest(
            resourceName = "CreateVPNDevice",
            parameters = Json.encodeToString(vpnRequest)
        )

        val requestJson = Json.encodeToString(frontendRequest)
        val responseBytes = Shared_backend.handleRequest(requestJson.toByteArray())
        val responseJson = responseBytes.toString(Charsets.UTF_8)

        val response = Json.decodeFromString<FrontendResponse>(responseJson)

        if (response.error.isNotEmpty()) {
            throw Exception("创建 VPN 设备失败: ${response.error}")
        }

        return Json.decodeFromString<VPNDeviceResponse>(response.body)
    }

    private fun startVPNService(accessKey: String, deviceId: String) {
        val intent = Intent(this, OutlineVpnService::class.java).apply {
            action = OutlineVpnService.ACTION_CONNECT
            putExtra("ACCESS_KEY", accessKey)
            putExtra("DEVICE_ID", deviceId)
        }
        startService(intent)
    }

    private fun disconnectVPN() {
        isConnected = false
        
        lifecycleScope.launch {
            try {
                deviceId?.let { id ->
                    withContext(Dispatchers.IO) {
                        stopVPNDevice(id)
                    }
                }
            } catch (e: Exception) {
                // 忽略停止设备的错误，继续断开服务
            }
        }
        
        deviceId = null
        updateUI()
        statusTextView.text = "已断开连接"

        // 停止 VPN 服务
        val intent = Intent(this, OutlineVpnService::class.java).apply {
            action = OutlineVpnService.ACTION_DISCONNECT
        }
        startService(intent)

        Toast.makeText(this, "VPN 已断开", Toast.LENGTH_SHORT).show()
    }

    private fun stopVPNDevice(deviceId: String) {
        val stopRequest = StopVPNDeviceRequest(deviceId = deviceId)

        val frontendRequest = FrontendRequest(
            resourceName = "StopVPNDevice",
            parameters = Json.encodeToString(stopRequest)
        )

        val requestJson = Json.encodeToString(frontendRequest)
        val responseBytes = Shared_backend.handleRequest(requestJson.toByteArray())
        val responseJson = responseBytes.toString(Charsets.UTF_8)

        val response = Json.decodeFromString<FrontendResponse>(responseJson)

        if (response.error.isNotEmpty()) {
            throw Exception("停止 VPN 设备失败: ${response.error}")
        }
    }

    private fun updateUI() {
        if (isConnected) {
            connectButton.isEnabled = false
            disconnectButton.isEnabled = true
            statusTextView.text = "VPN 已连接"
            vpnInfoTextView.text = """
                🔒 VPN 设备 ID: $deviceId
                
                ✅ VPN 已激活，所有网络流量将通过 Outline 服务器路由
                
                🚀 技术特点:
                • 系统级 VPN 连接
                • 自动处理所有应用流量  
                • 基于 lwIP 的完整网络栈
                • 与官方 Outline 客户端相同的技术
                • 透明代理所有网络请求
                
                📱 无需额外配置，所有应用自动使用 VPN
            """.trimIndent()
        } else {
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            statusTextView.text = "未连接"
            vpnInfoTextView.text = """
                请输入 Outline 访问密钥并点击连接 VPN
                
                🔗 这将创建真正的 VPN 连接：
                • 系统级网络隧道
                • 所有应用自动通过 VPN
                • 基于 lwIP 的高性能网络栈
                • 与官方 Outline 客户端技术一致
                
                ⚠️ 需要 VPN 权限授权
            """.trimIndent()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isConnected) {
            disconnectVPN()
        }
    }
}