package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	mathrand "math/rand"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

// 服务器运行期统计（累计计数 + 启动时间），全部为匿名聚合数据
var (
	serverStartTime   = time.Now()
	totalRoomsCreated int64 // 累计创建房间数（token 重连复用不计）
	totalGuestsJoined int64 // 累计观看端加入次数
)

// 版本信息：编译时通过 -ldflags "-X main.appVersion=... -X main.gitCommit=..." 注入
var (
	appVersion = "dev"
	gitCommit  = "unknown"
	buildTime  = "unknown"
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
	TypeGuestJoin      = "guest_join"      // 服 → Host：通知有新 Guest 加入
	TypeGuestLeave     = "guest_leave"     // 服 → Host：通知 Guest 离开
	TypeKickGuest      = "kick_guest"      // Host → 服 → Guest：房主踢人
)

// WSMessage WebSocket 消息结构
type WSMessage struct {
	Type    string          `json:"type"`
	RoomID  string          `json:"room_id,omitempty"`
	Data    json.RawMessage `json:"data,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"` // 存放 SDP/ICE 等数据
}

// PayloadData 通用 payload 结构
// 注意：SDP/Candidate 等转发消息会同时携带 GuestID 以区分多 Guest 场景
type PayloadData struct {
	Message    string `json:"message,omitempty"`
	SDP        string `json:"sdp,omitempty"`
	Candidate  string `json:"candidate,omitempty"`
	Mid        string `json:"mid,omitempty"`
	MLineIndex int    `json:"m_line_index,omitempty"`
	Token      string `json:"token,omitempty"`      // register: 持久 Host token
	MaxGuests  int    `json:"max_guests,omitempty"` // register: 房主设置的最大观众数（默认 5）
	GuestID    string `json:"guest_id,omitempty"`   // 多 Guest 场景的路由 key
}

// Room 房间结构 —— 1 Host + N Guests
type Room struct {
	mu                 sync.RWMutex
	Host               *Client
	Guests             map[string]*Client // guestId → guest 连接
	MaxGuests          int
	ID                 string
	HostToken          string
	HostDisconnectedAt time.Time
}

const (
	hostReconnectTTL = 60 * time.Second
	defaultMaxGuests = 5
	hardCapGuests    = 32 // 服务端硬上限保护
)

// Client WebSocket 客户端连接
type Client struct {
	conn     *websocket.Conn
	mu       sync.Mutex
	roomID   string
	role     string // "host" 或 "guest"
	guestID  string // 仅 role==guest 时有值
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
		id := fmt.Sprintf("%06d", mathrand.Intn(1000000))
		if _, ok := rooms[id]; !ok {
			return id
		}
	}
	return ""
}

// 生成 16 字节随机 guestId（hex 编码 32 字符）
func generateGuestID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		// 退路：用时间戳 + math/rand
		return fmt.Sprintf("g%x%x", time.Now().UnixNano(), mathrand.Int63())
	}
	return hex.EncodeToString(b)
}

var rooms = make(map[string]*Room)
var tokenToRoom = make(map[string]string) // host token → roomId
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
	case TypeKickGuest:
		c.handleKickGuest(msg)
	default:
		c.sendError("未知消息类型: " + msg.Type)
	}
}

// 房主踢出 Guest：仅 Host 可调用，payload 必须含 guest_id
func (c *Client) handleKickGuest(msg WSMessage) {
	if c.role != "host" {
		c.sendError("只有 Host 能踢人")
		return
	}
	var p map[string]interface{}
	if len(msg.Payload) > 0 {
		_ = json.Unmarshal(msg.Payload, &p)
	}
	gid, _ := p["guest_id"].(string)
	if gid == "" {
		c.sendError("kick_guest 缺少 guest_id")
		return
	}
	hub.mu.RLock()
	room := rooms[c.roomID]
	hub.mu.RUnlock()
	if room == nil {
		return
	}
	room.mu.RLock()
	guest := room.Guests[gid]
	room.mu.RUnlock()
	if guest == nil {
		return
	}
	// 通知 Guest 自己已被踢
	guest.sendJSON(WSMessage{
		Type: TypeError,
		Data: mustMarshal(PayloadData{Message: "您已被房主断开连接"}),
	})
	log.Printf("Host 踢出 Guest %s from room %s", gid[:8], c.roomID)
	// 主动关闭 Guest 的 ws → 触发 cleanup → 通知 Host guest_leave
	_ = guest.conn.Close()
}

