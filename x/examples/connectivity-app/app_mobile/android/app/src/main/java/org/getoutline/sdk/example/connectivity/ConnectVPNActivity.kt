package org.getoutline.sdk.example.connectivity

import android.annotation.SuppressLint
import android.content.Intent
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
        private const val VPN_REQUEST_CODE = 100
    }

    private lateinit var accessKeyEditText: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var proxyInfoTextView: TextView

    private var isConnected = false
    private var proxyAddress: String? = null

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
        proxyInfoTextView = findViewById(R.id.textViewProxyInfo)
    }

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            connectProxy()
        }

        disconnectButton.setOnClickListener {
            disconnectProxy()
        }
    }

    private fun connectProxy() {
        val accessKey = accessKeyEditText.text.toString().trim()
        
        if (accessKey.isEmpty()) {
            Toast.makeText(this, "请输入 Outline 访问密钥", Toast.LENGTH_SHORT).show()
            return
        }

        connectButton.isEnabled = false
        statusTextView.text = "正在建立代理连接..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    setupOutlineProxy(accessKey)
                }

                // 连接成功
                isConnected = true
                proxyAddress = result
                updateUI()
                Toast.makeText(this@ConnectVPNActivity, "代理启动成功！", Toast.LENGTH_SHORT).show()

                // 启动代理服务
                startProxyService(accessKey)

            } catch (e: Exception) {
                // 连接失败
                isConnected = false
                proxyAddress = null
                updateUI()
                statusTextView.text = "连接失败: ${e.message}"
                Toast.makeText(this@ConnectVPNActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                connectButton.isEnabled = true
            }
        }
    }

    private fun setupOutlineProxy(accessKey: String): String {
        // 首先验证访问密钥是否有效
        val testResult = testConnectivityWithAccessKey(accessKey)
        if (!testResult) {
            throw Exception("访问密钥无效或服务器不可达")
        }

        // 创建本地代理配置
        val proxyRequest = createProxyRequest(accessKey)
        val frontendRequest = FrontendRequest(
            resourceName = "CreateProxy",
            parameters = Json.encodeToString(proxyRequest)
        )

        val requestJson = Json.encodeToString(frontendRequest)
        val responseBytes = Shared_backend.handleRequest(requestJson.toByteArray())
        val responseJson = responseBytes.toString(Charsets.UTF_8)
        val response = Json.decodeFromString<FrontendResponse>(responseJson)

        if (response.error.isNotEmpty()) {
            throw Exception("创建代理失败: ${response.error}")
        }

        // 解析代理地址
        val proxyResponse = Json.decodeFromString<ProxyResponse>(response.body)
        return proxyResponse.address
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

    private fun createProxyRequest(accessKey: String): ProxyRequest {
        return ProxyRequest(
            transportConfig = accessKey,
            localAddress = "127.0.0.1:0"  // 使用端口 0 让系统自动分配
        )
    }

    private fun startProxyService(accessKey: String) {
        val intent = Intent(this, OutlineVpnService::class.java).apply {
            putExtra("ACCESS_KEY", accessKey)
            action = OutlineVpnService.ACTION_START_PROXY
        }
        startService(intent)
    }

    private fun disconnectProxy() {
        val intent = Intent(this, OutlineVpnService::class.java).apply {
            action = OutlineVpnService.ACTION_STOP_PROXY
        }
        startService(intent)
        
        isConnected = false
        proxyAddress = null
        updateUI()
        statusTextView.text = "已断开连接"
        Toast.makeText(this, "代理服务已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isConnected) {
            connectButton.isEnabled = false
            disconnectButton.isEnabled = true
            statusTextView.text = "代理已启动"
            proxyInfoTextView.text = "本地代理地址: $proxyAddress\n\n📘 使用说明:\n1. 在浏览器中配置 HTTP 代理\n2. 代理地址: $proxyAddress\n3. 或配置应用使用此代理\n\n⚠️ 注意: 这是本地代理服务，需要手动配置应用才能使用"
        } else {
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            statusTextView.text = "未连接"
            proxyInfoTextView.text = "请输入 Outline 访问密钥并点击连接\n\n这将创建一个本地 HTTP 代理服务器，您可以配置浏览器或应用使用此代理来访问网络。"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isConnected) {
            disconnectProxy()
        }
    }
}