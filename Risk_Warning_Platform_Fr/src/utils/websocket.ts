/**
 * WebSocket 连接管理工具
 * 用于与后端建立实时通信连接
 */
import { ElMessage, ElNotification } from 'element-plus'
import router from '@/router'

// WebSocket 消息类型定义
export interface WebSocketMessage {
  type: string
  [key: string]: any
}

// 通知消息类型
export interface NotificationMessage {
  type: 'notification'
  notificationType: string
  title: string
  content: string
  projectId?: number
  assessmentId?: number
  timestamp?: number
  extraData?: Record<string, any>
}

// 绑定响应消息类型
export interface BindResponse {
  type: 'bind_success' | 'bind_error'
  userId: number | null
  message: string
}

// WebSocket配置
interface WebSocketConfig {
  url: string
  reconnectInterval: number
  maxReconnectAttempts: number
  heartbeatInterval: number
}

// 获取WebSocket URL
const getWebSocketUrl = (): string => {
  // 优先使用环境变量配置
  if (import.meta.env.VITE_WEBSOCKET_URL) {
    return import.meta.env.VITE_WEBSOCKET_URL
  }
  // 默认使用本地开发地址
  return 'ws://localhost:9090/ws'
}

const DEFAULT_CONFIG: WebSocketConfig = {
  url: getWebSocketUrl(),
  reconnectInterval: 3000,
  maxReconnectAttempts: 5,
  heartbeatInterval: 30000
}

class WebSocketService {
  private ws: WebSocket | null = null
  private config: WebSocketConfig
  private reconnectAttempts: number = 0
  private heartbeatTimer: number | null = null
  private reconnectTimer: number | null = null
  private userId: number | null = null
  private isManualClose: boolean = false
  private messageHandlers: Map<string, ((message: any) => void)[]> = new Map()

  constructor(config: Partial<WebSocketConfig> = {}) {
    this.config = { ...DEFAULT_CONFIG, ...config }
  }

