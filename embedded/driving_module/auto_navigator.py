"""
Autonomous Vision Navigation Controller for Jetson Orin Nano
Integrates Camera Geometry (Height: 20cm, Downward Pitch: 15deg),
YOLO Instance Segmentation, Road/Sidewalk Boundary Protection,
Obstacle Bypass Navigation, and Motor Driver Execution.

Obstacle behavior is a timed state machine:
    CRUISE      - path clear, drive straight (sidewalk guard nudges away from road)
    STOP_HOLD   - obstacle appeared -> brake immediately and observe it
                    obstacle stays at the same spot for OBSTACLE_STOP_HOLD_SEC -> AVOID
                    obstacle leaves                                            -> CRUISE (straight on)
    AVOID       - timed evasive turn around the obstacle
    PASS_BY     - run straight past the obstacle until the path ahead is clear
    RECOVER     - mirrored counter-steer that puts the heading back where it was, then CRUISE
    REVERSE     - escape hatch when an evasive turn ends up blocked anyway
    BLOCKED     - both sides failed repeatedly: park safely until the path clears

Two fail-safe layers sit ABOVE that state machine:
    LINK_LOST         - operator heartbeat or camera frames went stale -> brake, require re-arm
    HOLD_NO_EVIDENCE  - no positive evidence of drivable ground -> brake, resume when it returns
"""

import time
import math
import logging
import threading
import numpy as np
import cv2
import config
from motor_driver import get_motor_driver

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# Navigation behavior states
STATE_CRUISE = "CRUISE"
STATE_STOP_HOLD = "STOP_HOLD"
STATE_AVOID = "AVOID"
STATE_PASS_BY = "PASS_BY"
STATE_RECOVER = "RECOVER"
STATE_REVERSE = "REVERSE"
STATE_BLOCKED = "BLOCKED"
STATE_LINK_LOST = "LINK_LOST"
STATE_HOLD_NO_EVIDENCE = "HOLD_NO_EVIDENCE"


