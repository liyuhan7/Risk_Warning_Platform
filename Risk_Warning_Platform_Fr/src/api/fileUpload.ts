/**
 * 文件分片上传 API
 */
import request from '@/utils/request'
import CryptoJS from 'crypto-js'
import type { Result } from '@/types'

// 默认配置
const DEFAULT_CONFIG = {
  chunkSize: 1024 * 1024, // 1MB
  maxRetries: 3,
  retryDelay: 1000,
  maxConcurrency: 3,
  requestTimeout: 30000 // 30秒
}

// 初始化上传响应
export interface FileUploadInitResponse {
  uploadId: string
}

// 初始化上传请求参数
export interface FileUploadInitRequest {
  projectId: number
  fileHash: string
  fileSize: number
  totalChunks: number
  fileType?: string
  /** 原始文件名；后端必填，仅用于展示与证据回溯 */
  fileName: string
}

// 上传进度信息
export interface UploadProgressInfo {
  uploaded: number
  total: number
  percentage: number
  chunkIndex: number
}

// 上传结果
export interface UploadResult {
  success: boolean
  message: string | null
  uploadId: string
  fileHash: string
  totalChunks: number
  fileSize: number
  fileName: string
  fileType: string
}

/**
 * 计算文件hash值 - 使用MD5
 * @param file - 文件对象
 * @param onProgress - 进度回调函数
 * @returns 文件的MD5哈希值
 */
export const calculateFileHash = (file: File, onProgress?: (progress: number) => void): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    const chunkSize = 2097152 // 2MB chunks for hash calculation
    let currentChunk = 0
    const chunks = Math.ceil(file.size / chunkSize)
    const hash = CryptoJS.algo.MD5.create()

    const loadNext = () => {
      const start = currentChunk * chunkSize
      const end = Math.min(start + chunkSize, file.size)

      reader.onload = (e) => {
        const wordArray = CryptoJS.lib.WordArray.create(e.target?.result as ArrayBuffer)
        hash.update(wordArray)

        currentChunk++

        if (onProgress) {
          onProgress(Math.round((currentChunk / chunks) * 100))
        }

        if (currentChunk < chunks) {
          loadNext()
        } else {
          const hashResult = hash.finalize()
          resolve(hashResult.toString())
        }
      }

      reader.onerror = reject
      reader.readAsArrayBuffer(file.slice(start, end))
    }

    loadNext()
  })
}

/**
 * 延迟函数
 * @param ms - 延迟毫秒数
 */
const delay = (ms: number): Promise<void> => new Promise(resolve => setTimeout(resolve, ms))

/**
 * 带重试机制的异步函数执行器
 */
const retryAsync = async <T>(
  fn: () => Promise<T>,
  maxRetries: number = DEFAULT_CONFIG.maxRetries,
  retryDelay: number = DEFAULT_CONFIG.retryDelay
): Promise<T> => {
  let lastError: Error | undefined

  for (let i = 0; i <= maxRetries; i++) {
    try {
      return await fn()
    } catch (error: any) {
      lastError = error

      if (i < maxRetries) {
        console.warn(`操作失败，${retryDelay}ms后进行第${i + 1}次重试:`, error.message)
        await delay(retryDelay)
        retryDelay *= 2 // 指数退避
      }
    }
  }

  throw lastError
}

/**
 * 并发控制器
 */
class ConcurrencyController {
  private maxConcurrency: number
  private running: number
  private queue: Array<{
    fn: () => Promise<any>
    resolve: (value: any) => void
    reject: (reason?: any) => void
  }>

  constructor(maxConcurrency: number = DEFAULT_CONFIG.maxConcurrency) {
    this.maxConcurrency = maxConcurrency
    this.running = 0
    this.queue = []
  }

  async execute<T>(fn: () => Promise<T>): Promise<T> {
    return new Promise((resolve, reject) => {
      this.queue.push({
        fn,
        resolve,
        reject
      })
      this.process()
    })
  }

  private async process(): Promise<void> {
    if (this.running >= this.maxConcurrency || this.queue.length === 0) {
      return
    }

    this.running++
    const item = this.queue.shift()!
    const { fn, resolve, reject } = item

    try {
      const result = await fn()
      resolve(result)
    } catch (error) {
      reject(error)
    } finally {
      this.running--
      this.process()
    }
  }
}

/**
 * 获取文件扩展名
 */
const getFileExtension = (fileName: string): string => {
  if (!fileName) {
    return ''
  }

  const lastDotIndex = fileName.lastIndexOf('.')
  if (lastDotIndex === -1 || lastDotIndex === fileName.length - 1) {
    return ''
  }

  return fileName.substring(lastDotIndex + 1).toLowerCase()
}

/**
 * 将文件分片
 */
export const createFileChunks = (file: File, chunkSize: number = 1024 * 1024): Array<{
  chunk: Blob
  index: number
  start: number
  end: number
}> => {
  const chunks: Array<{
    chunk: Blob
    index: number
    start: number
    end: number
  }> = []
  let start = 0
  let chunkIndex = 0

  while (start < file.size) {
    const end = Math.min(start + chunkSize, file.size)
    const chunk = file.slice(start, end)
    chunks.push({
      chunk,
      index: chunkIndex,
      start,
      end
    })
    start = end
    chunkIndex++
  }

  return chunks
}

