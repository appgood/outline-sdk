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
class MainActivity : AppCompatActivity() {
    
    private lateinit var accessKeyEditText: EditText
    private lateinit var domainEditText: EditText
    private lateinit var resolversEditText: EditText
    private lateinit var testButton: Button
    private lateinit var vpnButton: Button
    private lateinit var resultTextView: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupClickListeners()
    }
    
    private fun initViews() {
        accessKeyEditText = findViewById(R.id.editTextAccessKey)
        domainEditText = findViewById(R.id.editTextDomain)
        resolversEditText = findViewById(R.id.editTextResolvers)
        testButton = findViewById(R.id.buttonTest)
        vpnButton = findViewById(R.id.buttonVPN)
        resultTextView = findViewById(R.id.textViewResult)
        
        // 设置默认值
        domainEditText.setText("example.com")
        resolversEditText.setText("8.8.8.8\n1.1.1.1")
    }
    
    private fun setupClickListeners() {
        testButton.setOnClickListener {
            performConnectivityTest()
        }
        
        vpnButton.setOnClickListener {
            val intent = Intent(this, ConnectVPNActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun performConnectivityTest() {
        val accessKey = accessKeyEditText.text.toString().trim()
        val domain = domainEditText.text.toString().trim()
        val resolvers = resolversEditText.text.toString()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        if (accessKey.isEmpty()) {
            Toast.makeText(this, "请输入 Outline 访问密钥", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (domain.isEmpty()) {
            Toast.makeText(this, "请输入测试域名", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (resolvers.isEmpty()) {
            Toast.makeText(this, "请输入至少一个 DNS 解析器", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 禁用按钮，显示测试中状态
        testButton.isEnabled = false
        testButton.text = "测试中..."
        resultTextView.text = "正在执行连接测试，请稍候..."
        
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    testConnectivityWithGoBackend(accessKey, domain, resolvers)
                }
                
                // 在主线程更新UI
                displayResult(result)
                
            } catch (e: Exception) {
                displayError(e.message ?: "未知错误")
            } finally {
                // 恢复按钮状态
                testButton.isEnabled = true
                testButton.text = "开始测试"
            }
        }
    }
    
    private fun testConnectivityWithGoBackend(
        accessKey: String,
        domain: String,
        resolvers: List<String>
    ): String {
        
        // 构建连接测试请求
        val testRequest = ConnectivityTestRequest(
            accessKey = accessKey,
            domain = domain,
            resolvers = resolvers,
            protocols = ConnectivityTestProtocolConfig(tcp = true, udp = true)
        )
        
        // 构建前端请求
        val frontendRequest = FrontendRequest(
            resourceName = "ConnectivityTest",
            parameters = Json.encodeToString(testRequest)
        )
        
        // 调用 Go 后端
        val requestJson = Json.encodeToString(frontendRequest)
        val responseBytes = Shared_backend.handleRequest(requestJson.toByteArray())
        val responseJson = responseBytes.toString(Charsets.UTF_8)
        
        // 解析响应
        val response = Json.decodeFromString<FrontendResponse>(responseJson)
        
        if (response.error.isNotEmpty()) {
            throw Exception("测试失败: ${response.error}")
        }
        
        return response.body
    }
    
    private fun displayResult(resultJson: String) {
        try {
            val results = Json.decodeFromString<List<ConnectivityTestResult>>(resultJson)
            
            val resultText = buildString {
                appendLine("=== 连接测试结果 ===")
                appendLine()
                
                var successCount = 0
                val totalCount = results.size
                
                results.forEach { result ->
                    val status = if (result.error == null) {
                        successCount++
                        "✅ 成功"
                    } else {
                        "❌ 失败"
                    }
                    
                    appendLine("协议: ${result.proto.uppercase()}")
                    appendLine("解析器: ${result.resolver}")
                    appendLine("状态: $status")
                    appendLine("耗时: ${result.durationMs}ms")
                    
                    if (result.error != null) {
                        appendLine("错误: ${result.error.msg}")
                    }
                    
                    appendLine("代理: ${result.proxy}")
                    appendLine("时间: ${result.time}")
                    appendLine("---")
                }
                
                appendLine()
                appendLine("总结: $successCount/$totalCount 测试成功")
            }
            
            resultTextView.text = resultText
            
        } catch (e: Exception) {
            displayError("解析结果失败: ${e.message}")
        }
    }

	private fun displayError(error: String) {
        resultTextView.text = "❌ 测试失败\n\n$error"
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }
}
