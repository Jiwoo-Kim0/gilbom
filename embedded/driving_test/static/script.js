// AutoDrive Sidewalk Robot & Braille Block Capture Dashboard - Frontend Logic

let isAutoMode = false;
let pollingInterval = null;
const canvas = document.getElementById('radarCanvas');
const ctx = canvas.getContext('2d');
let lastCaptureCount = 0;

document.addEventListener('DOMContentLoaded', () => {
    startTelemetryPolling();
    setupKeyboardListeners();
    setupGamepadListeners();
    drawRadarBase();
});

function startTelemetryPolling() {
    pollingInterval = setInterval(fetchTelemetry, 120);
}

async function fetchTelemetry() {
    try {
        const response = await fetch('/api/telemetry');
        const data = await response.json();
        if (data.success) {
            updateDashboard(data);
        }
    } catch (err) {
        console.error("Telemetry fetch error:", err);
    }
}

function updateDashboard(data) {
    // 1. Update Mode Badges & Controls
    isAutoMode = data.is_auto;
    document.getElementById('autoSwitch').checked = isAutoMode;
    const autoBadge = document.getElementById('autoBadge');
    autoBadge.innerText = isAutoMode ? 'AUTONOMOUS' : 'MANUAL';
    autoBadge.className = `mode-badge ${isAutoMode ? 'auto' : 'manual'}`;

    document.getElementById('lidarSourceSelect').value = data.lidar_mock_mode ? 'MOCK' : 'HARDWARE';
    document.getElementById('speedDisplay').innerText = `${data.speed}%`;
    document.getElementById('angleDisplay').innerText = `${data.angle}°`;
    document.getElementById('directionBadge').innerText = data.direction;

    // 2. Vision Overlay Badges
    const vision = data.vision || {};
    const brailleBlocks = vision.detected_braille_blocks || [];
    document.getElementById('brailleCountBadge').innerText = `점자블럭: ${brailleBlocks.length}개 탐지`;
    document.getElementById('visionOffsetVal').innerText = vision.center_offset ? vision.center_offset.toFixed(2) : '0.00';
    document.getElementById('visionSteerVal').innerText = `${vision.suggested_steer_angle || 100}°`;

    // 3. Status Banner
    const banner = document.getElementById('statusBanner');
    const title = document.getElementById('statusTitle');
    const subtitle = document.getElementById('statusSubtitle');
    const actionPill = document.getElementById('actionPill');
    const icon = document.getElementById('statusIcon');

    const lidar = data.lidar || {};
    const status = lidar.overall_status || 'CLEAR_PATH';
    actionPill.innerText = data.last_action || data.direction;

    if (lidar.is_emergency_stop) {
        banner.className = 'status-banner danger-center';
        icon.innerText = '🚨';
        title.innerText = '전방 장애물 긴급 정지! (EMERGENCY BRAKE)';
        subtitle.innerText = `전방 센서 거리 ${lidar.front_min_dist}m 이내 장애물 감지됨 -> 비상 정지 및 회피`;
    } else if (status.includes('OBSTACLE')) {
        banner.className = 'status-banner warning-left';
        icon.innerText = '⚠️';
        title.innerText = '장애물 감지 회피 주행 중 (OBSTACLE AVOIDANCE)';
        subtitle.innerText = `LiDAR 전방(${lidar.front_min_dist}m), 좌(${lidar.left_min_dist}m), 우(${lidar.right_min_dist}m) 회피`;
    } else {
        banner.className = 'status-banner';
        icon.innerText = brailleBlocks.length > 0 ? '📸' : '🛡️';
        title.innerText = brailleBlocks.length > 0 ? '점자 보도블럭 발견 & 캡쳐 진행 중!' : '인도 자율 주행 중 (NORMAL CRUISE)';
        subtitle.innerText = vision.sidewalk_detected ? '비전 YOLO 인도 세그멘테이션 중심선 추종 주행' : '수평 LiDAR 안전거리 확보 주행 중';
    }

    // 4. Sector Distance Displays
    document.getElementById('distLeft').innerText = `${lidar.left_min_dist || '--'} m`;
    document.getElementById('distCenter').innerText = `${lidar.front_min_dist || '--'} m`;
    document.getElementById('distRight').innerText = `${lidar.right_min_dist || '--'} m`;

    // 5. Render Radar Canvas
    renderRadarCanvas(data.scan_points || [], lidar);

    // 6. Update Capture Gallery
    const captures = data.captures || [];
    if (captures.length !== lastCaptureCount) {
        lastCaptureCount = captures.length;
        renderCaptureGallery(captures);
    }
}

