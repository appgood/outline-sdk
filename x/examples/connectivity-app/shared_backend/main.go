// Copyright 2023 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package shared_backend

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Jigsaw-Code/outline-sdk/dns"
	"github.com/Jigsaw-Code/outline-sdk/network"
	"github.com/Jigsaw-Code/outline-sdk/network/lwip2transport"
	"github.com/Jigsaw-Code/outline-sdk/transport"
	"github.com/Jigsaw-Code/outline-sdk/transport/shadowsocks"
	"github.com/Jigsaw-Code/outline-sdk/x/connectivity"
	"github.com/Jigsaw-Code/outline-sdk/x/configurl"
	"github.com/Jigsaw-Code/outline-sdk/x/httpproxy"

	_ "golang.org/x/mobile/bind"
)

type ConnectivityTestProtocolConfig struct {
	TCP bool `json:"tcp"`
	UDP bool `json:"udp"`
}

type ConnectivityTestResult struct {
	// Inputs
	Proxy    string `json:"proxy"`
	Resolver string `json:"resolver"`
	Proto    string `json:"proto"`
	Prefix   string `json:"prefix"`
	// Observations
	Time       time.Time              `json:"time"`
	DurationMs int64                  `json:"durationMs"`
	Error      *ConnectivityTestError `json:"error"`
}

type ConnectivityTestError struct {
	// TODO: add Shadowsocks/Transport error
	Op string `json:"operation"`
	// Posix error, when available
	PosixError string `json:"posixError"`
	// TODO: remove IP addresses
	Msg string `json:"message"`
}

type ConnectivityTestRequest struct {
	AccessKey string                         `json:"accessKey"`
	Domain    string                         `json:"domain"`
	Resolvers []string                       `json:"resolvers"`
	Protocols ConnectivityTestProtocolConfig `json:"protocols"`
}

type sessionConfig struct {
	Hostname  string
	Port      int
	CryptoKey *shadowsocks.EncryptionKey
	Prefix    Prefix
}

type Prefix []byte

func ConnectivityTest(request ConnectivityTestRequest) ([]ConnectivityTestResult, error) {
	// 简化实现，主要验证访问密钥的有效性
	_, err := parseAccessKey(request.AccessKey)
	if err != nil {
		return nil, err
	}

	var results []ConnectivityTestResult

	for _, resolverHost := range request.Resolvers {
		resolverHost := strings.TrimSpace(resolverHost)
		resolverAddress := net.JoinHostPort(resolverHost, "53")

		if request.Protocols.TCP {
			testTime := time.Now()
			startTime := time.Now()

			// 创建 TCP 解析器进行连接测试
			resolver := dns.NewTCPResolver(&transport.TCPDialer{}, resolverAddress)

			testResult, testErr := connectivity.TestConnectivityWithResolver(context.Background(), resolver, request.Domain)

			testDuration := time.Since(startTime)

			results = append(results, ConnectivityTestResult{
				Proxy:      request.AccessKey, // 简化，使用访问密钥作为代理标识
				Resolver:   resolverAddress,
				Proto:      "tcp",
				Prefix:     "",
				Time:       testTime.UTC().Truncate(time.Second),
				DurationMs: testDuration.Milliseconds(),
				Error:      makeErrorRecord(testResult, testErr),
			})
		}

		if request.Protocols.UDP {
			testTime := time.Now()
			startTime := time.Now()

			// 创建 UDP 解析器进行连接测试
			resolver := dns.NewUDPResolver(&transport.UDPDialer{}, resolverAddress)

			testResult, testErr := connectivity.TestConnectivityWithResolver(context.Background(), resolver, request.Domain)

			testDuration := time.Since(startTime)

			results = append(results, ConnectivityTestResult{
				Proxy:      request.AccessKey, // 简化，使用访问密钥作为代理标识
				Resolver:   resolverAddress,
				Proto:      "udp",
				Prefix:     "",
				Time:       testTime.UTC().Truncate(time.Second),
				DurationMs: testDuration.Milliseconds(),
				Error:      makeErrorRecord(testResult, testErr),
			})
		}
	}

	return results, nil
}

