package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true // 允许所有来源，生产环境应限制
	},
}

// 消息类型定义
const (
	TypeRegister       = "register"       // 注册/连接（被控端）
	TypeJoin           = "join"            // 加入房间（控制端）
	TypeOffer          = "offer"           // WebRTC Offer
	TypeAnswer         = "answer"          // WebRTC Answer
	TypeCandidate      = "candidate"       // ICE Candidate
	TypeError          = "error"           // 错误消息
	TypeRoomReady      = "room_ready"      // 房间就绪
	TypeDisconnect     = "disconnect"      // 主动断开
	TypePing           = "ping"            // 心跳
	TypePong           = "pong"            // 心跳响应
)

// WSMessage WebSocket 消息结构
type WSMessage struct {
	Type    string          `json:"type"`
	RoomID  string          `json:"room_id,omitempty"`
	Data    json.RawMessage `json:"data,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"` // 存放 SDP/ICE 等数据
}

// PayloadData 通用 payload 结构
type PayloadData struct {
	Message string `json:"message,omitempty"`
	SDP     string `json:"sdp,omitempty"`
	Candidate string `json:"candidate,omitempty"`
	Mid     string `json:"mid,omitempty"`
	MLineIndex int    `json:"m_line_index,omitempty"`
}

// Room 房间结构
type Room struct {
	mu    sync.RWMutex
	Host  *Client
	Guest *Client
	ID    string
}

// Client WebSocket 客户端连接
type Client struct {
	conn     *websocket.Conn
	mu       sync.Mutex
	roomID   string
	role     string // "host" 或 "guest"
	sendChan chan []byte
}

// Hub 信令服务器核心
type Hub struct {
	rooms      map[string]*Room
	clients    map[*Client]bool
	mu         sync.RWMutex
	register   chan *Client
	unregister chan *Client
	broadcast  chan []byte
}

func newHub() *Hub {
	return &Hub{
		rooms:      make(map[string]*Room),
		clients:    make(map[*Client]bool),
		register:   make(chan *Client),
		unregister: make(chan *Client),
	}
}

// 生成 6 位不重复数字 RoomID
func generateRoomID() string {
	for i := 0; i < 100; i++ {
		id := fmt.Sprintf("%06d", rand.Intn(1000000))
		if _, ok := rooms[id]; !ok {
			return id
		}
	}
	return ""
}

var rooms = make(map[string]*Room)
var hub = newHub()

// 处理 WebSocket 连接
func handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket 升级失败: %v", err)
		return
	}

	client := &Client{
		conn:     conn,
		sendChan: make(chan []byte, 256),
		role:     "",
	}

	hub.mu.Lock()
	hub.clients[client] = true
	hub.mu.Unlock()

	go client.writePump()
	go client.readPump()
}

// 读取泵 - 处理接收到的消息
func (c *Client) readPump() {
	defer func() {
		c.cleanup()
		c.conn.Close()
	}()

	c.conn.SetReadLimit(65536)
	c.conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	c.conn.SetPongHandler(func(string) error {
		c.conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	for {
		_, message, err := c.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				log.Printf("读取消息错误: %v", err)
			}
			break
		}

		c.handleMessage(message)
	}
}

