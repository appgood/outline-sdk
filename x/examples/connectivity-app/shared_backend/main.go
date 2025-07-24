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
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"runtime"
	"strconv"
	"strings"
	"time"

	"github.com/Jigsaw-Code/outline-sdk/dns"
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