function renderCaptureGallery(captures) {
    const galleryEl = document.getElementById('captureGallery');
    if (!captures || captures.length === 0) {
        galleryEl.innerHTML = '<div class="empty-gallery">아직 저장된 점자블럭 캡쳐가 없습니다.</div>';
        return;
    }

    galleryEl.innerHTML = captures.map(cap => `
        <div class="gallery-item">
            <a href="/captures/${cap.filename}" target="_blank">
                <img src="/captures/${cap.filename}" alt="${cap.top_class}">
            </a>
            <div class="gallery-cap-info">
                <span class="cap-title">${cap.top_class}</span>
                <small class="cap-time">${cap.timestamp}</small>
            </div>
        </div>
    `).join('');
}

// 2D Horizontal Radar Canvas Renderer
function renderRadarCanvas(scanPoints, lidar) {
    const width = canvas.width;
    const height = canvas.height;

    ctx.fillStyle = '#050a14';
    ctx.fillRect(0, 0, width, height);

    const originX = width / 2;
    const originY = height - 20;
    const scale = 55; // 1 meter = 55 pixels

    // Grid Concentric Arcs (1m, 2m, 3m, 4m)
    ctx.strokeStyle = 'rgba(0, 210, 255, 0.12)';
    ctx.lineWidth = 1;
    for (let r = 1; r <= 4; r++) {
        ctx.beginPath();
        ctx.arc(originX, originY, r * scale, Math.PI, 0);
        ctx.stroke();

        ctx.fillStyle = 'rgba(138, 153, 173, 0.5)';
        ctx.font = '10px Orbitron';
        ctx.fillText(`${r}m`, originX + r * scale - 15, originY - 4);
    }

    if (!scanPoints || scanPoints.length === 0) {
        ctx.fillStyle = '#ff9100';
        ctx.font = '13px Pretendard';
        ctx.textAlign = 'center';
        ctx.fillText('📡 수평 LiDAR 센서 데이터 연결 대기 중...', width / 2, height / 2);
        ctx.textAlign = 'left';
        return;
    }

    // Scan Points
    scanPoints.forEach(pt => {
        const rad = (pt.angle - 90) * (Math.PI / 180);
        const x = originX + pt.dist * scale * Math.cos(rad);
        const y = originY + pt.dist * scale * Math.sin(rad);

        if (pt.dist < 0.40 && Math.abs(pt.angle) < 30) {
            ctx.fillStyle = '#ff1744';
            ctx.shadowColor = '#ff1744';
            ctx.shadowBlur = 6;
        } else if (pt.dist < 0.75) {
            ctx.fillStyle = '#ff9100';
            ctx.shadowBlur = 3;
        } else {
            ctx.fillStyle = '#00e676';
            ctx.shadowBlur = 1;
        }

        ctx.beginPath();
        ctx.arc(x, y, 2.5, 0, 2 * Math.PI);
        ctx.fill();
        ctx.shadowBlur = 0;
    });

    // Vehicle Marker
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.moveTo(originX, originY - 10);
    ctx.lineTo(originX - 7, originY + 6);
    ctx.lineTo(originX + 7, originY + 6);
    ctx.closePath();
    ctx.fill();
}

function drawRadarBase() {
    renderRadarCanvas([], {});
}

// API Controls
async function toggleAutoDrive(enabled) {
    try {
        const res = await fetch('/api/auto', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({enabled})
        });
        const data = await res.json();
        addLog(`[AUTO MODE] ${enabled ? 'Autonomous Engaged' : 'Manual Mode'}`, enabled ? 'success' : 'info');
    } catch (err) {
        console.error("Toggle auto error:", err);
    }
}

async function toggleLidarSource(val) {
    const use_mock = (val === 'MOCK');
    try {
        const res = await fetch('/api/lidar_source', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({mock_mode: use_mock})
        });
        const data = await res.json();
        addLog(`[LiDAR SOURCE] Switched to: ${use_mock ? 'Mock Simulator' : 'Physical /dev/ttyUSB0'}`, 'warning');
    } catch (err) {
        console.error("LiDAR source toggle error:", err);
    }
}

async function sendCommand(action, params = {}) {
    try {
        const payload = { action, ...params };
        const res = await fetch('/api/control', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (data.success) {
            addLog(`[CONTROL] Action: ${action} | Angle: ${data.angle}° | Speed: ${data.speed}%`, 'info');
        }
    } catch (err) {
        console.error("Control error:", err);
    }
}

async function changeHardwareMode(mode) {
    try {
        const res = await fetch('/api/mode', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({mode})
        });
        const data = await res.json();
        addLog(`[HARDWARE] Switched mode to: ${mode}`, 'warning');
    } catch (err) {
        console.error("Mode error:", err);
    }
}