  /**
   * 连接 WebSocket
   * @param userId 用户ID，用于绑定连接
   */
  connect(userId: number): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      console.log('WebSocket already connected')
      return
    }

    this.userId = userId
    this.isManualClose = false
    this.reconnectAttempts = 0

    try {
      this.ws = new WebSocket(this.config.url)
      this.setupEventListeners()
    } catch (error) {
      console.error('Failed to create WebSocket connection:', error)
      this.scheduleReconnect()
    }
  }

  /**
   * 设置事件监听器
   */
  private setupEventListeners(): void {
    if (!this.ws) return

    this.ws.onopen = () => {
      console.log('WebSocket connected')
      this.reconnectAttempts = 0

      // 连接成功后绑定用户
      if (this.userId) {
        this.bindUser(this.userId)
      }

      // 启动心跳
      this.startHeartbeat()
    }

    this.ws.onmessage = (event: MessageEvent) => {
      try {
        const message: WebSocketMessage = JSON.parse(event.data)
        this.handleMessage(message)
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    }

    this.ws.onclose = (event: CloseEvent) => {
      console.log('WebSocket closed:', event.code, event.reason)
      this.stopHeartbeat()

      if (!this.isManualClose) {
        this.scheduleReconnect()
      }
    }

    this.ws.onerror = (error: Event) => {
      console.error('WebSocket error:', error)
    }
  }

  /**
   * 处理收到的消息
   */
  private handleMessage(message: WebSocketMessage): void {
    console.log('Received WebSocket message:', message)

    const { type } = message

    // 处理绑定响应
    if (type === 'bind_success') {
      console.log('User bound successfully')
      ElMessage.success('实时通信连接已建立')
    } else if (type === 'bind_error') {
      console.error('User bind failed:', (message as BindResponse).message)
      ElMessage.error('实时通信绑定失败')
    } else if (type === 'pong') {
      // 心跳响应，不做特殊处理
      console.log('Heartbeat pong received')
    } else if (type === 'notification') {
      // 处理通知消息
      this.handleNotification(message as NotificationMessage)
    }

    // 调用注册的消息处理器
    const handlers = this.messageHandlers.get(type)
    if (handlers) {
      handlers.forEach(handler => handler(message))
    }

    // 调用通用处理器
    const allHandlers = this.messageHandlers.get('*')
    if (allHandlers) {
      allHandlers.forEach(handler => handler(message))
    }
  }

  /**
   * 处理通知消息
   */
  private handleNotification(notification: NotificationMessage): void {
    const { notificationType, title, content, assessmentId } = notification

    // 检查当前是否在评估结果页面等待中
    const currentRoute = router.currentRoute.value
    const isOnWaitingPage = currentRoute.path === '/assessment-result' && currentRoute.query.waiting === 'true'

    // 显示通知
    ElNotification({
      title: title || '系统通知',
      message: content || '您有新的消息',
      type: this.getNotificationType(notificationType),
      duration: 5000,
      onClick: () => {
        // 点击通知跳转到评估结果页面
        if (assessmentId && !isOnWaitingPage) {
          router.push({
            path: '/assessment-result',
            query: { assessmentId: assessmentId.toString() }
          })
        }
      }
    })

    // 根据通知类型处理跳转
    if (notificationType === 'ASSESSMENT_COMPLETED' || notificationType === 'ASSESSMENT_COMPLETE' || notificationType === 'PROCESSING_COMPLETE') {
      // 评估完成
      // 如果用户已经在评估结果页面等待，不需要自动跳转（由页面自己处理）
      if (assessmentId && !isOnWaitingPage) {
        ElMessage.success('评估已完成，正在跳转到结果页面...')
        setTimeout(() => {
          router.push({
            path: '/assessment-result',
            query: { assessmentId: assessmentId.toString() }
          })
        }, 1500)
      }
    } else if (notificationType === 'PROCESSING_STARTED') {
      ElMessage.info('评估已开始，正在处理中...')
    }
  }

  /**
   * 获取通知类型对应的 Element Plus 类型
   */
  private getNotificationType(notificationType: string): 'success' | 'warning' | 'info' | 'error' {
    const typeMap: Record<string, 'success' | 'warning' | 'info' | 'error'> = {
      'ASSESSMENT_COMPLETED': 'success',
      'ASSESSMENT_COMPLETE': 'success',
      'PROCESSING_COMPLETE': 'success',
      'PROCESSING_STARTED': 'info',
      'RISK_WARNING': 'warning',
      'SYSTEM_NOTIFICATION': 'info',
      'QUESTIONNAIRE_REMINDER': 'info',
      'ERROR': 'error',
      'WARNING': 'warning'
    }
    return typeMap[notificationType] || 'info'
  }

  /**
   * 绑定用户
   */
  private bindUser(userId: number): void {
    this.send({
      type: 'bind',
      userId: userId
    })
  }

  /**
   * 发送消息
   */
  send(message: WebSocketMessage): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message))
    } else {
      console.warn('WebSocket is not connected, message not sent:', message)
    }
  }

  /**
   * 启动心跳
   */
  private startHeartbeat(): void {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      this.send({ type: 'ping' })
    }, this.config.heartbeatInterval)
  }

  /**
   * 停止心跳
   */
  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  /**
   * 安排重连
   */
  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.config.maxReconnectAttempts) {
      console.log('Max reconnect attempts reached')
      ElMessage.warning('实时通信连接失败，请刷新页面重试')
      return
    }

    this.reconnectAttempts++
    console.log(`Scheduling reconnect attempt ${this.reconnectAttempts}/${this.config.maxReconnectAttempts}`)

    this.reconnectTimer = window.setTimeout(() => {
      if (this.userId) {
        console.log('Attempting to reconnect...')
        this.connect(this.userId)
      }
    }, this.config.reconnectInterval)
  }

  /**
   * 注册消息处理器
   * @param type 消息类型，使用 '*' 表示处理所有消息
   * @param handler 处理函数
   */
  on(type: string, handler: (message: any) => void): void {
    if (!this.messageHandlers.has(type)) {
      this.messageHandlers.set(type, [])
    }
    this.messageHandlers.get(type)!.push(handler)
  }

  /**
   * 移除消息处理器
   */
  off(type: string, handler?: (message: any) => void): void {
    if (!handler) {
      this.messageHandlers.delete(type)
    } else {
      const handlers = this.messageHandlers.get(type)
      if (handlers) {
        const index = handlers.indexOf(handler)
        if (index > -1) {
          handlers.splice(index, 1)
        }
      }
    }
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    this.isManualClose = true
    this.stopHeartbeat()

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }

    if (this.ws) {
      this.ws.close()
      this.ws = null
    }

    this.userId = null
    this.messageHandlers.clear()
    console.log('WebSocket disconnected')
  }

  /**
   * 检查连接状态
   */
  isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN
  }
}

// 导出单例
export const websocketService = new WebSocketService()

export default websocketService
