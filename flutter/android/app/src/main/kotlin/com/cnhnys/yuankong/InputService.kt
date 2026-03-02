package com.cnhnys.yuankong

/**
 * Handle remote input and dispatch android gesture
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.view.accessibility.AccessibilityEvent
import android.view.ViewGroup.LayoutParams
import android.view.accessibility.AccessibilityNodeInfo
import android.view.KeyEvent as KeyEventAndroid
import android.view.ViewConfiguration
import android.graphics.Rect
import android.media.AudioManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
import android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import java.util.*
import java.lang.Character
import kotlin.math.abs
import kotlin.math.max
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import hbb.KeyEventConverter

// 正确的本地 Socket 导入（Android 平台）
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.IOException
import java.io.OutputStream

// const val BUTTON_UP = 2
// const val BUTTON_BACK = 0x08

const val LEFT_DOWN = 9
const val LEFT_MOVE = 8
const val LEFT_UP = 10
const val RIGHT_UP = 18
// (BUTTON_BACK << 3) | BUTTON_UP
const val BACK_UP = 66
const val WHEEL_BUTTON_DOWN = 33
const val WHEEL_BUTTON_UP = 34
const val WHEEL_DOWN = 523331
const val WHEEL_UP = 963

const val TOUCH_SCALE_START = 1
const val TOUCH_SCALE = 2
const val TOUCH_SCALE_END = 3
const val TOUCH_PAN_START = 4
const val TOUCH_PAN_UPDATE = 5
const val TOUCH_PAN_END = 6

const val WHEEL_STEP = 120
const val WHEEL_DURATION = 50L
const val LONG_TAP_DELAY = 200L

class InputService : AccessibilityService() {

    companion object {
        var ctx: InputService? = null
        val isOpen: Boolean
            get() = ctx != null || (serverSocket != null && clientSocket != null)
    }

    private val logTag = "input service"
    private var leftIsDown = false
    private var touchPath = Path()
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastTouchGestureStartTime = 0L
    private var mouseX = 0
    private var mouseY = 0
    private var timer = Timer()
    private var recentActionTask: TimerTask? = null
    // 100(tap timeout) + 400(long press timeout)
    private val longPressDuration = ViewConfiguration.getTapTimeout().toLong() + ViewConfiguration.getLongPressTimeout().toLong()

    private val wheelActionsQueue = LinkedList<GestureDescription>()
    private var isWheelActionsPolling = false
    private var isWaitingLongPress = false

    private var fakeEditTextForTextStateCalculation: EditText? = null

    private var lastX = 0
    private var lastY = 0

    private val volumeController: VolumeController by lazy { VolumeController(applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager) }

    // ---------- 降级处理：本地 Socket 服务器 ----------
    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var clientOutput: OutputStream? = null
    private var isClientConnected = false
    private val socketLock = Any()

    // 降级专用状态（用于触摸位移的累积）
    private var fallbackTouchX: Int = 0
    private var fallbackTouchY: Int = 0
    private var dragActive = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragLastX = 0
    private var dragLastY = 0
    private var dragStartTime = 0L
    private var wheelButtonDownTime = 0L
    private var recentActionTaskFallback: TimerTask? = null

    override fun onCreate() {
        super.onCreate()
        startSocketServer()
    }

    /**
     * 启动本地 Socket 服务器，等待唯一客户端连接
     */
    private fun startSocketServer() {
        Thread {
            try {
                serverSocket = LocalServerSocket("MyInput")
                Log.d(logTag, "Socket server started, waiting for client...")
                while (true) {
                    val client = serverSocket!!.accept()
                    synchronized(socketLock) {
                        if (clientSocket == null) {
                            clientSocket = client
                            clientOutput = client.getOutputStream()
                            isClientConnected = true
                            Log.d(logTag, "Socket client connected")

                            // 启动监控线程，检测客户端断开
                            startClientMonitor(client)
                        } else {
                            // 已有客户端，拒绝新连接
                            Log.d(logTag, "Additional client rejected")
                            client.close()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(logTag, "Socket server error", e)
            }
        }.start()
    }

    /**
     * 监控客户端是否断开，若断开则清理资源
     */
    private fun startClientMonitor(client: LocalSocket) {
        Thread {
            try {
                val inputStream = client.inputStream
                val buffer = ByteArray(1024)
                while (inputStream.read(buffer) != -1) {
                    // 只是检测断开，不需要读数据
                }
            } catch (e: IOException) {
                Log.d(logTag, "Client disconnected")
            } finally {
                synchronized(socketLock) {
                    if (clientSocket == client) {
                        clientSocket = null
                        clientOutput = null
                        isClientConnected = false
                        Log.d(logTag, "Client cleaned up")
                    }
                }
            }
        }.start()
    }

    /**
     * 向 socket 客户端发送命令（自动追加换行符，并去除"input "前缀）
     */
    private fun sendCommand(cmd: String) {
        synchronized(socketLock) {
            if (clientOutput != null) {
                try {
                    // 命令格式如 "tap 100 200"，直接发送，客户端负责添加 input 前缀
                    clientOutput!!.write((cmd + "\n").toByteArray())
                    clientOutput!!.flush()
                    Log.d(logTag, "Sent: $cmd")
                } catch (e: IOException) {
                    Log.e(logTag, "Send command failed", e)
                    // 连接已断开，清理
                    clientSocket = null
                    clientOutput = null
                    isClientConnected = false
                }
            }
        }
    }

    // ---------- 原有输入方法，增加降级分支 ----------

    @RequiresApi(Build.VERSION_CODES.N)
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x)
        val y = max(0, _y)

        // 优先使用无障碍服务
        if (ctx != null) {
            handleMouseInputAccessibility(mask, x, y)
        } else if (isClientConnected) {
            handleMouseInputFallback(mask, x, y)
        } else {
            Log.w(logTag, "No input method available")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleMouseInputAccessibility(mask: Int, x: Int, y: Int) {
        // 原有无障碍鼠标逻辑（完全保留）
        var localX = x
        var localY = y
        if (mask == 0 || mask == LEFT_MOVE) {
            val oldX = mouseX
            val oldY = mouseY
            mouseX = localX * SCREEN_INFO.scale
            mouseY = localY * SCREEN_INFO.scale
            if (isWaitingLongPress) {
                val delta = abs(oldX - mouseX) + abs(oldY - mouseY)
                Log.d(logTag,"delta:$delta")
                if (delta > 8) {
                    isWaitingLongPress = false
                }
            }
        }

        // left button down, was up
        if (mask == LEFT_DOWN) {
            isWaitingLongPress = true
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (isWaitingLongPress) {
                        isWaitingLongPress = false
                        continueGesture(mouseX, mouseY)
                    }
                }
            }, longPressDuration)

            leftIsDown = true
            startGesture(mouseX, mouseY)
            return
        }

        // left down, was down
        if (leftIsDown) {
            continueGesture(mouseX, mouseY)
        }

        // left up, was down
        if (mask == LEFT_UP) {
            if (leftIsDown) {
                leftIsDown = false
                isWaitingLongPress = false
                endGesture(mouseX, mouseY)
                return
            }
        }

        if (mask == RIGHT_UP) {
            longPress(mouseX, mouseY)
            return
        }

        if (mask == BACK_UP) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // long WHEEL_BUTTON_DOWN -> GLOBAL_ACTION_RECENTS
        if (mask == WHEEL_BUTTON_DOWN) {
            timer.purge()
            recentActionTask = object : TimerTask() {
                override fun run() {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    recentActionTask = null
                }
            }
            timer.schedule(recentActionTask, LONG_TAP_DELAY)
        }

        // wheel button up
        if (mask == WHEEL_BUTTON_UP) {
            if (recentActionTask != null) {
                recentActionTask!!.cancel()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            return
        }

        if (mask == WHEEL_DOWN) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY - WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
        }

        if (mask == WHEEL_UP) {
            if (mouseY < WHEEL_STEP) {
                return
            }
            val path = Path()
            path.moveTo(mouseX.toFloat(), mouseY.toFloat())
            path.lineTo(mouseX.toFloat(), (mouseY + WHEEL_STEP).toFloat())
            val stroke = GestureDescription.StrokeDescription(
                path,
                0,
                WHEEL_DURATION
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            wheelActionsQueue.offer(builder.build())
            consumeWheelActions()
        }
    }

    /**
     * 降级鼠标处理：转换为 adb input 命令并通过 socket 发送
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleMouseInputFallback(mask: Int, x: Int, y: Int) {
        val scaledX = x * SCREEN_INFO.scale
        val scaledY = y * SCREEN_INFO.scale

        when (mask) {
            LEFT_DOWN -> {
                // 开始拖拽
                dragActive = true
                dragStartX = scaledX
                dragStartY = scaledY
                dragLastX = scaledX
                dragLastY = scaledY
                dragStartTime = System.currentTimeMillis()
                // 左键按下时不发送命令，等待移动或抬起
            }
            LEFT_MOVE -> {
                if (dragActive) {
                    // 发送从上一点到当前点的短时滑动，模拟移动
                    sendCommand("swipe ${dragLastX} ${dragLastY} ${scaledX} ${scaledY} 10")
                    dragLastX = scaledX
                    dragLastY = scaledY
                }
            }
            LEFT_UP -> {
                if (dragActive) {
                    val duration = max(1, System.currentTimeMillis() - dragStartTime)
                    if (dragStartX == scaledX && dragStartY == scaledY) {
                        // 没有移动，视为点击
                        sendCommand("tap $scaledX $scaledY")
                    } else {
                        // 完整拖拽：从起点到终点
                        sendCommand("swipe $dragStartX $dragStartY $scaledX $scaledY $duration")
                    }
                    dragActive = false
                }
            }
            RIGHT_UP -> {
                // 右键模拟长按
                sendCommand("swipe $scaledX $scaledY $scaledX $scaledY $longPressDuration")
            }
            BACK_UP -> {
                sendCommand("keyevent KEYCODE_BACK")
            }
            WHEEL_BUTTON_DOWN -> {
                wheelButtonDownTime = System.currentTimeMillis()
                timer.purge()
                recentActionTaskFallback = object : TimerTask() {
                    override fun run() {
                        if (wheelButtonDownTime > 0) {
                            sendCommand("keyevent KEYCODE_APP_SWITCH")
                            wheelButtonDownTime = 0
                        }
                    }
                }
                timer.schedule(recentActionTaskFallback, LONG_TAP_DELAY)
            }
            WHEEL_BUTTON_UP -> {
                if (recentActionTaskFallback != null) {
                    recentActionTaskFallback!!.cancel()
                    recentActionTaskFallback = null
                    // 短按发送 HOME
                    sendCommand("keyevent KEYCODE_HOME")
                }
                wheelButtonDownTime = 0
            }
            WHEEL_DOWN -> {
                if (scaledY >= WHEEL_STEP) {
                    sendCommand("swipe $scaledX $scaledY $scaledX ${scaledY - WHEEL_STEP} $WHEEL_DURATION")
                }
            }
            WHEEL_UP -> {
                if (scaledY + WHEEL_STEP <= SCREEN_INFO.height * SCREEN_INFO.scale) {
                    sendCommand("swipe $scaledX $scaledY $scaledX ${scaledY + WHEEL_STEP} $WHEEL_DURATION")
                }
            }
            else -> {
                // 其他 mask 忽略
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        if (ctx != null) {
            handleTouchInputAccessibility(mask, _x, _y)
        } else if (isClientConnected) {
            handleTouchInputFallback(mask, _x, _y)
        } else {
            Log.w(logTag, "No input method available for touch")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleTouchInputAccessibility(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_UPDATE -> {
                mouseX -= _x * SCREEN_INFO.scale
                mouseY -= _y * SCREEN_INFO.scale
                mouseX = max(0, mouseX);
                mouseY = max(0, mouseY);
                continueGesture(mouseX, mouseY)
            }
            TOUCH_PAN_START -> {
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
                startGesture(mouseX, mouseY)
            }
            TOUCH_PAN_END -> {
                endGesture(mouseX, mouseY)
                mouseX = max(0, _x) * SCREEN_INFO.scale
                mouseY = max(0, _y) * SCREEN_INFO.scale
            }
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleTouchInputFallback(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_START -> {
                val scaledX = max(0, _x) * SCREEN_INFO.scale
                val scaledY = max(0, _y) * SCREEN_INFO.scale
                fallbackTouchX = scaledX
                fallbackTouchY = scaledY
                dragActive = true
                dragStartX = scaledX
                dragStartY = scaledY
                dragLastX = scaledX
                dragLastY = scaledY
                dragStartTime = System.currentTimeMillis()
            }
            TOUCH_PAN_UPDATE -> {
                if (dragActive) {
                    // _x, _y 是位移量（相对上次位置的变化）
                    val newX = fallbackTouchX - _x * SCREEN_INFO.scale
                    val newY = fallbackTouchY - _y * SCREEN_INFO.scale
                    val clampedX = max(0, newX)
                    val clampedY = max(0, newY)
                    // 发送从上一点到新点的短时滑动
                    sendCommand("swipe ${dragLastX} ${dragLastY} ${clampedX} ${clampedY} 10")
                    dragLastX = clampedX
                    dragLastY = clampedY
                    fallbackTouchX = clampedX
                    fallbackTouchY = clampedY
                }
            }
            TOUCH_PAN_END -> {
                val scaledX = max(0, _x) * SCREEN_INFO.scale
                val scaledY = max(0, _y) * SCREEN_INFO.scale
                if (dragActive) {
                    val duration = max(1, System.currentTimeMillis() - dragStartTime)
                    sendCommand("swipe $dragStartX $dragStartY $scaledX $scaledY $duration")
                    dragActive = false
                }
                fallbackTouchX = scaledX
                fallbackTouchY = scaledY
            }
            else -> {}
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun consumeWheelActions() {
        if (isWheelActionsPolling) {
            return
        } else {
            isWheelActionsPolling = true
        }
        wheelActionsQueue.poll()?.let {
            dispatchGesture(it, null, null)
            timer.purge()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    isWheelActionsPolling = false
                    consumeWheelActions()
                }
            }, WHEEL_DURATION + 10)
        } ?: let {
            isWheelActionsPolling = false
            return
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun performClick(x: Int, y: Int, duration: Long) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())
        try {
            val longPressStroke = GestureDescription.StrokeDescription(path, 0, duration)
            val builder = GestureDescription.Builder()
            builder.addStroke(longPressStroke)
            Log.d(logTag, "performClick x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "performClick, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun longPress(x: Int, y: Int) {
        performClick(x, y, longPressDuration)
    }

    private fun startGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            touchPath.reset()
        } else {
            touchPath = Path()
        }
        touchPath.moveTo(x.toFloat(), y.toFloat())
        lastTouchGestureStartTime = System.currentTimeMillis()
        lastX = x
        lastY = y
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doDispatchGesture(x: Int, y: Int, willContinue: Boolean) {
        touchPath.lineTo(x.toFloat(), y.toFloat())
        var duration = System.currentTimeMillis() - lastTouchGestureStartTime
        if (duration <= 0) {
            duration = 1
        }
        try {
            if (stroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration,
                        willContinue
                    )
                } else {
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    stroke = stroke?.continueStroke(touchPath, 0, duration, willContinue)
                } else {
                    stroke = null
                    stroke = GestureDescription.StrokeDescription(
                        touchPath,
                        0,
                        duration
                    )
                }
            }
            stroke?.let {
                val builder = GestureDescription.Builder()
                builder.addStroke(it)
                Log.d(logTag, "doDispatchGesture x:$x y:$y time:$duration")
                dispatchGesture(builder.build(), null, null)
            }
        } catch (e: Exception) {
            Log.e(logTag, "doDispatchGesture, willContinue:$willContinue, error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun continueGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, true)
            touchPath.reset()
            touchPath.moveTo(x.toFloat(), y.toFloat())
            lastTouchGestureStartTime = System.currentTimeMillis()
            lastX = x
            lastY = y
        } else {
            touchPath.lineTo(x.toFloat(), y.toFloat())
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGestureBelowO(x: Int, y: Int) {
        try {
            touchPath.lineTo(x.toFloat(), y.toFloat())
            var duration = System.currentTimeMillis() - lastTouchGestureStartTime
            if (duration <= 0) {
                duration = 1
            }
            val stroke = GestureDescription.StrokeDescription(
                touchPath,
                0,
                duration
            )
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            Log.d(logTag, "end gesture x:$x y:$y time:$duration")
            dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(logTag, "endGesture error:$e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun endGesture(x: Int, y: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            doDispatchGesture(x, y, false)
            touchPath.reset()
            stroke = null
        } else {
            endGestureBelowO(x, y)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        val keyEvent = KeyEvent.parseFrom(data)
        val keyboardMode = keyEvent.getMode()

        var textToCommit: String? = null

        if (keyEvent.hasSeq()) {
            textToCommit = keyEvent.getSeq()
        } else if (keyboardMode == KeyboardMode.Legacy) {
            if (keyEvent.hasChr() && (keyEvent.getDown() || keyEvent.getPress())) {
                val chr = keyEvent.getChr()
                if (chr != null) {
                    textToCommit = String(Character.toChars(chr))
                }
            }
        } else if (keyboardMode == KeyboardMode.Translate) {
        } else {
        }

        Log.d(logTag, "onKeyEvent $keyEvent textToCommit:$textToCommit")

        var ke: KeyEventAndroid? = null
        if (Build.VERSION.SDK_INT < 33 || textToCommit == null) {
            ke = KeyEventConverter.toAndroidKeyEvent(keyEvent)
        }
        ke?.let { event ->
            if (tryHandleVolumeKeyEvent(event)) {
                return
            } else if (tryHandlePowerKeyEvent(event)) {
                return
            }
        }

        // 根据可用输入方式分发
        if (ctx != null) {
            handleKeyEventAccessibility(ke, textToCommit, keyEvent)
        } else if (isClientConnected) {
            handleKeyEventFallback(ke, textToCommit, keyEvent)
        } else {
            Log.w(logTag, "No input method available for key event")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleKeyEventAccessibility(ke: KeyEventAndroid?, textToCommit: String?, original: hbb.MessageOuterClass.KeyEvent) {
        if (Build.VERSION.SDK_INT >= 33) {
            getInputMethod()?.let { inputMethod ->
                inputMethod.getCurrentInputConnection()?.let { inputConnection ->
                    if (textToCommit != null) {
                        inputConnection.commitText(textToCommit, 1, null)
                    } else {
                        ke?.let { event ->
                            inputConnection.sendKeyEvent(event)
                            if (original.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                inputConnection.sendKeyEvent(actionUpEvent)
                            }
                        }
                    }
                }
            }
        } else {
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                ke?.let { event ->
                    val possibleNodes = possibleAccessibiltyNodes()
                    Log.d(logTag, "possibleNodes:$possibleNodes")
                    for (item in possibleNodes) {
                        val success = trySendKeyEvent(event, item, textToCommit)
                        if (success) {
                            if (original.getPress()) {
                                val actionUpEvent = KeyEventAndroid(KeyEventAndroid.ACTION_UP, event.keyCode)
                                trySendKeyEvent(actionUpEvent, item, textToCommit)
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun handleKeyEventFallback(ke: KeyEventAndroid?, textToCommit: String?, original: hbb.MessageOuterClass.KeyEvent) {
        if (textToCommit != null) {
            // 发送文本，转义双引号
            val escaped = textToCommit.replace("\"", "\\\"")
            sendCommand("text \"$escaped\"")
        } else {
            ke?.let { event ->
                // 只处理 ACTION_DOWN，keyevent 命令模拟一次完整的按键
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    val keyCodeStr = keyCodeToString(event.keyCode)
                    if (keyCodeStr != null) {
                        sendCommand("keyevent $keyCodeStr")
                    } else {
                        Log.w(logTag, "Unknown keycode: ${event.keyCode}")
                    }
                }
            }
        }
    }

    private fun keyCodeToString(keyCode: Int): String? {
        return when (keyCode) {
            KeyEventAndroid.KEYCODE_HOME -> "KEYCODE_HOME"
            KeyEventAndroid.KEYCODE_BACK -> "KEYCODE_BACK"
            KeyEventAndroid.KEYCODE_CALL -> "KEYCODE_CALL"
            KeyEventAndroid.KEYCODE_ENDCALL -> "KEYCODE_ENDCALL"
            KeyEventAndroid.KEYCODE_VOLUME_UP -> "KEYCODE_VOLUME_UP"
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> "KEYCODE_VOLUME_DOWN"
            KeyEventAndroid.KEYCODE_POWER -> "KEYCODE_POWER"
            KeyEventAndroid.KEYCODE_CAMERA -> "KEYCODE_CAMERA"
            KeyEventAndroid.KEYCODE_CLEAR -> "KEYCODE_CLEAR"
            KeyEventAndroid.KEYCODE_ENTER -> "KEYCODE_ENTER"
            KeyEventAndroid.KEYCODE_DEL -> "KEYCODE_DEL"
            KeyEventAndroid.KEYCODE_DPAD_UP -> "KEYCODE_DPAD_UP"
            KeyEventAndroid.KEYCODE_DPAD_DOWN -> "KEYCODE_DPAD_DOWN"
            KeyEventAndroid.KEYCODE_DPAD_LEFT -> "KEYCODE_DPAD_LEFT"
            KeyEventAndroid.KEYCODE_DPAD_RIGHT -> "KEYCODE_DPAD_RIGHT"
            KeyEventAndroid.KEYCODE_DPAD_CENTER -> "KEYCODE_DPAD_CENTER"
            KeyEventAndroid.KEYCODE_APP_SWITCH -> "KEYCODE_APP_SWITCH"
            // 可根据需要继续添加
            else -> null
        }
    }

    private fun tryHandleVolumeKeyEvent(event: KeyEventAndroid): Boolean {
        when (event.keyCode) {
            KeyEventAndroid.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.raiseVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.lowerVolume(null, true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            KeyEventAndroid.KEYCODE_VOLUME_MUTE -> {
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    volumeController.toggleMute(true, AudioManager.STREAM_SYSTEM)
                }
                return true
            }
            else -> {
                return false
            }
        }
    }

    private fun tryHandlePowerKeyEvent(event: KeyEventAndroid): Boolean {
        if (event.keyCode == KeyEventAndroid.KEYCODE_POWER) {
            // Perform power dialog action when action is up
            if (event.action == KeyEventAndroid.ACTION_UP) {
                if (ctx != null) {
                    performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                } else if (isClientConnected) {
                    sendCommand("keyevent KEYCODE_POWER")
                }
            }
            return true
        }
        return false
    }

    // 以下为无障碍辅助方法，未作修改
    private fun insertAccessibilityNode(list: LinkedList<AccessibilityNodeInfo>, node: AccessibilityNodeInfo) {
        if (node == null) {
            return
        }
        if (list.contains(node)) {
            return
        }
        list.add(node)
    }

    private fun findChildNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isEditable() && node.isFocusable()) {
            return node
        }
        val childCount = node.getChildCount()
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (child.isEditable() && child.isFocusable()) {
                    return child
                }
                if (Build.VERSION.SDK_INT < 33) {
                    child.recycle()
                }
            }
        }
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findChildNode(child)
                if (Build.VERSION.SDK_INT < 33) {
                    if (child != result) {
                        child.recycle()
                    }
                }
                if (result != null) {
                    return result
                }
            }
        }
        return null
    }

    private fun possibleAccessibiltyNodes(): LinkedList<AccessibilityNodeInfo> {
        val linkedList = LinkedList<AccessibilityNodeInfo>()
        val latestList = LinkedList<AccessibilityNodeInfo>()

        val focusInput = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        var focusAccessibilityInput = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        val rootInActiveWindow = getRootInActiveWindow()

        Log.d(logTag, "focusInput:$focusInput focusAccessibilityInput:$focusAccessibilityInput rootInActiveWindow:$rootInActiveWindow")

        if (focusInput != null) {
            if (focusInput.isFocusable() && focusInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusInput)
            } else {
                insertAccessibilityNode(latestList, focusInput)
            }
        }

        if (focusAccessibilityInput != null) {
            if (focusAccessibilityInput.isFocusable() && focusAccessibilityInput.isEditable()) {
                insertAccessibilityNode(linkedList, focusAccessibilityInput)
            } else {
                insertAccessibilityNode(latestList, focusAccessibilityInput)
            }
        }

        val childFromFocusInput = findChildNode(focusInput)
        Log.d(logTag, "childFromFocusInput:$childFromFocusInput")

        if (childFromFocusInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusInput)
        }

        val childFromFocusAccessibilityInput = findChildNode(focusAccessibilityInput)
        if (childFromFocusAccessibilityInput != null) {
            insertAccessibilityNode(linkedList, childFromFocusAccessibilityInput)
        }
        Log.d(logTag, "childFromFocusAccessibilityInput:$childFromFocusAccessibilityInput")

        if (rootInActiveWindow != null) {
            insertAccessibilityNode(linkedList, rootInActiveWindow)
        }

        for (item in latestList) {
            insertAccessibilityNode(linkedList, item)
        }

        return linkedList
    }

    private fun trySendKeyEvent(event: KeyEventAndroid, node: AccessibilityNodeInfo, textToCommit: String?): Boolean {
        node.refresh()
        this.fakeEditTextForTextStateCalculation?.setSelection(0,0)
        this.fakeEditTextForTextStateCalculation?.setText(null)

        val text = node.getText()
        var isShowingHint = false
        if (Build.VERSION.SDK_INT >= 26) {
            isShowingHint = node.isShowingHintText()
        }

        var textSelectionStart = node.textSelectionStart
        var textSelectionEnd = node.textSelectionEnd

        if (text != null) {
            if (textSelectionStart > text.length) {
                textSelectionStart = text.length
            }
            if (textSelectionEnd > text.length) {
                textSelectionEnd = text.length
            }
            if (textSelectionStart > textSelectionEnd) {
                textSelectionStart = textSelectionEnd
            }
        }

        var success = false

        Log.d(logTag, "existing text:$text textToCommit:$textToCommit textSelectionStart:$textSelectionStart textSelectionEnd:$textSelectionEnd")

        if (textToCommit != null) {
            if ((textSelectionStart == -1) || (textSelectionEnd == -1)) {
                val newText = textToCommit
                this.fakeEditTextForTextStateCalculation?.setText(newText)
                success = updateTextForAccessibilityNode(node)
            } else if (text != null) {
                this.fakeEditTextForTextStateCalculation?.setText(text)
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
                this.fakeEditTextForTextStateCalculation?.text?.insert(textSelectionStart, textToCommit)
                success = updateTextAndSelectionForAccessibiltyNode(node)
            }
        } else {
            if (isShowingHint) {
                this.fakeEditTextForTextStateCalculation?.setText(null)
            } else {
                this.fakeEditTextForTextStateCalculation?.setText(text)
            }
            if (textSelectionStart != -1 && textSelectionEnd != -1) {
                Log.d(logTag, "setting selection $textSelectionStart $textSelectionEnd")
                this.fakeEditTextForTextStateCalculation?.setSelection(
                    textSelectionStart,
                    textSelectionEnd
                )
            }

            this.fakeEditTextForTextStateCalculation?.let {
                // This is essiential to make sure layout object is created. OnKeyDown may not work if layout is not created.
                val rect = Rect()
                node.getBoundsInScreen(rect)

                it.layout(rect.left, rect.top, rect.right, rect.bottom)
                it.onPreDraw()
                if (event.action == KeyEventAndroid.ACTION_DOWN) {
                    val succ = it.onKeyDown(event.getKeyCode(), event)
                    Log.d(logTag, "onKeyDown $succ")
                } else if (event.action == KeyEventAndroid.ACTION_UP) {
                    val success = it.onKeyUp(event.getKeyCode(), event)
                    Log.d(logTag, "keyup $success")
                } else {}
            }

            success = updateTextAndSelectionForAccessibiltyNode(node)
        }
        return success
    }

    fun updateTextForAccessibilityNode(node: AccessibilityNodeInfo): Boolean {
        var success = false
        this.fakeEditTextForTextStateCalculation?.text?.let {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                it.toString()
            )
            success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return success
    }

    fun updateTextAndSelectionForAccessibiltyNode(node: AccessibilityNodeInfo): Boolean {
        var success = updateTextForAccessibilityNode(node)

        if (success) {
            val selectionStart = this.fakeEditTextForTextStateCalculation?.selectionStart
            val selectionEnd = this.fakeEditTextForTextStateCalculation?.selectionEnd

            if (selectionStart != null && selectionEnd != null) {
                val arguments = Bundle()
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    selectionStart
                )
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    selectionEnd
                )
                success = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
                Log.d(logTag, "Update selection to $selectionStart $selectionEnd success:$success")
            }
        }

        return success
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ctx = this
        val info = AccessibilityServiceInfo()
        if (Build.VERSION.SDK_INT >= 33) {
            info.flags = FLAG_INPUT_METHOD_EDITOR or FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        } else {
            info.flags = FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        setServiceInfo(info)
        fakeEditTextForTextStateCalculation = EditText(this)
        fakeEditTextForTextStateCalculation?.layoutParams = LayoutParams(100, 100)
        fakeEditTextForTextStateCalculation?.onPreDraw()
        val layout = fakeEditTextForTextStateCalculation?.getLayout()
        Log.d(logTag, "fakeEditTextForTextStateCalculation layout:$layout")
        Log.d(logTag, "onServiceConnected!")
    }

    override fun onDestroy() {
        ctx = null
        // 关闭 socket 资源
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        super.onDestroy()
    }

    override fun onInterrupt() {}
}