// 被控端注册：若 payload 携带 token，且该 token 之前的房间仍在 TTL 内，复用旧 roomID
func (c *Client) handleRegister(msg WSMessage) {
	var token string
	maxGuests := defaultMaxGuests
	if len(msg.Payload) > 0 {
		var p PayloadData
		if err := json.Unmarshal(msg.Payload, &p); err == nil {
			token = p.Token
			if p.MaxGuests > 0 {
				maxGuests = p.MaxGuests
			}
		}
	}
	if maxGuests > hardCapGuests {
		maxGuests = hardCapGuests
	}

	hub.mu.Lock()

	// 用 token 恢复旧房间
	if token != "" {
		if oldRoomID, ok := tokenToRoom[token]; ok {
			if room, exists := rooms[oldRoomID]; exists {
				room.mu.Lock()
				inTTL := !room.HostDisconnectedAt.IsZero() &&
					time.Since(room.HostDisconnectedAt) < hostReconnectTTL
				canRestore := room.Host == nil && (inTTL || room.HostDisconnectedAt.IsZero())
				if canRestore {
					room.Host = c
					room.HostDisconnectedAt = time.Time{}
					room.MaxGuests = maxGuests
					c.role = "host"
					c.roomID = oldRoomID

					// 给 Host 补发当前活跃 Guest 列表（让 Host 端能恢复观众视图）
					guestIDs := make([]string, 0, len(room.Guests))
					for gid := range room.Guests {
						guestIDs = append(guestIDs, gid)
					}
					room.mu.Unlock()
					hub.mu.Unlock()

					log.Printf("Host 复用 token=%s 重连 RoomID=%s (current guests=%d)",
						shortToken(token), oldRoomID, len(guestIDs))
					c.sendJSON(WSMessage{
						Type:   TypeRoomReady,
						RoomID: oldRoomID,
						Data:   mustMarshal(PayloadData{Message: "已恢复原连接码，可继续使用"}),
					})
					// 把每个仍在线的 Guest 重新通知 Host（host 会重新协商）
					for _, gid := range guestIDs {
						c.sendJSON(WSMessage{
							Type:    TypeGuestJoin,
							RoomID:  oldRoomID,
							Payload: mustMarshal(PayloadData{GuestID: gid, Message: "rejoin"}),
						})
					}
					return
				}
				room.mu.Unlock()
			}
			delete(tokenToRoom, token)
		}
	}

	roomID := generateRoomID()
	room := &Room{
		ID:        roomID,
		Host:      c,
		Guests:    make(map[string]*Client),
		MaxGuests: maxGuests,
		HostToken: token,
	}
	rooms[roomID] = room
	if token != "" {
		tokenToRoom[token] = roomID
	}
	c.role = "host"
	c.roomID = roomID
	atomic.AddInt64(&totalRoomsCreated, 1)
	hub.mu.Unlock()

	log.Printf("新房间 RoomID=%s token=%s maxGuests=%d",
		roomID, shortToken(token), maxGuests)
	c.sendJSON(WSMessage{
		Type:    TypeRoomReady,
		RoomID:  roomID,
		Payload: mustMarshal(PayloadData{MaxGuests: maxGuests}),
		Data:    mustMarshal(PayloadData{Message: "房间已创建，等待控制端连接..."}),
	})
}

func shortToken(t string) string {
	if t == "" {
		return "(none)"
	}
	if len(t) > 8 {
		return t[:8] + "…"
	}
	return t
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

	room.mu.Lock()
	if len(room.Guests) >= room.MaxGuests {
		room.mu.Unlock()
		hub.mu.Unlock()
		c.sendError(fmt.Sprintf("房间已满（最多 %d 个观众）", room.MaxGuests))
		return
	}
	gid := generateGuestID()
	room.Guests[gid] = c
	c.role = "guest"
	c.roomID = roomID
	c.guestID = gid
	guestCount := len(room.Guests)
	room.mu.Unlock()
	atomic.AddInt64(&totalGuestsJoined, 1)
	hub.mu.Unlock()

	log.Printf("Guest %s 加入 RoomID=%s（当前观众 %d/%d）", gid[:8], roomID, guestCount, room.MaxGuests)

	// 通知 Host
	room.Host.sendJSON(WSMessage{
		Type:    TypeGuestJoin,
		RoomID:  roomID,
		Payload: mustMarshal(PayloadData{GuestID: gid}),
	})

	// 通知 Guest 自己 + 携带其 guest_id 和已连接观众数
	c.sendJSON(WSMessage{
		Type:    TypeRoomReady,
		RoomID:  roomID,
		Payload: mustMarshal(PayloadData{GuestID: gid}),
		Data:    mustMarshal(PayloadData{Message: "已连接到被控端，开始建立连接..."}),
	})
}

// payload 反序列化辅助
func decodePayload(raw json.RawMessage) PayloadData {
	var p PayloadData
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &p)
	}
	return p
}