function setupKeyboardListeners() {
    window.addEventListener('keydown', (e) => {
        if (e.repeat) return;
        const key = e.key.toUpperCase();
        if (key === 'W' || key === 'ARROWUP') {
            e.preventDefault();
            sendCommand('FORWARD');
        } else if (key === 'S' || key === 'ARROWDOWN') {
            e.preventDefault();
            sendCommand('BACKWARD');
        } else if (key === 'A' || key === 'ARROWLEFT') {
            e.preventDefault();
            sendCommand('LEFT');
        } else if (key === 'D' || key === 'ARROWRIGHT') {
            e.preventDefault();
            sendCommand('RIGHT');
        } else if (key === 'C') {
            sendCommand('CENTER');
        } else if (key === ' ' || key === 'SPACEBAR') {
            e.preventDefault();
            sendCommand('STOP');
        }
    });

    window.addEventListener('keyup', (e) => {
        const key = e.key.toUpperCase();
        if (key === 'W' || key === 'S' || key === 'ARROWUP' || key === 'ARROWDOWN') {
            sendCommand('STOP');
        }
    });
}

// ----------------------------------------------------
// 🎮 HTML5 Gamepad API Direct Control Implementation
// ----------------------------------------------------
let gamepadLoopId = null;
let lastGpState = { y: 'STOP', x: 'CENTER', btn0: false, btn1: false, btn2: false };

let gamepadCalibration = {
    calibrated: false,
    zeroX: 0,
    zeroY: 0,
    sampleCount: 0,
    sampleSumX: 0,
    sampleSumY: 0,
    gamepadId: null
};

function resetGamepadCalibration(id = null) {
    gamepadCalibration = {
        calibrated: false,
        zeroX: 0,
        zeroY: 0,
        sampleCount: 0,
        sampleSumX: 0,
        sampleSumY: 0,
        gamepadId: id
    };
}

function triggerGamepadRecalibration() {
    resetGamepadCalibration(gamepadCalibration.gamepadId);
    addLog('[GAMEPAD] 🎯 영점 보정 시작... 스틱 조작을 멈추고 잠시 대기해 주세요.', 'warning');
}

function setupGamepadListeners() {
    const badge = document.getElementById('gamepadStatus');
    if (badge) {
        badge.style.cursor = 'pointer';
        badge.title = '클릭하여 영점(중립 위치) 보정';
        badge.onclick = () => {
            if (gamepadCalibration.gamepadId) {
                triggerGamepadRecalibration();
            }
        };
    }

    window.addEventListener("gamepadconnected", (e) => {
        const gp = e.gamepad;
        resetGamepadCalibration(gp.id);
        updateGamepadStatusBadge(true, gp.id);
        addLog(`[GAMEPAD] 조이패드 연결됨: ${gp.id} (매핑: ${gp.mapping || 'non-standard'})`, 'success');
        addLog(`[GAMEPAD] 🎯 영점 보정 진행 중... 스틱을 가만히 두세요.`, 'info');
        if (!gamepadLoopId) {
            gamepadLoopId = requestAnimationFrame(pollGamepad);
        }
    });

    window.addEventListener("gamepaddisconnected", (e) => {
        resetGamepadCalibration(null);
        updateGamepadStatusBadge(false);
        addLog(`[GAMEPAD] 조이패드 연결 해제됨: ${e.gamepad.id}`, 'warning');
    });

    // Check if gamepads are already connected on initialization
    const initialGps = navigator.getGamepads ? navigator.getGamepads() : [];
    for (const gp of initialGps) {
        if (gp && gp.connected) {
            resetGamepadCalibration(gp.id);
            updateGamepadStatusBadge(true, gp.id);
            addLog(`[GAMEPAD] 기존 연결된 조이패드 사용: ${gp.id}`, 'success');
            addLog(`[GAMEPAD] 🎯 영점 보정 진행 중... 스틱을 가만히 두세요.`, 'info');
            if (!gamepadLoopId) {
                gamepadLoopId = requestAnimationFrame(pollGamepad);
            }
            break;
        }
    }
}

function updateGamepadStatusBadge(connected, name = '') {
    const badge = document.getElementById('gamepadStatus');
    if (!badge) return;
    if (connected) {
        const shortName = name.length > 18 ? name.substring(0, 18) + '...' : name;
        badge.innerText = `🎮 조이패드 연결됨 (${shortName}) [보정]`;
        badge.style.background = 'rgba(0, 230, 118, 0.2)';
        badge.style.color = '#00e676';
        badge.style.border = '1px solid rgba(0, 230, 118, 0.4)';
    } else {
        badge.innerText = '🎮 조이패드 연결 대기중';
        badge.style.background = 'rgba(255, 255, 255, 0.08)';
        badge.style.color = '#8a99ad';
        badge.style.border = 'none';
    }
}

