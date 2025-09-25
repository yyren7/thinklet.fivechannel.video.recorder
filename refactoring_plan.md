# ThinkletRecorder 并发控制重构方案

## 概述

本重构方案旨在解决 ThinkletRecorder 中的竞态条件问题，确保相机状态的原子性操作，并在录像期间完全保护相机状态不被意外修改。

## 问题分析

### 当前存在的竞态条件

1. **检查-修改的原子性缺失**
   ```kotlin
   // 现有问题模式：
   if (isRecording()) {  // 检查
       return           // 可能在这里被中断
   }
   rebindUseCasesAsync() // 修改 - 存在时间窗口问题
   ```

2. **多层锁的不一致状态**
   - `recordingLock` 控制 `recording` 对象
   - `cameraBindingLock` 控制相机绑定
   - 两个锁之间存在状态不一致的时间窗口

3. **异步事件的无控制性**
   - 直播客户端连接/断开（原Vision）
   - Preview surface 变化
   - 录像结束后的状态恢复

4. **`startRecording` 执行期间的竞态窗口**
   - 在 `recording` 对象被设置之前，`isRecording()` 仍返回 `false`
   - 直播客户端事件可能在此窗口期间触发状态变更

## 重构方案

### 核心设计原则

1. **单一状态锁**：用一个锁控制所有相机相关的状态
2. **原子性检查-修改**：状态检查和修改在同一个锁内完成
3. **录像期间的完全保护**：录像时拒绝所有非录像控制的状态变更
4. **简化异步处理**：减少异步调用，增加同步控制
5. **统一命名风格**：`preview` 和 `streaming`（原vision）保持一致的命名规范

### 关键变更点

#### 1. 锁机制简化

**变更前**：
```kotlin
private val recordingLock: Lock = ReentrantLock()
private val cameraBindingLock = Mutex()
```

**变更后**：
```kotlin
private val cameraStateLock = Mutex()  // 统一的状态锁
```

#### 2. 状态管理集中化

**变更前**：
```kotlin
@GuardedBy("recordingLock")
private var recording: Recording? = null
private var visionUseCaseEnabled: Boolean
private var previewEnabled: Boolean
```

**变更后**：
```kotlin
@GuardedBy("cameraStateLock")
private var recording: Recording? = null
@GuardedBy("cameraStateLock")
private var previewEnabled: Boolean = false
@GuardedBy("cameraStateLock") 
private var streamingEnabled: Boolean = false  // 重命名
@GuardedBy("cameraStateLock")
private var isInTransition: Boolean = false    // 新增
```

#### 3. 异步操作同步化

**移除的方法**：
```kotlin
// 移除异步绑定方法
@MainThread
internal fun rebindUseCasesAsync() {
    lifecycleOwner.lifecycleScope.launch {
        performCameraBinding()
    }
}
```

**替换为同步方法**：
```kotlin
// 在锁内直接调用的同步方法
private suspend fun performCameraBindingUnsafe() {
    setRebinding(true)
    delay(100L)  // 减少延迟
    // ... 绑定逻辑
    setRebinding(false)
}
```

#### 4. 接口方法重构

##### 预览控制（现有方法增强）

```kotlin
suspend fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?): Boolean {
    return cameraStateLock.withLock {
        previewUseCase.setSurfaceProvider(surfaceProvider)
        
        val enabled = surfaceProvider != null
        if (previewEnabled == enabled) {
            return@withLock true
        }
        
        if (isRecordingUnsafe()) {
            showToast("Settings are frozen during recording")
            return@withLock false
        }
        
        previewEnabled = enabled
        performCameraBindingUnsafe()
        return@withLock true
    }
}
```

##### 直播控制（重命名并增强）

```kotlin
// 原 enableVisionUseCase -> setStreamingEnabled
suspend fun setStreamingEnabled(enabled: Boolean): Boolean {
    return cameraStateLock.withLock {
        if (streamingEnabled == enabled) {
            return@withLock true
        }
        
        if (isRecordingUnsafe()) {
            showToast("Settings are frozen during recording")
            return@withLock false
        }
        
        streamingEnabled = enabled
        performCameraBindingUnsafe()
        return@withLock true
    }
}
```

##### 录像控制（简化并增强）

```kotlin
suspend fun startRecording(outputFile: File, outputAudioFile: File): Boolean {
    return cameraStateLock.withLock {
        if (isRecordingUnsafe() || isInTransition) {
            return@withLock false
        }
        
        isInTransition = true
        try {
            // 保存当前状态用于恢复
            previewEnabledBeforeRecording = previewEnabled
            streamingEnabledBeforeRecording = streamingEnabled
            
            // 录像期间强制启用必要的功能
            streamingEnabled = true  // 录像时启用直播传输
            previewEnabled = true    // 录像时启用预览
            
            performCameraBindingUnsafe()
            
            // 开始录像
            val pendingRecording = recorder.prepareRecording(context, ...)
            recording = pendingRecording.start(...)
            
            return@withLock true
        } catch (e: Exception) {
            recording = null
            return@withLock false
        } finally {
            isInTransition = false
        }
    }
}
```