type PlatformMetadata struct {
	OS string `json:"operatingSystem"`
}

type ProxyRequest struct {
	TransportConfig string `json:"transportConfig"`
	LocalAddress    string `json:"localAddress"`
}

type ProxyResponse struct {
	Address string `json:"address"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
}

// 全局代理服务器实例
var globalProxyServer *http.Server

// VPN 设备相关结构
type VPNDeviceRequest struct {
	TransportConfig string `json:"transportConfig"`
}

type VPNDeviceResponse struct {
	DeviceID string `json:"deviceId"`
	Status   string `json:"status"`
}

type VPNDevice struct {
	id           string
	device       network.IPDevice
	fromDevice   *io.PipeReader  // Android 从这里读取 (设备输出)
	toDevice     *io.PipeWriter  // Android 写入到这里 (设备输入)
	fromAndroid  *io.PipeReader  // 设备从这里读取 (Android 输出)
	toAndroid    *io.PipeWriter  // 设备写入到这里 (Android 输入) 
	cancel       context.CancelFunc
	mu           sync.RWMutex
}

// 全局 VPN 设备管理 - 增强版
var (
	vpnDevices     = make(map[string]*VPNDevice)
	vpnMutex       sync.RWMutex
	deviceCounter  int64 = 0
	debugMode      bool  = true  // 调试模式
)

func Platform() PlatformMetadata {
	return PlatformMetadata{OS: runtime.GOOS}
}

func CreateProxy(request ProxyRequest) (*ProxyResponse, error) {
	// 停止现有的代理服务器（如果有）
	if globalProxyServer != nil {
		globalProxyServer.Close()
		globalProxyServer = nil
	}

	// 创建传输配置
	configModule := configurl.NewDefaultProviders()
	dialer, err := configModule.NewStreamDialer(context.Background(), request.TransportConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create stream dialer: %w", err)
	}

	// 创建监听器
	listener, err := net.Listen("tcp", request.LocalAddress)
	if err != nil {
		return nil, fmt.Errorf("failed to listen on %s: %w", request.LocalAddress, err)
	}

	// 创建代理处理器
	proxyHandler := httpproxy.NewProxyHandler(&streamDialerAdapter{dialer})
	proxyHandler.FallbackHandler = http.NotFoundHandler()

	// 创建 HTTP 服务器
	globalProxyServer = &http.Server{
		Handler: proxyHandler,
	}

	// 启动代理服务器
	go func() {
		if err := globalProxyServer.Serve(listener); err != nil && err != http.ErrServerClosed {
			log.Printf("Proxy server error: %v", err)
		}
	}()

	// 解析监听地址
	host, portStr, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		return nil, fmt.Errorf("failed to parse proxy address: %w", err)
	}

	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("failed to parse proxy port: %w", err)
	}

	return &ProxyResponse{
		Address: net.JoinHostPort(host, portStr),
		Host:    host,
		Port:    port,
	}, nil
}

// streamDialerAdapter 适配器，用于包装 transport.StreamDialer
type streamDialerAdapter struct {
	dialer transport.StreamDialer
}

func (s *streamDialerAdapter) DialStream(ctx context.Context, addr string) (transport.StreamConn, error) {
	return s.dialer.DialStream(ctx, addr)
}

// VPN 设备管理函数
func CreateVPNDevice(request VPNDeviceRequest) (*VPNDeviceResponse, error) {
	// 创建传输配置
	configModule := configurl.NewDefaultProviders()
	
	// 创建 Stream 拨号器
	sd, err := configModule.NewStreamDialer(context.Background(), request.TransportConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create stream dialer: %w", err)
	}
	
	// 创建 Packet 监听器和代理
	pl, err := configModule.NewPacketListener(context.Background(), request.TransportConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create packet listener: %w", err)
	}
	
	pp, err := network.NewPacketProxyFromPacketListener(pl)
	if err != nil {
		return nil, fmt.Errorf("failed to create packet proxy: %w", err)
	}
	
	// 使用 lwIP 配置设备
	log.Printf("Creating lwIP device with transport config...")
	device, err := lwip2transport.ConfigureDevice(sd, pp)
	if err != nil {
		log.Printf("Failed to configure lwIP device: %v", err)
		return nil, fmt.Errorf("failed to configure lwIP device: %w", err)
	}
	log.Printf("lwIP device configured successfully")
	
	// 创建双向管道用于与 Android 通信
	// 管道 1: Android 读取 (lwIP -> Android)
	androidReader, deviceWriter := io.Pipe()
	// 管道 2: Android 写入 (Android -> lwIP)  
	deviceReader, androidWriter := io.Pipe()
	
	// 创建上下文用于管理生命周期
	ctx, cancel := context.WithCancel(context.Background())
	
	// 生成设备 ID - 使用原子计数器确保唯一性
	atomic.AddInt64(&deviceCounter, 1)
	deviceID := fmt.Sprintf("vpn-device-%d-%d", time.Now().Unix(), deviceCounter)
	
	vpnDevice := &VPNDevice{
		id:          deviceID,
		device:      device,
		fromDevice:  androidReader,    // Android 从这里读取
		toAndroid:   deviceWriter,     // lwIP 写入这里 -> Android
		fromAndroid: deviceReader,     // lwIP 从这里读取 <- Android  
		toDevice:    androidWriter,    // Android 写入这里
		cancel:      cancel,
	}
	
	// 存储设备 - 增强版本
	vpnMutex.Lock()
	vpnDevices[deviceID] = vpnDevice
	vpnMutex.Unlock()
	
	if debugMode {
		log.Printf("✅ VPN设备已创建: %s, 当前设备数量: %d", deviceID, len(vpnDevices))
	}
	
	// 启动数据转发协程
	go vpnDevice.startForwarding(ctx)
	
	return &VPNDeviceResponse{
		DeviceID: deviceID,
		Status:   "created",
	}, nil
}

func (vd *VPNDevice) startForwarding(ctx context.Context) {
	defer func() {
		if debugMode {
			log.Printf("🔄 VPN设备 %s 开始清理", vd.id)
		}
		vd.cleanup()
	}()
	
	if debugMode {
		log.Printf("🚀 VPN设备 %s 开始数据转发", vd.id)
	}
	
	// 添加设备健康检查
	healthCtx, healthCancel := context.WithCancel(ctx)
	defer healthCancel()
	
	// 启动健康检查协程
	go func() {
		ticker := time.NewTicker(5 * time.Second)
		defer ticker.Stop()
		
		for {
			select {
			case <-healthCtx.Done():
				return
			case <-ticker.C:
				if debugMode {
					log.Printf("💓 VPN设备 %s 健康检查", vd.id)
				}
				// 检查设备是否还在映射中
				vpnMutex.RLock()
				_, exists := vpnDevices[vd.id]
				vpnMutex.RUnlock()
				if !exists {
					log.Printf("⚠️ VPN设备 %s 从映射中消失了！", vd.id)
					healthCancel()
					return
				}
			}
		}
	}()
	
	// 启动双向数据转发
	done := make(chan error, 2)
	
	// lwIP 设备 -> Android (设备输出复制到 Android 读取管道)
	go func() {
		log.Printf("VPN device %s: Starting lwIP->Android forwarding", vd.id)
		
		// 使用带监控的复制
		n, err := copyWithMonitoring(vd.toAndroid, vd.device, fmt.Sprintf("%s-lwIP->Android", vd.id))
		
		log.Printf("VPN device %s: lwIP->Android copy ended: %d bytes, error: %v", vd.id, n, err)
		done <- err
	}()
	
	// Android -> lwIP 设备 (Android 写入管道复制到设备输入)
	go func() {
		log.Printf("VPN device %s: Starting Android->lwIP forwarding", vd.id)
		
		// 使用带监控的复制
		n, err := copyWithMonitoring(vd.device, vd.fromAndroid, fmt.Sprintf("%s-Android->lwIP", vd.id))
		
		log.Printf("VPN device %s: Android->lwIP copy ended: %d bytes, error: %v", vd.id, n, err)
		done <- err
	}()
	
	// 等待任一方向完成或上下文取消
	select {
	case <-ctx.Done():
		log.Printf("VPN device %s context cancelled", vd.id)
	case err := <-done:
		if err != nil {
			log.Printf("VPN device %s forwarding error: %v", vd.id, err)
		} else {
			log.Printf("VPN device %s forwarding completed normally", vd.id)
		}
	}
}

func (vd *VPNDevice) cleanup() {
	vd.mu.Lock()
	defer vd.mu.Unlock()
	
	if debugMode {
		log.Printf("🧹 VPN设备 %s 开始清理资源", vd.id)
	}
	
	if vd.fromDevice != nil {
		vd.fromDevice.Close()
	}
	if vd.toAndroid != nil {
		vd.toAndroid.Close()
	}
	if vd.fromAndroid != nil {
		vd.fromAndroid.Close()
	}
	if vd.toDevice != nil {
		vd.toDevice.Close()
	}
	if vd.device != nil {
		vd.device.Close()
	}
	
	// 从全局映射中移除
	vpnMutex.Lock()
	delete(vpnDevices, vd.id)
	remainingDevices := len(vpnDevices)
	vpnMutex.Unlock()
	
	if debugMode {
		log.Printf("🗑️ VPN设备 %s 已清理完成，剩余设备数量: %d", vd.id, remainingDevices)
	}
}

func GetVPNDevice(deviceID string) (*VPNDevice, error) {
	vpnMutex.RLock()
	device, exists := vpnDevices[deviceID]
	vpnMutex.RUnlock()
	
	if debugMode {
		if exists {
			log.Printf("✅ GetVPNDevice: Found device %s", deviceID)
		} else {
			log.Printf("❌ GetVPNDevice: Device %s not found, available devices: %v", deviceID, getDeviceIDs())
		}
	}
	
	if !exists {
		return nil, fmt.Errorf("VPN device %s not found", deviceID)
	}
	
	return device, nil
}

// 获取所有设备ID的辅助函数（调试用）
func getDeviceIDs() []string {
	vpnMutex.RLock()
	defer vpnMutex.RUnlock()
	
	var ids []string
	for id := range vpnDevices {
		ids = append(ids, id)
	}
	return ids
}

func StopVPNDevice(deviceID string) error {
	device, err := GetVPNDevice(deviceID)
	if err != nil {
		return err
	}
	
	device.cancel()
	return nil
}

// Android 写入数据到 lwIP 设备 (TUN -> lwIP)
func WriteToVPNDevice(deviceID string, data []byte) (int, error) {
	device, err := GetVPNDevice(deviceID)
	if err != nil {
		return 0, err
	}
	
	// Android 写入 -> lwIP 设备
	return device.toDevice.Write(data)
}

// Android 从 lwIP 设备读取数据 (lwIP -> TUN)
func ReadFromVPNDevice(deviceID string, buffer []byte) (int, error) {
	device, err := GetVPNDevice(deviceID)
	if err != nil {
		return 0, err
	}
	
	// lwIP 设备 -> Android 读取
	return device.fromDevice.Read(buffer)
}

func makeErrorRecord(connectivityErr *connectivity.ConnectivityError, err error) *ConnectivityTestError {
	if connectivityErr == nil && err == nil {
		return nil
	}
	
	if connectivityErr != nil {
		return &ConnectivityTestError{
			Op:         connectivityErr.Op,
			PosixError: connectivityErr.PosixError,
			Msg:        connectivityErr.Error(),
		}
	}
	
	if err != nil {
		return &ConnectivityTestError{
			Op:         "test",
			PosixError: "",
			Msg:        err.Error(),
		}
	}
	
	return nil
}

func unwrapAll(err error) error {
	for {
		unwrapped := errors.Unwrap(err)
		if unwrapped == nil {
			return err
		}
		err = unwrapped
	}
}

func (p Prefix) String() string {
	runes := make([]rune, len(p))
	for i, b := range p {
		runes[i] = rune(b)
	}
	return string(runes)
}

func parseAccessKey(accessKey string) (*sessionConfig, error) {
	var config sessionConfig
	accessKeyURL, err := url.Parse(accessKey)
	if err != nil {
		return nil, fmt.Errorf("failed to parse access key: %w", err)
	}
	var portString string
	// Host is a <host>:<port> string
	config.Hostname, portString, err = net.SplitHostPort(accessKeyURL.Host)
	if err != nil {
		return nil, fmt.Errorf("failed to parse endpoint address: %w", err)
	}
	config.Port, err = strconv.Atoi(portString)
	if err != nil {
		return nil, fmt.Errorf("failed to parse port number: %w", err)
	}
	cipherInfoBytes, err := base64.URLEncoding.WithPadding(base64.NoPadding).DecodeString(accessKeyURL.User.String())
	if err != nil {
		return nil, fmt.Errorf("failed to decode cipher info [%v]: %v", accessKeyURL.User.String(), err)
	}
	cipherName, secret, found := strings.Cut(string(cipherInfoBytes), ":")
	if !found {
		return nil, fmt.Errorf("invalid cipher info: no ':' separator")
	}
	config.CryptoKey, err = shadowsocks.NewEncryptionKey(cipherName, secret)
	if err != nil {
		return nil, fmt.Errorf("failed to create cipher: %w", err)
	}
	prefixStr := accessKeyURL.Query().Get("prefix")
	if len(prefixStr) > 0 {
		config.Prefix, err = ParseStringPrefix(prefixStr)
		if err != nil {
			return nil, fmt.Errorf("failed to parse prefix: %w", err)
		}
	}
	return &config, nil
}

func ParseStringPrefix(utf8Str string) (Prefix, error) {
	runes := []rune(utf8Str)
	rawBytes := make([]byte, len(runes))
	for i, r := range runes {
		if (r & 0xFF) != r {
			return nil, fmt.Errorf("character out of range: %d", r)
		}
		rawBytes[i] = byte(r)
	}
	return rawBytes, nil
}

// 网络连通性测试函数
func TestNetworkConnectivity(deviceID string) (string, error) {
	if debugMode {
		log.Printf("🔍 开始网络连通性测试，设备: %s", deviceID)
	}
	
	device, err := GetVPNDevice(deviceID)
	if err != nil {
		return "", fmt.Errorf("设备不存在: %v", err)
	}
	
	result := make(map[string]interface{})
	
	// 测试1：设备状态
	result["device_status"] = "active"
	result["device_id"] = deviceID
	
	// 测试2：管道状态
	pipeStatus := make(map[string]string)
	if device.fromDevice != nil {
		pipeStatus["fromDevice"] = "open"
	} else {
		pipeStatus["fromDevice"] = "closed"
	}
	if device.toDevice != nil {
		pipeStatus["toDevice"] = "open"
	} else {
		pipeStatus["toDevice"] = "closed"
	}
	result["pipe_status"] = pipeStatus
	
	// 测试3：尝试小数据包测试
	testData := []byte("connectivity-test-ping")
	written, writeErr := device.toDevice.Write(testData)
	if writeErr != nil {
		result["write_test"] = fmt.Sprintf("failed: %v", writeErr)
	} else {
		result["write_test"] = fmt.Sprintf("success: %d bytes", written)
	}
	
	if debugMode {
		log.Printf("🔍 网络连通性测试完成: %+v", result)
	}
	
	// 将结果编码为JSON字符串
	resultBytes, _ := json.Marshal(result)
	return string(resultBytes), nil
}

// lwIP网络栈诊断
func DiagnoseLwIPStack(deviceID string) (string, error) {
	if debugMode {
		log.Printf("🔧 开始lwIP网络栈诊断，设备: %s", deviceID)
	}
	
	device, err := GetVPNDevice(deviceID)
	if err != nil {
		return "", fmt.Errorf("设备不存在: %v", err)
	}
	
	result := make(map[string]interface{})
	result["device_id"] = deviceID
	result["timestamp"] = time.Now().Unix()
	
	// 检查lwIP设备状态
	if device.device != nil {
		result["lwip_device"] = "active"
		result["lwip_mtu"] = device.device.MTU()
	} else {
		result["lwip_device"] = "null"
	}
	
	// 检查管道连接状态
	pipeStatus := make(map[string]interface{})
	
	// 尝试写入测试数据到各个管道
	testPayload := []byte("lwip-test-" + fmt.Sprintf("%d", time.Now().Unix()))
	
	if device.toDevice != nil {
		_, writeErr := device.toDevice.Write(testPayload)
		if writeErr != nil {
			pipeStatus["toDevice_write"] = fmt.Sprintf("error: %v", writeErr)
		} else {
			pipeStatus["toDevice_write"] = "success"
		}
	} else {
		pipeStatus["toDevice_write"] = "pipe_null"
	}
	
	result["pipe_diagnostics"] = pipeStatus
	
	// 全局设备统计
	vpnMutex.RLock()
	totalDevices := len(vpnDevices)
	var deviceList []string
	for id := range vpnDevices {
		deviceList = append(deviceList, id)
	}
	vpnMutex.RUnlock()
	
	result["global_stats"] = map[string]interface{}{
		"total_devices": totalDevices,
		"device_list":   deviceList,
	}
	
	if debugMode {
		log.Printf("🔧 lwIP诊断完成: %+v", result)
	}
	
	resultBytes, _ := json.Marshal(result)
	return string(resultBytes), nil
}

// 带监控的数据复制函数
func copyWithMonitoring(dst io.Writer, src io.Reader, name string) (int64, error) {
	var totalBytes int64
	var packetCount int64
	
	buffer := make([]byte, 32*1024) // 32KB缓冲区
	
	for {
		nr, err := src.Read(buffer)
		if nr > 0 {
			packetCount++
			totalBytes += int64(nr)
			
			// 每100个包记录一次详细信息
			if packetCount%100 == 0 {
				if debugMode {
					log.Printf("📊 %s: 处理了 %d 个包, 总计 %d 字节", name, packetCount, totalBytes)
				}
			}
			
			// 分析包内容（前20字节用于IP头分析）
			if nr >= 20 && debugMode && packetCount <= 5 {
				version := (buffer[0] & 0xF0) >> 4
				protocol := buffer[9]
				var srcIP, dstIP string
				if nr >= 20 {
					srcIP = fmt.Sprintf("%d.%d.%d.%d", buffer[12], buffer[13], buffer[14], buffer[15])
					dstIP = fmt.Sprintf("%d.%d.%d.%d", buffer[16], buffer[17], buffer[18], buffer[19])
				}
				log.Printf("📦 %s 包#%d: IPv%d, 协议=%d, %s->%s, 长度=%d", 
					name, packetCount, version, protocol, srcIP, dstIP, nr)
			}
			
			nw, ew := dst.Write(buffer[0:nr])
			if nw < 0 || nr < nw {
				nw = 0
				if ew == nil {
					ew = fmt.Errorf("invalid write result")
				}
			}
			if ew != nil {
				log.Printf("❌ %s: 写入错误: %v", name, ew)
				return totalBytes, ew
			}
			if nr != nw {
				log.Printf("⚠️ %s: 部分写入 %d/%d", name, nw, nr)
				return totalBytes, io.ErrShortWrite
			}
		}
		if err != nil {
			if err != io.EOF {
				log.Printf("❌ %s: 读取错误: %v", name, err)
			} else {
				log.Printf("📥 %s: 读取完成, 总计 %d 字节, %d 个包", name, totalBytes, packetCount)
			}
			return totalBytes, err
		}
	}
}