// 转发 WebRTC 消息（offer / answer / candidate）
// 多 Guest 路由（用 map[string]interface{} 做"零损耗"透传，避免解码到具体结构丢失字段）：
//   - Guest → Host：解码为通用 map，强制覆写 guest_id 后转发
//   - Host → Guest：从 payload 读 guest_id 路由，原 payload 不变直接转发
func (c *Client) forwardMessage(msg WSMessage, msgType string) {
	hub.mu.RLock()
	room, exists := rooms[c.roomID]
	hub.mu.RUnlock()
	if !exists {
		c.sendError("房间不存在")
		return
	}

	if c.role == "guest" {
		var p map[string]interface{}
		if len(msg.Payload) > 0 {
			_ = json.Unmarshal(msg.Payload, &p)
		}
		if p == nil {
			p = make(map[string]interface{})
		}
		p["guest_id"] = c.guestID
		newPayload, err := json.Marshal(p)
		if err != nil {
			log.Printf("forward: 重新序列化失败: %v", err)
			return
		}
		if room.Host == nil {
			c.sendError("对端未连接")
			return
		}
		room.Host.sendJSON(WSMessage{
			Type:    msgType,
			RoomID:  c.roomID,
			Payload: newPayload,
		})
	} else if c.role == "host" {
		var p map[string]interface{}
		if len(msg.Payload) > 0 {
			_ = json.Unmarshal(msg.Payload, &p)
		}
		gid, _ := p["guest_id"].(string)
		if gid == "" {
			c.sendError("缺少 guest_id")
			return
		}
		room.mu.RLock()
		guest := room.Guests[gid]
		room.mu.RUnlock()
		if guest == nil {
			return
		}
		guest.sendJSON(WSMessage{
			Type:    msgType,
			RoomID:  c.roomID,
			Payload: msg.Payload, // 透传，不重新序列化
		})
	}
}

func (c *Client) forwardCandidate(msg WSMessage) {
	c.forwardMessage(msg, TypeCandidate)
}

// 处理断开连接
func (c *Client) handleDisconnect() {
	c.cleanup()
}