function pollGamepad() {
    const gamepads = navigator.getGamepads ? navigator.getGamepads() : [];
    let activeGp = null;

    for (const gp of gamepads) {
        if (gp && gp.connected) {
            activeGp = gp;
            break;
        }
    }

    if (activeGp) {
        handleGamepadInput(activeGp);
        gamepadLoopId = requestAnimationFrame(pollGamepad);
    } else {
        gamepadLoopId = null;
    }
}

function handleGamepadInput(gp) {
    const axes = gp.axes || [];
    const buttons = gp.buttons || [];

    const rawX = axes[0] !== undefined ? axes[0] : 0;
    const rawY = axes[1] !== undefined ? axes[1] : 0;

    // 1. Zero-point Calibration Phase (samples 15 frames of neutral state)
    if (!gamepadCalibration.calibrated) {
        if (gamepadCalibration.gamepadId !== gp.id) {
            resetGamepadCalibration(gp.id);
        }
        gamepadCalibration.sampleSumX += rawX;
        gamepadCalibration.sampleSumY += rawY;
        gamepadCalibration.sampleCount++;

        if (gamepadCalibration.sampleCount >= 15) {
            gamepadCalibration.zeroX = gamepadCalibration.sampleSumX / 15;
            gamepadCalibration.zeroY = gamepadCalibration.sampleSumY / 15;
            gamepadCalibration.calibrated = true;
            addLog(`[GAMEPAD] ✅ 영점 보정 완료 (중립 기준값 -> X: ${gamepadCalibration.zeroX.toFixed(2)}, Y: ${gamepadCalibration.zeroY.toFixed(2)})`, 'success');
        }
        return; // Skip sending control commands during calibration
    }

    // 2. Apply Zero-point Offset
    const axisX = rawX - gamepadCalibration.zeroX;
    const axisY = rawY - gamepadCalibration.zeroY;

    const deadzone = 0.35;

    // D-Pad buttons (Only check if standard mapping and buttons exist)
    const isStandard = gp.mapping === 'standard';
    const dpadUp = isStandard && buttons[12] && buttons[12].pressed;
    const dpadDown = isStandard && buttons[13] && buttons[13].pressed;
    const dpadLeft = isStandard && buttons[14] && buttons[14].pressed;
    const dpadRight = isStandard && buttons[15] && buttons[15].pressed;

    // 3. Throttle / Backwards (Y-Axis Stick)
    let currentY = 'STOP';
    if (axisY < -deadzone || dpadUp) {
        currentY = 'FORWARD';
    } else if (axisY > deadzone || dpadDown) {
        currentY = 'BACKWARD';
    }

    // 4. Steering Left / Right (X-Axis Stick)
    let currentX = 'CENTER';
    if (axisX < -deadzone || dpadLeft) {
        currentX = 'LEFT';
    } else if (axisX > deadzone || dpadRight) {
        currentX = 'RIGHT';
    }

    // Trigger actions only when input state changes to prevent spamming HTTP requests
    if (currentY !== lastGpState.y) {
        if (currentY === 'FORWARD') sendCommand('FORWARD');
        else if (currentY === 'BACKWARD') sendCommand('BACKWARD');
        else if (currentY === 'STOP' && lastGpState.y !== 'STOP') sendCommand('STOP');
        lastGpState.y = currentY;
    }

    if (currentX !== lastGpState.x) {
        if (currentX === 'LEFT') sendCommand('LEFT');
        else if (currentX === 'RIGHT') sendCommand('RIGHT');
        else if (currentX === 'CENTER' && lastGpState.x !== 'CENTER') sendCommand('CENTER');
        lastGpState.x = currentX;
    }

    // 5. Gamepad Action Buttons
    // Button 0 (A / Cross): Emergency STOP
    const btn0Pressed = buttons[0] && buttons[0].pressed;
    if (btn0Pressed && !lastGpState.btn0) {
        sendCommand('STOP');
    }
    lastGpState.btn0 = btn0Pressed;

    // Button 1 (B / Circle): CENTER Servo (100°)
    const btn1Pressed = buttons[1] && buttons[1].pressed;
    if (btn1Pressed && !lastGpState.btn1) {
        sendCommand('CENTER');
    }
    lastGpState.btn1 = btn1Pressed;

    // Button 2 (X / Square): Toggle Autonomous Mode
    const btn2Pressed = buttons[2] && buttons[2].pressed;
    if (btn2Pressed && !lastGpState.btn2) {
        toggleAutoDrive(!isAutoMode);
    }
    lastGpState.btn2 = btn2Pressed;
}

function addLog(msg, type = 'info') {
    const console = document.getElementById('logConsole');
    const line = document.createElement('div');
    line.className = `log-line ${type}`;
    const timestamp = new Date().toLocaleTimeString();
    line.innerText = `[${timestamp}] ${msg}`;
    console.appendChild(line);
    console.scrollTop = console.scrollHeight;
}

function clearLogs() {
    document.getElementById('logConsole').innerHTML = '';
}