/**
 * 初始化文件上传
 */
export const initFileUpload = (data: FileUploadInitRequest): Promise<Result<FileUploadInitResponse>> => {
  return request.post('org/file/initUpload', data)
}

/**
 * 上传文件分片
 */
export const uploadFileChunk = async (params: {
  projectId: number
  uploadId: string
  chunkIndex: number
  chunkData: Blob
}): Promise<Result<string>> => {
  const { projectId, uploadId, chunkIndex, chunkData } = params

  const formData = new FormData()
  formData.append('projectId', projectId.toString())
  formData.append('uploadId', uploadId)
  formData.append('chunkIndex', chunkIndex.toString())
  formData.append('chunkData', chunkData, `chunk_${chunkIndex}`)

  return request.post('org/file/uploadChunk', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    },
    timeout: DEFAULT_CONFIG.requestTimeout
  })
}

/**
 * 确认文件上传完成
 */
export const confirmFileUpload = (projectId: number): Promise<Result<string>> => {
  return request.post('org/file/confirmUpload', null, {
    params: { projectId }
  })
}

/**
 * 删除上传的文件
 */
export const deleteUploadFile = (projectId: number, uploadId: string): Promise<Result<string>> => {
  return request.post('org/file/deleteFile', null, {
    params: { projectId, uploadId }
  })
}

/**
 * 完整的文件分片上传流程
 */
export const uploadFileWithChunks = async (params: {
  file: File
  projectId: number
  chunkSize?: number
  maxConcurrency?: number
  onProgress?: (info: UploadProgressInfo) => void
  onHashProgress?: (progress: number) => void
  onChunkSuccess?: (chunkIndex: number, uploaded: number, total: number) => void
  onChunkError?: (chunkIndex: number, error: Error) => void
  autoConfirm?: boolean
}): Promise<UploadResult> => {
  const {
    file,
    projectId,
    chunkSize = DEFAULT_CONFIG.chunkSize,
    maxConcurrency = DEFAULT_CONFIG.maxConcurrency,
    onProgress,
    onHashProgress,
    onChunkSuccess,
    onChunkError,
    autoConfirm = false
  } = params

  try {
    // 1. 获取文件类型
    const fileType = getFileExtension(file.name)
    console.log('文件类型:', fileType || '未知')

    // 2. 计算文件哈希
    console.log('开始计算文件哈希...')
    const fileHash = await calculateFileHash(file, onHashProgress)
    console.log('文件哈希计算完成:', fileHash)

    // 3. 创建文件分片
    const chunks = createFileChunks(file, chunkSize)
    console.log(`文件分片完成，共 ${chunks.length} 个分片`)

    // 4. 初始化上传
    const initResult = await initFileUpload({
      projectId,
      fileHash,
      fileSize: file.size,
      totalChunks: chunks.length,
      fileType,
      fileName: file.name
    })
    const uploadId = initResult.data.uploadId
    console.log('初始化上传成功，uploadId:', uploadId)

    let uploadedChunks = 0
    const failedChunks: Array<{ index: number; error: Error }> = []
    const concurrencyController = new ConcurrencyController(maxConcurrency)

    // 5. 并发上传所有分片
    const uploadPromises = chunks.map(({ chunk, index }) =>
      concurrencyController.execute(async () => {
        try {
          await retryAsync(() => uploadFileChunk({
            projectId,
            uploadId,
            chunkIndex: index,
            chunkData: chunk
          }))

          uploadedChunks++

          if (onProgress) {
            onProgress({
              uploaded: uploadedChunks,
              total: chunks.length,
              percentage: Math.round((uploadedChunks / chunks.length) * 100),
              chunkIndex: index
            })
          }

          if (onChunkSuccess) {
            onChunkSuccess(index, uploadedChunks, chunks.length)
          }

          return { index, success: true }

        } catch (error: any) {
          failedChunks.push({ index, error })

          if (onChunkError) {
            onChunkError(index, error)
          }

          return { index, success: false, error }
        }
      })
    )

    // 等待所有分片上传完成
    await Promise.allSettled(uploadPromises)

    // 6. 检查是否有失败的分片
    if (failedChunks.length > 0) {
      const errorMessage = `${failedChunks.length} 个分片上传失败: ${failedChunks.map(f => f.index).join(', ')}`
      console.error(errorMessage)
      throw new Error(errorMessage)
    }

    // 7. 只在autoConfirm为true时才确认上传
    let confirmMessage: string | null = null
    if (autoConfirm) {
      const result = await confirmFileUpload(projectId)
      confirmMessage = result.data
      console.log('文件上传完成:', confirmMessage)
    } else {
      console.log('所有分片上传完成，等待确认上传')
    }

    return {
      success: true,
      message: confirmMessage,
      uploadId,
      fileHash,
      totalChunks: chunks.length,
      fileSize: file.size,
      fileName: file.name,
      fileType
    }

  } catch (error) {
    console.error('文件分片上传失败:', error)
    throw error
  }
}