class AutoNavigator:
    def __init__(self, motor_mode=None):
        self.motor_driver = get_motor_driver(motor_mode)

        # Camera geometry settings
        self.camera_height_m = getattr(config, 'CAMERA_HEIGHT_CM', 20.0) / 100.0  # 0.20 m
        self.camera_pitch_deg = getattr(config, 'CAMERA_PITCH_DEG', 15.0)        # 15 deg downward

        self.is_auto = False
        self.running = False
        self.lock = threading.Lock()

        # --- Obstacle Behavior State Machine ---
        now = time.monotonic()
        self.state = STATE_CRUISE
        self.state_since = now
        self.obstacle_anchor = None      # The obstacle we braked for (position snapshot)
        self.anchor_since = now          # When the anchor was placed / last re-anchored
        self.anchor_last_seen_t = now    # Last frame the anchored obstacle was matched
        self.stop_entered_t = now        # When the current stop-and-observe began
        self.obstacle_seen_streak = 0    # Consecutive frames with a blocking obstacle
        self.avoid_dir = "LEFT"
        self.avoid_attempts = 0          # Evasive turns started for the current obstacle
        self.last_close_obstacle_t = 0.0 # Last time an obstacle was seen at close range
        self.recover_aborts = 0          # Turn-backs postponed because the obstacle reappeared

        # --- Fail-safe layer ---
        # Both start "stale" on purpose: arming is refused until a real heartbeat and a real
        # camera frame have arrived, so a freshly booted server cannot be driven blind.
        self.last_heartbeat_t = 0.0
        self.last_frame_t = 0.0
        self.watchdog_tripped = False
        self.watchdog_reason = ""
        self.needs_rearm = False
        self.blind_forward_since = None   # When blind (no-evidence) forward travel began
        self.evidence_provider = None     # Callable(percept) -> evidence dict, set by the app
        self.last_evidence = {"ok": False, "source": "NONE", "confidence": None, "lookahead_m": None}

        self._shutdown = False
        self._watchdog_thread = None
        if getattr(config, 'WATCHDOG_ENABLED', True):
            self._watchdog_thread = threading.Thread(target=self._watchdog_loop, daemon=True)
            self._watchdog_thread.start()
            logging.info("[AutoNav] Watchdog thread started "
                         f"(link {getattr(config, 'WATCHDOG_LINK_TIMEOUT_SEC', 2.5)}s / "
                         f"frame {getattr(config, 'WATCHDOG_FRAME_TIMEOUT_SEC', 1.0)}s)")
        self.cooldown_until = 0.0        # Suppress new stop triggers right after a maneuver

        # Telemetry Cache
        self.last_telemetry = {
            "is_auto": False,
            "motor_direction": "STOP",
            "current_speed": config.DEFAULT_SPEED,
            "current_angle": config.STEERING_CENTER,
            "action_status": "MANUAL_IDLE",
            "front_obstacle_detected": False,
            "road_boundary_detected": False,
            "nearest_obstacle_dist_m": None,
            "suggested_steer_angle": config.STEERING_CENTER,
            "nav_state": STATE_CRUISE,
            "state_elapsed_sec": 0.0,
            "stop_hold_remaining_sec": None,
            "obstacle_persistent_sec": None,
            "avoid_direction": None,
            "avoid_attempts": 0,
            "needs_operator": False,
            "link_ok": False,
            "frame_ok": False,
            "ms_since_heartbeat": 0,
            "frame_age_ms": 0,
            "needs_rearm": False,
            "watchdog_reason": None,
            "evidence_source": "NONE",
            "evidence_ok": False,
            "evidence_gate_active": bool(getattr(config, 'REQUIRE_DRIVABLE_EVIDENCE', False)),
            "blind_forward_sec": None
        }

    # ==================================================================
    # 0. Fail-safe layer: watchdog + arming
    # ==================================================================
    def notify_heartbeat(self):
        """Called by the operator link (dashboard). Proof that someone can still stop the bot."""
        with self.lock:
            self.last_heartbeat_t = time.monotonic()

    def notify_frame(self):
        """Called once per processed camera frame. Proof that perception is alive."""
        with self.lock:
            self.last_frame_t = time.monotonic()

    def _link_status(self, now):
        """(link_ok, frame_ok, age_link, age_frame) - caller must hold the lock."""
        link_timeout = getattr(config, 'WATCHDOG_LINK_TIMEOUT_SEC', 2.5)
        frame_timeout = getattr(config, 'WATCHDOG_FRAME_TIMEOUT_SEC', 1.0)
        age_link = now - self.last_heartbeat_t
        age_frame = now - self.last_frame_t
        return age_link <= link_timeout, age_frame <= frame_timeout, age_link, age_frame

    def _watchdog_loop(self):
        """
        Runs independently of the vision loop on purpose. If the capture thread dies, the state
        machine stops being called entirely and the motor would otherwise hold its last throttle.
        """
        tick = 1.0 / max(1, getattr(config, 'WATCHDOG_TICK_HZ', 10))
        while not self._shutdown:
            time.sleep(tick)
            try:
                now = time.monotonic()
                with self.lock:
                    link_ok, frame_ok, age_link, age_frame = self._link_status(now)
                    if link_ok and frame_ok:
                        continue

                    # Nothing is moving and nothing is armed: stay quiet (no I2C traffic).
                    idle = (not self.is_auto) and self.motor_driver.current_direction == "STOP"
                    if idle and self.watchdog_tripped:
                        continue

                    reason = ("OPERATOR LINK LOST (%.1fs)" % age_link if not link_ok
                              else "VISION STALLED (%.1fs)" % age_frame)
                    first_trip = not self.watchdog_tripped
                    self.watchdog_tripped = True
                    self.watchdog_reason = reason

                    if idle:
                        continue

                    # Brake, disarm, and demand an explicit re-arm: whoever was supervising
                    # this run has lost visibility, so resuming on our own is not acceptable.
                    self.motor_driver.stop()
                    was_auto = self.is_auto
                    self.is_auto = False
                    self.needs_rearm = True
                    self.blind_forward_since = None
                    self._enter(STATE_LINK_LOST, now, reason=reason)
                    self.last_telemetry["action_status"] = f"WATCHDOG -> {reason}, MOTORS STOPPED"
                    self.last_telemetry["nav_state"] = STATE_LINK_LOST
                    self.last_telemetry["is_auto"] = False
                    self.last_telemetry["motor_direction"] = self.motor_driver.current_direction
                if first_trip:
                    logging.warning(f"[AutoNav] WATCHDOG TRIPPED: {reason} "
                                    f"-> motors stopped{', auto disengaged' if was_auto else ''}")
            except Exception as e:   # a watchdog that can die is not a watchdog
                logging.error(f"[AutoNav] Watchdog tick error: {e}")

    def set_evidence_provider(self, provider):
        """
        Registers the drivable-surface evidence source.
        provider(percept) -> {"ok": bool, "source": str, "confidence": float, "lookahead_m": float}
        Until a free-space module exists this stays None and evidence_source reports NONE.
        """
        self.evidence_provider = provider
        logging.info(f"[AutoNav] Evidence provider set: {getattr(provider, '__name__', provider)}")

    def shutdown(self):
        self._shutdown = True
        t = self._watchdog_thread
        if t is not None:
            t.join(timeout=1.0)

    def update_camera_geometry(self, height_cm=None, pitch_deg=None):
        with self.lock:
            if height_cm is not None:
                config.CAMERA_HEIGHT_CM = float(height_cm)
                self.camera_height_m = float(height_cm) / 100.0
            if pitch_deg is not None:
                config.CAMERA_PITCH_DEG = float(pitch_deg)
                self.camera_pitch_deg = float(pitch_deg)
        logging.info(f"[AutoNav] Updated Camera Geometry: Height={self.camera_height_m}m, Pitch={self.camera_pitch_deg}°")

    def estimate_ground_distance(self, pixel_y, frame_height=480):
        """
        Estimates ground distance in meters from pixel_y using camera height and pitch.
        With camera at 20cm height and 15° pitch downward:
        pixel_y = frame_height (bottom) -> closest ground (~0.7m)
        pixel_y = frame_height / 2 (horizon) -> further ground (~2.5m)
        """
        if pixel_y <= frame_height * 0.3:
            return 4.0  # Far away

        # Normalized vertical angle offset from center
        v_fov_deg = 45.0  # Approx vertical FOV
        norm_y = (pixel_y - (frame_height / 2.0)) / (frame_height / 2.0)  # -1.0 to +1.0
        angle_off_deg = norm_y * (v_fov_deg / 2.0)

        total_pitch_rad = math.radians(self.camera_pitch_deg + angle_off_deg)
        if math.sin(total_pitch_rad) <= 0.05:
            return 5.0

        dist_m = self.camera_height_m / math.sin(total_pitch_rad)
        return max(0.2, min(6.0, round(dist_m, 2)))

    # ==================================================================
    # 1. Perception
    # ==================================================================
    def _perceive(self, img_bgr, results):
        """Extracts front obstacles, road zone objects and steering geometry from YOLO output."""
        h, w = img_bgr.shape[:2]
        center_x = w // 2

        res = results[0]
        boxes = res.boxes

        corridor_left = config.SAFE_CORRIDOR_LEFT_X
        corridor_right = config.SAFE_CORRIDOR_RIGHT_X

        front_obstacles = []
        road_objects = []
        nearest_dist_m = None
        road_boundary_detected = False

        if boxes is not None and len(boxes) > 0:
            for idx in range(len(boxes)):
                box = boxes[idx]
                cls_id = int(box.cls[0].item())
                score = float(box.conf[0].item())

                xyxy = box.xyxy[0].cpu().numpy().tolist()
                x1, y1, x2, y2 = [int(v) for v in xyxy]

                obj_cx = (x1 + x2) // 2
                obj_bottom_y = y2

                dist_m = self.estimate_ground_distance(obj_bottom_y, frame_height=h)

                # Road vehicle detection (cars, buses, trucks indicate road zone)
                if cls_id in config.ROAD_VEHICLE_CLASS_IDS:
                    road_objects.append({
                        "id": idx, "cls": cls_id, "score": score, "box": [x1, y1, x2, y2], "cx": obj_cx, "dist_m": dist_m
                    })
                    if obj_cx > w * 0.4:
                        road_boundary_detected = True

                # General Obstacle detection (people, bikes, dogs, chairs, vehicles)
                elif cls_id in config.OBSTACLE_CLASS_IDS or score > 0.3:
                    # Check if inside or near front corridor
                    if (x1 < corridor_right and x2 > corridor_left) and y2 > h * 0.4:
                        blocking = (
                            dist_m <= getattr(config, 'OBSTACLE_TRIGGER_DIST_M', 1.20)
                            or y2 >= getattr(config, 'OBSTACLE_TRIGGER_Y', 300)
                        )
                        front_obstacles.append({
                            "id": idx, "cls": cls_id, "score": score, "box": [x1, y1, x2, y2],
                            "cx": obj_cx, "dist_m": dist_m, "bottom_y": y2, "blocking": blocking
                        })
                        if nearest_dist_m is None or dist_m < nearest_dist_m:
                            nearest_dist_m = dist_m

        # Closest first (largest bottom_y = nearest to the bot)
        front_obstacles.sort(key=lambda o: o["bottom_y"], reverse=True)
        blocking_obstacles = [o for o in front_obstacles if o["blocking"]]

        evidence = {"ok": False, "source": "NONE", "confidence": None, "lookahead_m": None}
        if self.evidence_provider is not None:
            try:
                got = self.evidence_provider({"h": h, "w": w, "img": img_bgr, "results": results})
                if got:
                    evidence.update(got)
            except Exception as e:
                logging.error(f"[AutoNav] Evidence provider failed: {e}")
                evidence = {"ok": False, "source": "ERROR", "confidence": None, "lookahead_m": None}

        return {
            "evidence": evidence,
            "h": h, "w": w, "center_x": center_x,
            "corridor_left": corridor_left, "corridor_right": corridor_right,
            "front_obstacles": front_obstacles,
            "blocking_obstacles": blocking_obstacles,
            "closest": blocking_obstacles[0] if blocking_obstacles else None,
            "road_objects": road_objects,
            "road_boundary_detected": road_boundary_detected,
            "nearest_dist_m": nearest_dist_m
        }

    # ==================================================================
    # 2. Behavior State Machine
    # ==================================================================
    def _enter(self, state, now, reason=""):
        if state != self.state:
            logging.info(f"[AutoNav] STATE {self.state} -> {state} {('(' + reason + ')') if reason else ''}")
        self.state = state
        self.state_since = now

    def _set_anchor(self, obstacle, now):
        """Snapshots the obstacle position we are waiting on ('is it still at that spot?')."""
        self.obstacle_anchor = dict(obstacle)
        self.anchor_since = now
        self.anchor_last_seen_t = now

    def _enter_stop_hold(self, obstacle, now, reason=""):
        was_holding = self.state == STATE_STOP_HOLD
        self._enter(STATE_STOP_HOLD, now, reason)
        self._set_anchor(obstacle, now)
        if not was_holding:
            self.stop_entered_t = now

    def _match_anchor(self, obstacles):
        """Finds the detection that is still at the anchored position (same obstacle, same spot)."""
        if self.obstacle_anchor is None:
            return None

        tol_x = getattr(config, 'ANCHOR_MATCH_TOL_X', 90)
        tol_y = getattr(config, 'ANCHOR_MATCH_TOL_Y', 80)
        anchor = self.obstacle_anchor

        best, best_score = None, None
        for o in obstacles:
            dx = abs(o["cx"] - anchor["cx"])
            dy = abs(o["bottom_y"] - anchor["bottom_y"])
            if dx <= tol_x and dy <= tol_y:
                score = dx + dy
                if best_score is None or score < best_score:
                    best, best_score = o, score
        return best

    def _is_critical(self, obstacle, center_x):
        """
        Emergency-brake test. Proximity alone is not enough: the obstacle must also overlap
        the bumper band in front of the wheels, otherwise every obstacle we are actively
        steering past would abort the maneuver that is getting us around it.
        """
        crit_y = getattr(config, 'CRITICAL_STOP_Y', 450)
        crit_d = getattr(config, 'CRITICAL_STOP_DIST_M', 0.50)
        near = obstacle["bottom_y"] > crit_y or (obstacle["dist_m"] is not None and obstacle["dist_m"] < crit_d)
        if not near:
            return False

        half_w = getattr(config, 'CRITICAL_BUMPER_HALF_W', 70)
        x1, _, x2, _ = obstacle["box"]
        return x1 < center_x + half_w and x2 > center_x - half_w

    def _critical_obstacle(self, p):
        """Closest obstacle that warrants an emergency brake, or None."""
        for o in p["blocking_obstacles"]:  # already sorted nearest-first
            if self._is_critical(o, p["center_x"]):
                return o
        return None

    def _travel_sec(self, dist_m, speed_pct):
        """How long the bot needs to cover dist_m at the given throttle percentage."""
        mps = speed_pct * getattr(config, 'BOT_SPEED_MPS_PER_PCT', 0.010)
        return dist_m / max(0.01, mps)

    def _is_close(self, obstacle):
        """Close enough that losing sight of it means 'blind spot', not 'gone'."""
        return (obstacle["bottom_y"] >= getattr(config, 'CLOSE_OBSTACLE_Y', 300)
                or (obstacle["dist_m"] is not None
                    and obstacle["dist_m"] <= getattr(config, 'CLOSE_OBSTACLE_DIST_M', 1.20)))

    def _steer_for(self, direction, amount=None):
        amount = getattr(config, 'AVOID_STEER_AMOUNT', 55) if amount is None else amount
        if direction == "LEFT":
            return max(getattr(config, 'STEERING_MAX_LEFT', 40), config.STEERING_CENTER - amount)
        return min(getattr(config, 'STEERING_MAX_RIGHT', 160), config.STEERING_CENTER + amount)

    def _pick_avoid_dir(self, p):
        """Chooses the side with more lateral clearance; never steers into a detected road zone."""
        anchor = self.obstacle_anchor
        if anchor is None:
            return "LEFT"

        x1, _, x2, _ = anchor["box"]
        gap_left = max(0, x1 - p["corridor_left"])
        gap_right = max(0, p["corridor_right"] - x2)

        if gap_left == gap_right:
            direction = "LEFT" if anchor["cx"] >= p["center_x"] else "RIGHT"
        else:
            direction = "LEFT" if gap_left > gap_right else "RIGHT"

        # Road detected on the right side -> stay on the sidewalk, evade left instead
        if direction == "RIGHT" and p["road_boundary_detected"]:
            direction = "LEFT"
        return direction

    def _evidence_ok(self, p):
        """
        Positive evidence that there is drivable ground ahead. Absence of evidence is NOT
        treated as permission to drive - that inversion is the whole point of this gate.
        """
        ev = p.get("evidence") or {}
        self.last_evidence = ev
        if not ev.get("ok"):
            return False
        conf = ev.get("confidence")
        if conf is not None and conf < getattr(config, 'DRIVABLE_MIN_CONFIDENCE', 0.5):
            return False
        look = ev.get("lookahead_m")
        if look is not None and look < getattr(config, 'DRIVABLE_MIN_LOOKAHEAD_M', 0.6):
            return False
        return True

    def _decision(self, motion, speed, steer, msg):
        return {"motion": motion, "speed": speed, "steer": steer, "msg": msg, "state": self.state}

    def _stop_decision(self, msg):
        return self._decision("STOP", 0, config.STEERING_CENTER, msg)

    def _decide(self, p, now):
        closest = p["closest"]

        # --- Priority 1: fail-safe layer. Link/vision loss outranks every driving concern. ---
        link_ok, frame_ok, age_link, age_frame = self._link_status(now)
        if not (link_ok and frame_ok):
            self._enter(STATE_LINK_LOST, now, reason="LINK/VISION STALE")
            stale = f"link {age_link:.1f}s" if not link_ok else f"frames {age_frame:.1f}s"
            return self._stop_decision(f"LINK LOST -> STOPPED ({stale}), re-arm required")
        if self.needs_rearm:
            return self._stop_decision("RE-ARM REQUIRED -> engage auto mode again to continue")

        # --- Priority 2: drivable-ground evidence gate ---
        evidence_ok = self._evidence_ok(p)
        if getattr(config, 'REQUIRE_DRIVABLE_EVIDENCE', False) and not evidence_ok:
            if self.state != STATE_HOLD_NO_EVIDENCE:
                self._enter(STATE_HOLD_NO_EVIDENCE, now, reason="NO DRIVABLE EVIDENCE")
            self.blind_forward_since = None
            src = (p.get("evidence") or {}).get("source", "NONE")
            return self._stop_decision(
                f"NO DRIVABLE GROUND EVIDENCE (source={src}) -> HOLDING")
        if self.state == STATE_HOLD_NO_EVIDENCE:
            # Evidence came back: resume by itself (unlike a link loss, nothing was unsupervised)
            self._enter(STATE_CRUISE, now, reason="DRIVABLE EVIDENCE RESTORED")

        # --- Blind-running leash: bounded travel when we have no surface evidence at all ---
        leash = getattr(config, 'NO_EVIDENCE_LEASH_SEC', 30.0)
        if evidence_ok:
            self.blind_forward_since = None
        elif leash > 0 and self.blind_forward_since is not None:
            blind_for = now - self.blind_forward_since
            if blind_for >= leash:
                self.motor_driver.stop()
                self.needs_rearm = True
                self.blind_forward_since = None
                self._enter(STATE_HOLD_NO_EVIDENCE, now, reason=f"BLIND LEASH {blind_for:.0f}s")
                return self._stop_decision(
                    f"BLIND TRAVEL LIMIT ({blind_for:.0f}s without surface evidence) -> re-arm required")

        # --- Obstacle presence debounce (avoid braking on single-frame false positives) ---
        if closest is not None:
            self.obstacle_seen_streak += 1
        else:
            self.obstacle_seen_streak = 0
        confirmed = self.obstacle_seen_streak >= getattr(config, 'OBSTACLE_CONFIRM_FRAMES', 2)

        # Blind-spot memory: the camera cannot see an obstacle that is right beside the bumper,
        # so remember when one was last seen at close range.
        if any(self._is_close(o) for o in p["front_obstacles"]):
            self.last_close_obstacle_t = now

        crit_obs = self._critical_obstacle(p)

        # --- Emergency brake overrides every state (except REVERSE, which is moving away) ---
        if crit_obs is not None:
            if self.state in (STATE_CRUISE, STATE_RECOVER):
                self._enter_stop_hold(crit_obs, now, reason="CRITICAL PROXIMITY")
            elif self.state == STATE_AVOID:
                # The evasive turn is running into something. Give the turn a short grace period
                # first - at the start of the maneuver the obstacle is still straight ahead.
                if now - self.state_since >= getattr(config, 'AVOID_ABORT_GRACE_SEC', 0.6):
                    self._on_bypass_blocked(crit_obs, now)
            elif self.state == STATE_PASS_BY:
                # Still nose-to-nose with something after the turn: this bypass side has failed
                self._on_bypass_blocked(crit_obs, now)

        if self.state == STATE_STOP_HOLD:
            return self._run_stop_hold(p, now)
        if self.state == STATE_AVOID:
            return self._run_avoid(p, now)
        if self.state == STATE_PASS_BY:
            return self._run_pass_by(p, now)
        if self.state == STATE_RECOVER:
            return self._run_recover(p, now)
        if self.state == STATE_REVERSE:
            return self._run_reverse(p, now)
        if self.state == STATE_BLOCKED:
            return self._run_blocked(p, now)
        if self.state in (STATE_LINK_LOST, STATE_HOLD_NO_EVIDENCE):
            # Cleared by the checks above; fall through to normal cruising.
            self._enter(STATE_CRUISE, now, reason="FAIL-SAFE CLEARED")
        return self._run_cruise(p, now, confirmed, evidence_ok)

    def _enter_avoid(self, p, now, reason="", flip=False):
        if flip:
            self.avoid_dir = "RIGHT" if self.avoid_dir == "LEFT" else "LEFT"
        else:
            self.avoid_dir = self._pick_avoid_dir(p)
        self.avoid_attempts += 1
        self.recover_aborts = 0
        self._enter(STATE_AVOID, now, reason=reason)

    def _on_bypass_blocked(self, obstacle, now):
        """An evasive turn hit something: back off and try the other side, or give up safely."""
        if self.avoid_attempts >= getattr(config, 'AVOID_MAX_ATTEMPTS', 3):
            self._enter(STATE_BLOCKED, now, reason="ALL BYPASS ATTEMPTS BLOCKED")
        else:
            # Reverse first: turning the other way while nose-to-nose would scrape the obstacle
            self._enter(STATE_REVERSE, now, reason="BYPASS BLOCKED")

    def _run_cruise(self, p, now, confirmed, evidence_ok=True):
        closest = p["closest"]

        # Cruise throttle is capped while running blind. Applied here and NOT in _apply on
        # purpose: the bypass legs are timed from CORNERING_SPEED, so capping them globally
        # would make the bot travel less than the distance each leg was computed for.
        cruise_speed = config.SAFE_CRUISE_SPEED
        blind_note = ""
        if not evidence_ok:
            cap = getattr(config, 'NO_EVIDENCE_SPEED_CAP', 35)
            if cap < cruise_speed:
                cruise_speed = cap
                blind_note = f" [BLIND, {cap}% cap]"

        # Obstacle appeared -> brake first, decide later
        if confirmed and closest is not None and now >= self.cooldown_until:
            self._enter_stop_hold(closest, now, reason="OBSTACLE DETECTED")
            return self._stop_decision(
                f"OBSTACLE DETECTED -> STOP & OBSERVE ({closest['dist_m']}m)"
            )

        # Clock the blind-travel leash only while actually rolling forward
        if not evidence_ok and self.blind_forward_since is None:
            self.blind_forward_since = now

        if p["road_boundary_detected"]:
            steer = config.STEERING_CENTER - 20
            return self._decision("FORWARD", cruise_speed, steer,
                                  "SIDEWALK GUARD -> Nudge Left away from Road" + blind_note)

        cooling = now < self.cooldown_until
        msg = "SIDEWALK CRUISE -> Clear Path Ahead"
        if cooling:
            msg = f"SIDEWALK CRUISE -> Post-Bypass Settle ({self.cooldown_until - now:.1f}s)"
        return self._decision("FORWARD", cruise_speed, config.STEERING_CENTER, msg + blind_note)

    def _run_stop_hold(self, p, now):
        """Standing still in front of the obstacle: wait it out, then avoid only if it stays put."""
        hold_sec = getattr(config, 'OBSTACLE_STOP_HOLD_SEC', 3.0)
        max_sec = getattr(config, 'STOP_HOLD_MAX_SEC', 6.0)
        grace = getattr(config, 'OBSTACLE_CLEAR_GRACE_SEC', 0.6)

        held_total = now - self.stop_entered_t
        match = self._match_anchor(p["front_obstacles"])

        if match is not None:
            self.anchor_last_seen_t = now
            persisted = now - self.anchor_since

            if persisted >= hold_sec or held_total >= max_sec:
                self._enter_avoid(p, now, reason=f"OBSTACLE STATIC {persisted:.1f}s")
                return self._run_avoid(p, now)

            remaining = max(0.0, hold_sec - persisted)
            return self._stop_decision(
                f"STOP HOLD -> Obstacle holding position ({persisted:.1f}s, avoid in {remaining:.1f}s)"
            )

        # Anchored obstacle not matched this frame
        missing_for = now - self.anchor_last_seen_t

        if missing_for < grace:
            return self._stop_decision(
                f"STOP HOLD -> Verifying obstacle ({missing_for:.1f}s without detection)"
            )

        other = p["closest"]
        if other is not None:
            # Obstacle moved, or a different one took the path: restart the observation window
            if held_total >= max_sec:
                self._enter_avoid(p, now, reason=f"BLOCKED {held_total:.1f}s")
                return self._run_avoid(p, now)

            self._set_anchor(other, now)
            return self._stop_decision(
                f"STOP HOLD -> Obstacle moved, re-observing new position ({other['dist_m']}m)"
            )

        # Path is clear again -> straight on, no avoidance maneuver
        self.obstacle_anchor = None
        self.obstacle_seen_streak = 0
        self.avoid_attempts = 0
        self._enter(STATE_CRUISE, now, reason=f"OBSTACLE CLEARED after {held_total:.1f}s")
        return self._decision("FORWARD", config.SAFE_CRUISE_SPEED, config.STEERING_CENTER,
                              f"OBSTACLE CLEARED ({held_total:.1f}s) -> RESUME STRAIGHT")

    def _run_avoid(self, p, now):
        speed = getattr(config, 'CORNERING_SPEED', 35)
        turn_sec = self._travel_sec(getattr(config, 'AVOID_TURN_DIST_M', 0.40), speed)
        elapsed = now - self.state_since

        if elapsed >= turn_sec:
            self._enter(STATE_PASS_BY, now, reason="BYPASS TURN DONE")
            return self._run_pass_by(p, now)

        steer = self._steer_for(self.avoid_dir)
        return self._decision(
            "FORWARD", speed, steer,
            f"BYPASS -> EVADE {self.avoid_dir} HARD ({steer}°, {elapsed:.1f}/{turn_sec:.1f}s, try {self.avoid_attempts})"
        )

    def _run_pass_by(self, p, now):
        """
        Straight leg alongside the obstacle. Turning back immediately after the evasive
        turn would swing the tail into the obstacle while it sits in the camera's near
        blind spot, so hold the offset heading until the path ahead is genuinely clear.
        """
        speed = getattr(config, 'CORNERING_SPEED', 35)
        leg_sec = self._travel_sec(getattr(config, 'AVOID_PASS_DIST_M', 0.60), speed)
        blind_sec = getattr(config, 'AVOID_BLIND_CLEAR_SEC', 1.0)
        extra_cap = getattr(config, 'AVOID_PASS_EXTRA_MAX_SEC', 2.0)

        elapsed = now - self.state_since
        since_close = now - self.last_close_obstacle_t
        leg_done = elapsed >= leg_sec
        blind_done = since_close >= blind_sec

        if (leg_done and blind_done) or elapsed >= leg_sec + extra_cap:
            reason = "OBSTACLE PASSED" if blind_done else "PASS LEG CAPPED"
            self._enter(STATE_RECOVER, now, reason=reason)
            return self._run_recover(p, now)

        note = f"{elapsed:.1f}/{leg_sec:.1f}s"
        if leg_done:
            note += f", waiting {blind_sec - since_close:.1f}s for blind spot to clear"
        return self._decision(
            "FORWARD", speed, config.STEERING_CENTER,
            f"BYPASS -> PASS ALONGSIDE ({note})"
        )

    def _run_recover(self, p, now):
        speed = getattr(config, 'CORNERING_SPEED', 35)
        recover_sec = self._travel_sec(getattr(config, 'AVOID_TURN_DIST_M', 0.40), speed)
        elapsed = now - self.state_since

        if elapsed >= recover_sec:
            self.cooldown_until = now + getattr(config, 'POST_AVOID_COOLDOWN_SEC', 1.0)
            self.obstacle_anchor = None
            self.avoid_attempts = 0
            self.obstacle_seen_streak = 0
            self._enter(STATE_CRUISE, now, reason="HEADING RECOVERED")
            return self._run_cruise(p, now, confirmed=False, evidence_ok=self._evidence_ok(p))

        # Mirror image of the evasive turn (same steer, same speed, same duration) so the yaw
        # cancels out exactly: the bot ends up laterally displaced but back on its original heading.
        # Calibration-free feedback: if swinging back brings the obstacle into the path ahead
        # again, the bot has not actually cleared it yet - go straight for longer instead.
        if p["closest"] is not None and self.recover_aborts < getattr(config, 'RECOVER_MAX_ABORTS', 3):
            self.recover_aborts += 1
            self._enter(STATE_PASS_BY, now, reason="OBSTACLE BACK IN VIEW, EXTEND PASS LEG")
            return self._run_pass_by(p, now)

        counter_dir = "RIGHT" if self.avoid_dir == "LEFT" else "LEFT"
        steer = self._steer_for(counter_dir)
        return self._decision(
            "FORWARD", speed, steer,
            f"RECOVER -> Straighten {counter_dir} ({steer}°, {elapsed:.1f}/{recover_sec:.1f}s)"
        )

    def _run_reverse(self, p, now):
        speed = getattr(config, 'REVERSE_SPEED', 35)
        reverse_sec = self._travel_sec(getattr(config, 'REVERSE_DIST_M', 0.25), speed)
        elapsed = now - self.state_since

        if elapsed >= reverse_sec:
            self._enter_avoid(p, now, reason="REVERSE DONE, RETRY OTHER SIDE", flip=True)
            return self._run_avoid(p, now)

        return self._decision(
            "BACKWARD", speed, config.STEERING_CENTER,
            f"BLOCKED -> REVERSE ESCAPE ({elapsed:.1f}/{reverse_sec:.1f}s), then EVADE {self.avoid_dir}"
        )

    def _run_blocked(self, p, now):
        """Parked: every bypass attempt failed. Waits for the path to open up on its own."""
        grace = getattr(config, 'OBSTACLE_CLEAR_GRACE_SEC', 0.6)
        blocked_for = now - self.state_since

        if p["closest"] is not None:
            self.anchor_last_seen_t = now
        elif now - self.anchor_last_seen_t >= grace:
            self.obstacle_anchor = None
            self.avoid_attempts = 0
            self.obstacle_seen_streak = 0
            self._enter(STATE_CRUISE, now, reason="PATH OPENED UP")
            return self._decision("FORWARD", config.SAFE_CRUISE_SPEED, config.STEERING_CENTER,
                                  f"PATH CLEARED after {blocked_for:.1f}s -> RESUME STRAIGHT")

        return self._stop_decision(
            f"BLOCKED -> No bypass available ({blocked_for:.1f}s), waiting / operator takeover"
        )

    # ==================================================================
    # 3. Actuation + Telemetry
    # ==================================================================
    def _apply(self, decision):
        # Hard gate, independent of the state machine: while the watchdog is tripped or a
        # re-arm is pending, nothing may reach the motors except a stop. This is deliberately
        # redundant with the checks in _decide - a bug up there must not be able to drive.
        if self.watchdog_tripped or self.needs_rearm:
            if self.motor_driver.current_direction != "STOP":
                self.motor_driver.stop()
            return

        if not self.is_auto:
            return

        speed = decision["speed"]
        if decision["motion"] == "STOP" or speed <= 0:
            self.motor_driver.stop()
            return

        self.motor_driver.set_angle(decision["steer"])
        if decision["motion"] == "BACKWARD":
            self.motor_driver.backward(speed)
        else:
            self.motor_driver.forward(speed)

    def _build_telemetry(self, p, decision, now):
        link_ok, frame_ok, age_link, age_frame = self._link_status(now)
        hold_remaining = None
        persistent = None
        if self.state == STATE_STOP_HOLD and self.obstacle_anchor is not None:
            persistent = round(now - self.anchor_since, 2)
            hold_remaining = round(max(0.0, getattr(config, 'OBSTACLE_STOP_HOLD_SEC', 3.0) - persistent), 2)

        return {
            "is_auto": self.is_auto,
            "motor_direction": self.motor_driver.current_direction,
            "current_speed": self.motor_driver.current_speed,
            "current_angle": self.motor_driver.current_angle,
            "action_status": decision["msg"],
            "front_obstacle_detected": len(p["front_obstacles"]) > 0,
            "road_boundary_detected": p["road_boundary_detected"],
            "nearest_obstacle_dist_m": p["nearest_dist_m"],
            "suggested_steer_angle": decision["steer"],
            "nav_state": self.state,
            "state_elapsed_sec": round(now - self.state_since, 2),
            "stop_hold_remaining_sec": hold_remaining,
            "obstacle_persistent_sec": persistent,
            "avoid_direction": self.avoid_dir if self.state in (
                STATE_AVOID, STATE_PASS_BY, STATE_RECOVER, STATE_REVERSE, STATE_BLOCKED) else None,
            "avoid_attempts": self.avoid_attempts,
            "needs_operator": self.state in (STATE_BLOCKED, STATE_LINK_LOST),
            "link_ok": link_ok,
            "frame_ok": frame_ok,
            "ms_since_heartbeat": int(age_link * 1000),
            "frame_age_ms": int(age_frame * 1000),
            "needs_rearm": self.needs_rearm,
            "watchdog_reason": self.watchdog_reason or None,
            "evidence_source": (self.last_evidence or {}).get("source", "NONE"),
            "evidence_ok": bool((self.last_evidence or {}).get("ok")),
            "evidence_gate_active": bool(getattr(config, 'REQUIRE_DRIVABLE_EVIDENCE', False)),
            "blind_forward_sec": (round(now - self.blind_forward_since, 1)
                                  if self.blind_forward_since else None)
        }

    def process_vision_navigation(self, img_bgr, results, drive=True):
        """
        Analyzes YOLO segmentation results on camera frame.
        Runs the obstacle behavior state machine, drives the motors (auto mode only)
        and returns the HUD-annotated frame plus telemetry.

        drive=False renders the HUD only (still images / snapshots) so one-off
        predictions cannot disturb the live driving state machine.
        """
        p = self._perceive(img_bgr, results)

        if not drive:
            preview = self._decision("STOP", 0, config.STEERING_CENTER, "STILL FRAME PREVIEW (no control)")
            hud = self.draw_navigation_hud(img_bgr, p, preview)
            return hud, dict(self.last_telemetry)

        now = time.monotonic()
        with self.lock:
            self.last_frame_t = now       # perception is alive (feeds the watchdog)
            decision = self._decide(p, now)
            self._apply(decision)
            self.last_telemetry = self._build_telemetry(p, decision, now)
            telemetry = dict(self.last_telemetry)

        hud = self.draw_navigation_hud(img_bgr, p, decision)
        return hud, telemetry

    # ==================================================================
    # 4. HUD Overlay
    # ==================================================================
    def draw_navigation_hud(self, img_bgr, percept, decision):
        """Draws visual HUD overlay: Safe Corridor, Obstacles, Anchor, Steering Arrow, State Banner"""
        hud = img_bgr.copy()
        h, w = hud.shape[:2]
        cx = w // 2

        steer_angle = decision["steer"]
        action_msg = decision["msg"]

        # 1. Draw Sidewalk Corridor Guide Lines
        cv2.line(hud, (config.SAFE_CORRIDOR_LEFT_X, int(h * 0.4)), (config.SAFE_CORRIDOR_LEFT_X, h), (254, 242, 0), 2)
        cv2.line(hud, (config.SAFE_CORRIDOR_RIGHT_X, int(h * 0.4)), (config.SAFE_CORRIDOR_RIGHT_X, h), (254, 242, 0), 2)

        # 2. Draw Front Obstacles (red = blocking the path, grey = tracked only)
        for obs in percept["front_obstacles"]:
            x1, y1, x2, y2 = obs["box"]
            color = (0, 0, 255) if obs["blocking"] else (150, 150, 150)
            cv2.rectangle(hud, (x1, y1), (x2, y2), color, 2)
            lbl = f"Obstacle ({obs['dist_m']}m)" if obs['dist_m'] else "Obstacle"
            cv2.putText(hud, lbl, (x1, max(15, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2)

        # 3. Draw Road Objects in Yellow/Orange
        for r_obj in percept["road_objects"]:
            x1, y1, x2, y2 = r_obj["box"]
            cv2.rectangle(hud, (x1, y1), (x2, y2), (0, 165, 255), 2)
            cv2.putText(hud, "ROAD ZONE", (x1, max(15, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 165, 255), 2)

        # 4. Draw the anchored obstacle we are waiting on (magenta) + hold countdown bar
        if self.state == STATE_STOP_HOLD and self.obstacle_anchor is not None:
            ax1, ay1, ax2, ay2 = self.obstacle_anchor["box"]
            cv2.rectangle(hud, (ax1, ay1), (ax2, ay2), (255, 0, 255), 2)
            cv2.putText(hud, "WATCHING", (ax1, min(h - 6, ay2 + 18)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 0, 255), 2)

            hold_sec = getattr(config, 'OBSTACLE_STOP_HOLD_SEC', 3.0)
            persisted = min(hold_sec, max(0.0, time.monotonic() - self.anchor_since))
            ratio = persisted / hold_sec if hold_sec > 0 else 1.0
            bar_w, bar_x, bar_y = 200, cx - 100, 48
            cv2.rectangle(hud, (bar_x, bar_y), (bar_x + bar_w, bar_y + 12), (60, 60, 60), -1)
            cv2.rectangle(hud, (bar_x, bar_y), (bar_x + int(bar_w * ratio), bar_y + 12), (255, 0, 255), -1)
            cv2.putText(hud, f"HOLD {persisted:.1f}/{hold_sec:.1f}s", (bar_x, bar_y + 28),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 0, 255), 1)

        # 5. Draw Steering / Motion Arrow
        steer_diff = steer_angle - config.STEERING_CENTER
        if decision["motion"] == "STOP":
            cv2.putText(hud, "STOPPED", (cx - 55, h - 30), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2)
        elif decision["motion"] == "BACKWARD":
            cv2.arrowedLine(hud, (cx, h - 100), (cx, h - 30), (0, 140, 255), 4, tipLength=0.3)
        else:
            arrow_end_x = int(cx + (steer_diff * 2.5))
            arrow_color = (0, 255, 0) if steer_diff == 0 else (0, 242, 254)
            cv2.arrowedLine(hud, (cx, h - 30), (arrow_end_x, h - 100), arrow_color, 4, tipLength=0.3)

        # 6. Draw Top Status Banner
        cv2.rectangle(hud, (0, 0), (w, 42), (15, 15, 15), -1)
        mode_text = "[AUTO ENGAGED]" if self.is_auto else "[MANUAL MODE]"
        mode_color = (0, 255, 160) if self.is_auto else (200, 200, 200)
        cv2.putText(hud, f"{mode_text} {self.state}", (10, 17), cv2.FONT_HERSHEY_SIMPLEX, 0.5, mode_color, 2)
        cv2.putText(hud, action_msg[:78], (10, 35), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (220, 220, 220), 1)

        return hud

    # ==================================================================
    # 5. Mode / Manual Control
    # ==================================================================
    def _reset_behavior_state(self):
        now = time.monotonic()
        self.state = STATE_CRUISE
        self.state_since = now
        self.obstacle_anchor = None
        self.anchor_since = now
        self.anchor_last_seen_t = now
        self.stop_entered_t = now
        self.obstacle_seen_streak = 0
        self.avoid_attempts = 0
        self.avoid_dir = "LEFT"
        self.last_close_obstacle_t = 0.0
        self.recover_aborts = 0
        self.cooldown_until = 0.0
        self.blind_forward_since = None

    def set_auto_mode(self, enabled):
        """Engaging auto mode is also the explicit re-arm after a watchdog trip."""
        with self.lock:
            if not enabled:
                self.is_auto = False
                self._reset_behavior_state()
                self.motor_driver.stop()
                self.needs_rearm = False
                self.last_telemetry["action_status"] = "MANUAL_MODE"
                self.last_telemetry["nav_state"] = self.state
                self.last_telemetry["is_auto"] = False
                logging.info("[AutoNav] Autonomous Mode: DISABLED")
                return {"success": True, "is_auto": False}

            # Arming requires a live operator link AND live camera frames. Refusing here is
            # what stops a freshly booted or disconnected server from being driven blind.
            now = time.monotonic()
            link_ok, frame_ok, age_link, age_frame = self._link_status(now)
            if not link_ok or not frame_ok:
                self.motor_driver.stop()
                detail = (f"operator link stale ({age_link:.1f}s)" if not link_ok
                          else f"camera frames stale ({age_frame:.1f}s)")
                self.last_telemetry["action_status"] = f"ARM REFUSED -> {detail}"
                logging.warning(f"[AutoNav] Auto mode arm REFUSED: {detail}")
                return {"success": False, "is_auto": False, "detail": detail}

            self.is_auto = True
            self._reset_behavior_state()
            self.watchdog_tripped = False
            self.watchdog_reason = ""
            self.needs_rearm = False
            self.last_telemetry["action_status"] = "AUTO_ENGAGED"
            self.last_telemetry["nav_state"] = self.state
            self.last_telemetry["is_auto"] = True
        logging.info("[AutoNav] Autonomous Mode: ENABLED (armed)")
        return {"success": True, "is_auto": True}

    def manual_command(self, cmd, param=None):
        with self.lock:
            if self.is_auto:
                return {"success": False, "detail": "Cannot execute manual commands while Auto Mode is active"}

            # The command itself proves the link is up right now. Manual forward latches a PWM
            # exactly like auto mode does, so the watchdog covers manual driving too.
            self.last_heartbeat_t = time.monotonic()
            if self.watchdog_tripped and cmd != "stop":
                self.motor_driver.stop()
                self.watchdog_tripped = False
                self.watchdog_reason = ""
                self.needs_rearm = False
                return {"success": False, "detail": "Watchdog had stopped the bot - command ignored, "
                                                    "state cleared. Re-issue to continue."}

            if cmd == "forward":
                self.motor_driver.forward(param)
            elif cmd == "backward":
                self.motor_driver.backward(param)
            elif cmd == "left":
                self.motor_driver.steer_left(param or getattr(config, 'STEERING_STEP', 15))
            elif cmd == "right":
                self.motor_driver.steer_right(param or getattr(config, 'STEERING_STEP', 15))
            elif cmd == "center":
                self.motor_driver.center()
            elif cmd == "stop":
                self.motor_driver.stop()
            elif cmd == "set_speed":
                self.motor_driver.set_speed(param)
            elif cmd == "set_angle":
                self.motor_driver.set_angle(param)

            self.last_telemetry.update({
                "motor_direction": self.motor_driver.current_direction,
                "current_speed": self.motor_driver.current_speed,
                "current_angle": self.motor_driver.current_angle,
                "action_status": f"MANUAL -> {cmd.upper()}"
            })

            return {"success": True, "direction": self.motor_driver.current_direction, "speed": self.motor_driver.current_speed, "angle": self.motor_driver.current_angle}