// 清理资源
// Host 离线：不立即删房，留 hostReconnectTTL 宽限
// Guest 离线：从 Guests map 移除，通知 Host
func (c *Client) cleanup() {
	hub.mu.Lock()
	delete(hub.clients, c)

	if c.roomID != "" {
		if room, exists := rooms[c.roomID]; exists {
			room.mu.Lock()
			if c.role == "host" {
				room.Host = nil
				room.HostDisconnectedAt = time.Now()
				// 通知所有 Guest
				for _, g := range room.Guests {
					g.sendJSON(WSMessage{
						Type: TypeError,
						Data: mustMarshal(PayloadData{Message: "被控端已断开连接"}),
					})
				}
			} else if c.role == "guest" {
				// 同一个 client 可能因为协议异常多次 join 造成 Guests 里有多个 key 指向自己
				// 这里按 client 引用清掉所有残留 key（防止"踢人后数量不减"的现象）
				toDelete := make([]string, 0, 2)
				for gid, g := range room.Guests {
					if g == c {
						toDelete = append(toDelete, gid)
					}
				}
				for _, gid := range toDelete {
					delete(room.Guests, gid)
				}
				if room.Host != nil {
					// 只通知 Host 该 client 当前的 guestID（即客户端自己知道的那个）
					room.Host.sendJSON(WSMessage{
						Type:    TypeGuestLeave,
						RoomID:  c.roomID,
						Payload: mustMarshal(PayloadData{GuestID: c.guestID}),
					})
				}
			}
			hostStillReservable := room.HostToken != "" &&
				!room.HostDisconnectedAt.IsZero() &&
				time.Since(room.HostDisconnectedAt) < hostReconnectTTL
			room.mu.Unlock()

			if !hostStillReservable {
				room.mu.RLock()
				if room.Host == nil && len(room.Guests) == 0 {
					delete(rooms, c.roomID)
					if room.HostToken != "" {
						delete(tokenToRoom, room.HostToken)
					}
					log.Printf("房间 %s 已删除", c.roomID)
				}
				room.mu.RUnlock()
			}
		}
	}
	hub.mu.Unlock()

	if c.role == "guest" && c.guestID != "" {
		log.Printf("Guest %s 断开 RoomID=%s", c.guestID[:8], c.roomID)
	} else {
		log.Printf("%s 断开连接，RoomID: %s", c.role, c.roomID)
	}
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

// StatsSnapshot 服务器运行状态快照（全部为匿名聚合数据，不含 roomID/token/IP）
type StatsSnapshot struct {
	UptimeSeconds     int64 `json:"uptime_seconds"`      // 运行时长（秒）
	ActiveRooms       int   `json:"active_rooms"`        // 当前房间总数
	RoomsWithHost     int   `json:"rooms_with_host"`     // 被控端在线的房间数
	RoomsAwaitingHost int   `json:"rooms_awaiting_host"` // 被控端离线但 TTL 内等待重连的房间数
	TotalConnections  int   `json:"total_connections"`   // 当前 WebSocket 连接总数
	HostCount         int   `json:"host_count"`          // 当前在线被控端数
	GuestCount        int   `json:"guest_count"`         // 当前在线观看端数
	GuestDistribution []int  `json:"guest_distribution"`  // 各房间的观看端人数（匿名，仅含有观众的房间）
	TotalRoomsCreated int64  `json:"total_rooms_created"` // 累计创建房间数
	TotalGuestsJoined int64  `json:"total_guests_joined"` // 累计观看端加入次数
	Version           string `json:"version"`             // 服务端版本号
	GitCommit         string `json:"git_commit"`          // 构建时的 git 短哈希
}

// collectStats 采集当前服务器状态快照
// 锁顺序与 handleRegister/handleJoin/cleanup 一致（hub → room），不会死锁
func collectStats() StatsSnapshot {
	hub.mu.RLock()
	defer hub.mu.RUnlock()

	s := StatsSnapshot{
		UptimeSeconds:     int64(time.Since(serverStartTime).Seconds()),
		TotalConnections:  len(hub.clients),
		TotalRoomsCreated: atomic.LoadInt64(&totalRoomsCreated),
		TotalGuestsJoined: atomic.LoadInt64(&totalGuestsJoined),
		GuestDistribution: []int{},
		Version:           appVersion,
		GitCommit:         gitCommit,
	}
	now := time.Now()
	for _, room := range rooms {
		room.mu.RLock()
		s.ActiveRooms++
		if room.Host != nil {
			s.RoomsWithHost++
			s.HostCount++
		} else if !room.HostDisconnectedAt.IsZero() && now.Sub(room.HostDisconnectedAt) < hostReconnectTTL {
			s.RoomsAwaitingHost++
		}
		g := len(room.Guests)
		s.GuestCount += g
		if g > 0 {
			s.GuestDistribution = append(s.GuestDistribution, g)
		}
		room.mu.RUnlock()
	}
	return s
}

// 定期清理：
// 1) Host 离线时间超过 TTL 的房间（含 token 映射）
// 2) Host 和 Guest 都不在的孤儿房间
func startCleanupTask() {
	ticker := time.NewTicker(15 * time.Second)
	go func() {
		for range ticker.C {
			hub.mu.Lock()
			now := time.Now()
			for roomID, room := range rooms {
				room.mu.RLock()
				hostGone := room.Host == nil
				expired := !room.HostDisconnectedAt.IsZero() &&
					now.Sub(room.HostDisconnectedAt) >= hostReconnectTTL
				token := room.HostToken
				room.mu.RUnlock()

				// Host 已离线且 TTL 用尽 → 房间作废
				if hostGone && expired {
					delete(rooms, roomID)
					if token != "" {
						delete(tokenToRoom, token)
					}
					log.Printf("TTL 过期清理房间: %s (token=%s)", roomID, shortToken(token))
				}
			}
			hub.mu.Unlock()
		}
	}()
}

func main() {
	startCleanupTask()

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", handleWS)

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status":  "ok",
			"rooms":   len(rooms),
			"clients": len(hub.clients),
		})
	})

	// 版本信息（供 Web 端展示）
	mux.HandleFunc("/version", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(map[string]string{
			"version":    appVersion,
			"git_commit": gitCommit,
			"build_time": buildTime,
		})
	})

	// 匿名运行状态：JSON 数据接口 + 可视化页面
	mux.HandleFunc("/stats.json", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(collectStats())
	})
	mux.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(statsHTML))
	})

	// Web 预览客户端：静态资源托管在 ./web 目录
	// 通过浏览器访问 http://<host>:8081/ 即可作为 Guest 接入
	webFS := http.FileServer(http.Dir("./web"))
	mux.Handle("/", webFS)

	addr := os.Getenv("EASYSCREEN_ADDR")
	if addr == "" {
		addr = ":8081"
	}
	log.Printf("EasyScreen 信令服务启动 version=%s commit=%s build=%s，监听 %s",
		appVersion, gitCommit, buildTime, addr)
	log.Printf("  - WebSocket:  ws://<host>%s/ws", addr)
	log.Printf("  - Web 预览:   http://<host>%s/", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("服务器启动失败: %v", err)
	}
}