// 写入泵 - 处理发送消息
func (c *Client) writePump() {
	ticker := time.NewTicker(30 * time.Second)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()

	for {
		select {
		case message, ok := <-c.sendChan:
			c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if !ok {
				c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			c.mu.Lock()
			err := c.conn.WriteMessage(websocket.TextMessage, message)
			c.mu.Unlock()
			if err != nil {
				log.Printf("发送消息失败: %v", err)
				return
			}

		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// 处理各类消息
func (c *Client) handleMessage(message []byte) {
	var msg WSMessage
	if err := json.Unmarshal(message, &msg); err != nil {
		log.Printf("JSON 解析失败: %v", err)
		c.sendError("无效的 JSON 格式")
		return
	}

	switch msg.Type {
	case TypeRegister:
		c.handleRegister(msg)
	case TypeJoin:
		c.handleJoin(msg)
	case TypeOffer:
		c.forwardMessage(msg, TypeOffer)
	case TypeAnswer:
		c.forwardMessage(msg, TypeAnswer)
	case TypeCandidate:
		c.forwardCandidate(msg)
	case TypePing:
		c.sendJSON(WSMessage{Type: TypePong})
	case TypeDisconnect:
		c.handleDisconnect()
	default:
		c.sendError("未知消息类型: " + msg.Type)
	}
}

// 被控端注册，生成 6 位 RoomID
func (c *Client) handleRegister(msg WSMessage) {
	roomID := generateRoomID()
	room := &Room{
		ID:    roomID,
		Host:  c,
		Guest: nil,
	}

	hub.mu.Lock()
	rooms[roomID] = room
	c.role = "host"
	c.roomID = roomID
	hub.mu.Unlock()

	log.Printf("被控端注册成功，RoomID: %s", roomID)
	c.sendJSON(WSMessage{
		Type:   TypeRoomReady,
		RoomID: roomID,
		Data:   mustMarshal(PayloadData{Message: "房间已创建，等待控制端连接..."}),
	})
}

// 控制端加入房间
func (c *Client) handleJoin(msg WSMessage) {
	roomID := msg.RoomID
	if roomID == "" {
		c.sendError("RoomID 不能为空")
		return
	}

	hub.mu.Lock()
	room, exists := rooms[roomID]
	if !exists {
		hub.mu.Unlock()
		c.sendError("房间不存在或已过期")
		return
	}

	if room.Host == nil {
		hub.mu.Unlock()
		c.sendError("房间暂无被控端")
		return
	}

	room.Guest = c
	c.role = "guest"
	c.roomID = roomID
	hub.mu.Unlock()

	log.Printf("控制端加入房间: %s", roomID)

	// 通知被控端有控制端连入
	room.Host.sendJSON(WSMessage{
		Type:   TypeRoomReady,
		RoomID: roomID,
		Data:   mustMarshal(PayloadData{Message: "控制端已连接，请等待..."}),
	})

	// 通知控制端房间就绪
	c.sendJSON(WSMessage{
		Type:   TypeRoomReady,
		RoomID: roomID,
		Data:   mustMarshal(PayloadData{Message: "已连接到被控端，开始建立连接..."}),
	})
}

// 转发 WebRTC 消息（Offer/Answer）
func (c *Client) forwardMessage(msg WSMessage, msgType string) {
	hub.mu.RLock()
	room, exists := rooms[c.roomID]
	hub.mu.RUnlock()

	if !exists {
		c.sendError("房间不存在")
		return
	}

	var target *Client
	if c.role == "host" {
		target = room.Guest
	} else {
		target = room.Host
	}

	if target == nil {
		c.sendError("对端未连接")
		return
	}

	forwardMsg := WSMessage{
		Type:    msgType,
		RoomID:  c.roomID,
		Payload: msg.Payload,
	}
	target.sendJSON(forwardMsg)
	log.Printf("转发 %s 到 %s", msgType, target.role)
}

// 转发 ICE Candidate
func (c *Client) forwardCandidate(msg WSMessage) {
	c.forwardMessage(msg, TypeCandidate)
}

// 处理断开连接
func (c *Client) handleDisconnect() {
	c.cleanup()
}

// 清理资源
func (c *Client) cleanup() {
	hub.mu.Lock()
	delete(hub.clients, c)

	if c.roomID != "" {
		if room, exists := rooms[c.roomID]; exists {
			room.mu.Lock()
			if c.role == "host" {
				room.Host = nil
				// 通知控制端被控端已断开
				if room.Guest != nil {
					room.Guest.sendJSON(WSMessage{
						Type:  TypeError,
						Data:  mustMarshal(PayloadData{Message: "被控端已断开连接"}),
					})
					room.Guest.role = ""
					room.Guest.roomID = ""
				}
			} else if c.role == "guest" {
				room.Guest = nil
				// 通知被控端控制端已断开
				if room.Host != nil {
					room.Host.sendJSON(WSMessage{
						Type:  TypeError,
						Data:  mustMarshal(PayloadData{Message: "控制端已断开连接"}),
					})
				}
			}
			room.mu.Unlock()

			// 如果房间为空，删除房间
			room.mu.RLock()
			if room.Host == nil && room.Guest == nil {
				delete(rooms, c.roomID)
				log.Printf("房间 %s 已删除", c.roomID)
			}
			room.mu.RUnlock()
		}
	}
	hub.mu.Unlock()

	log.Printf("%s 断开连接，RoomID: %s", c.role, c.roomID)
}

// 发送 JSON 消息
func (c *Client) sendJSON(msg WSMessage) {
	data, err := json.Marshal(msg)
	if err != nil {
		log.Printf("JSON 序列化失败: %v", err)
		return
	}

	select {
	case c.sendChan <- data:
	default:
		log.Printf("发送通道已满: %s", c.roomID)
	}
}

// 发送错误消息
func (c *Client) sendError(errMsg string) {
	c.sendJSON(WSMessage{
		Type: TypeError,
		Data: mustMarshal(PayloadData{Message: errMsg}),
	})
}

// 辅助函数：JSON 序列化（带错误处理）
func mustMarshal(v interface{}) json.RawMessage {
	data, err := json.Marshal(v)
	if err != nil {
		log.Printf("序列化失败: %v", err)
		return nil
	}
	return data
}

// 启动清理协程，定期清理过期房间
func startCleanupTask() {
	ticker := time.NewTicker(5 * time.Minute)
	go func() {
		for range ticker.C {
			hub.mu.Lock()
			now := time.Now()
			for roomID, room := range rooms {
				room.mu.RLock()
				if room.Host == nil && room.Guest == nil {
					delete(rooms, roomID)
					log.Printf("清理过期房间: %s", roomID)
				}
				_ = now // 避免未使用警告
				room.mu.RUnlock()
			}
			hub.mu.Unlock()
		}
	}()
}

func main() {
	// 启动清理任务
	startCleanupTask()

	// WebSocket 端点
	http.HandleFunc("/ws", handleWS)

	// 健康检查端点
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":  "ok",
			"rooms":   len(rooms),
			"clients": len(hub.clients),
		})
	})

	addr := ":8081"
	log.Printf("EasyScreen 信令服务启动，监听 %s", addr)
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("服务器启动失败: %v", err)
	}
}