#### 5. prepareToRecord 方法移除

**移除原因**：
- 功能与 `startRecording` 重复
- 增加了不必要的复杂性
- 其功能已集成到 `startRecording` 中

**影响的调用点**：
```kotlin
// MainActivity.handleCameraKeyPress() 简化
private fun handleCameraKeyPress(): Boolean {
    // 移除这行：recorderState.prepareToRecord(true, true)
    recorderState.toggleRecordState()  // 保留这行
    return true
}
```

#### 6. RecorderState 层的对应调整

##### 新增直播状态管理

```kotlin
// 与预览状态管理保持一致的风格
private val _isStreamingEnabled: MutableState<Boolean> = mutableStateOf(false)
val isStreamingEnabled: Boolean
    get() = _isStreamingEnabled.value

fun setStreamingEnabled(enabled: Boolean) {
    _isStreamingEnabled.value = enabled
}

private fun syncStreamingState(enabled: Boolean) {
    _isStreamingEnabled.value = enabled
}
```

##### 直播客户端连接处理更新

```kotlin
// 原 Vision -> Streaming
override fun onClientConnected() {
    lifecycleOwner.lifecycleScope.launch {
        recorderMutex.withLock {
            val success = recorder?.setStreamingEnabled(true) ?: false
            if (success) {
                syncStreamingState(true)
            }
            // 录像期间失败是正常的，会在录像结束后自动处理
        }
    }
}
```

### 状态转换规则

| 当前状态 | 允许的操作 | 拒绝的操作 |
|---------|-----------|-----------|
| 空闲 (Idle) | • startRecording()<br>• setPreviewSurfaceProvider()<br>• setStreamingEnabled() | • stopRecording() |
| 录像中 (Recording) | • **stopRecording()** | • startRecording()<br>• setPreviewSurfaceProvider()<br>• setStreamingEnabled() |
| 转换中 (Transition) | 无 | 所有操作 |

### 命名统一规范

| 功能 | 状态字段 | 设置方法 | 查询方法 | Before Recording 备份 |
|------|---------|----------|----------|----------------------|
| 预览 | `previewEnabled` | `setPreviewSurfaceProvider()` | `isPreviewEnabled()` | `previewEnabledBeforeRecording` |
| 直播 | `streamingEnabled` | `setStreamingEnabled()` | `isStreamingEnabled()` | `streamingEnabledBeforeRecording` |

## 实施步骤

### 阶段1：ThinkletRecorder 重构
1. 替换锁机制（`cameraStateLock`）
2. 更新状态字段的 `@GuardedBy` 注解
3. 重构 `setPreviewSurfaceProvider` 方法
4. 将 `enableVisionUseCase` 重命名为 `setStreamingEnabled`
5. 重构 `startRecording` 和 `stopRecording` 方法
6. 移除 `rebindUseCasesAsync` 和 `prepareToRecord` 方法

### 阶段2：RecorderState 调整
1. 添加直播状态管理字段和方法
2. 更新直播客户端连接处理逻辑
3. 简化录像控制逻辑
4. 更新状态同步机制

### 阶段3：MainActivity 简化
1. 移除 `prepareToRecord` 调用
2. 测试硬件按键录像功能

### 阶段4：测试验证
1. 录像期间的状态变更保护测试
2. 直播客户端连接/断开测试
3. 预览开关测试
4. 并发操作压力测试

## 预期效果

### 解决的问题
1. ✅ 消除所有竞态条件
2. ✅ 录像期间状态完全保护
3. ✅ 简化并发控制逻辑
4. ✅ 提高系统响应性（减少异步延迟）
5. ✅ 统一命名规范，提高可维护性

### 向后兼容性
- UI 层接口基本保持不变
- 硬件按键功能完全兼容
- 直播功能逻辑不受影响

### 性能改进
- 减少锁竞争（单一锁模型）
- 减少异步任务调度开销
- 更快的相机绑定响应时间

## 风险评估

### 低风险
- 重构主要在内部实现，外部接口变化最小
- 保持了所有现有功能
- 有完整的测试验证计划

### 需要关注的点
- 确保 `delay(1000L)` 时间足够进行相机绑定
- 验证在各种设备上的兼容性
- 确认直播传输功能的稳定性

## 结论

此重构方案通过集中式状态管理和单一锁模型，彻底解决了现有的竞态条件问题，同时保持了系统的功能完整性和性能表现。重构后的代码将更加健壮、可维护，并且具有更好的并发安全性。

