export type RobotStatus = 'online' | 'warning' | 'charging' | 'offline'

export type RobotConnectivityStatus = 'ONLINE' | 'OFFLINE'
export type RobotHealthStatus = 'OK' | 'WARNING' | 'CRITICAL' | 'UNKNOWN'

export interface RobotListItem {
  robotId: string
  mqttId: string
  robotName: string
  serialNumber: string
  operationalStatus: string
  connectivityStatus: RobotConnectivityStatus
  lastReceivedAt: string | null
  batteryPercent: number | null
  charging: boolean | null
  jetsonTempC: number | null
  speedMps: number | null
  robotMode: string | null
  missionStatus: string | null
  overallHealthStatus: RobotHealthStatus
}

export interface RobotListResponse {
  content: RobotListItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface RobotDetail {
  robotId: string
  mqttId: string
  robotName: string
  serialNumber: string
  operationalStatus: string
  connectivityStatus: RobotConnectivityStatus
  connectionChangedAt: string | null
  lastReceivedAt: string | null
  robotMode: string | null
  stateReasonCode: string | null
  missionStatus: string | null
  batteryPercent: number | null
  batteryState: string | null
  charging: boolean | null
  batteryTemperatureC: number | null
  estimatedRuntimeSec: number | null
  cameraHealth: string | null
  lidarHealth: string | null
  gpsHealth: string | null
  motorHealth: string | null
  storageHealth: string | null
  latitude: number | null
  longitude: number | null
  headingDeg: number | null
  moving: boolean | null
  speedMps: number | null
  jetsonTempC: number | null
  overallHealthStatus: RobotHealthStatus
  telemetrySampledAt: string | null
  telemetryReceivedAt: string | null
  registeredAt: string
  updatedAt: string
}

export interface RobotSummary {
  totalRobots: number
  onlineRobots: number
  offlineRobots: number
  warningRobots: number
  criticalRobots: number
}

export interface RobotMapItem {
  robotId: string
  mqttId: string
  robotName: string
  connectivityStatus: RobotConnectivityStatus
  latitude: number | null
  longitude: number | null
  headingDeg: number | null
  missionStatus: string | null
  observedAt: string | null
}

export interface RobotMapResponse {
  robots: RobotMapItem[]
}

export interface RobotMarker {
  id: string
  name: string
  lat: number
  lng: number
  headingDeg: number | null
  missionStatus: string | null
  status: RobotStatus
}

export interface RobotFleetItem {
  id: string
  name: string
  mqttId: string
  serialNumber: string
  status: RobotStatus
  connectivityStatus: RobotConnectivityStatus
  batteryPercent: number | null
  jetsonTempC: number | null
  speedMps: number | null
  lastReceivedAt: string | null
  robotMode: string | null
  missionStatus: string | null
  overallHealthStatus: RobotHealthStatus
}

export type RobotRealtimeEventType =
  | 'ROBOT_CONNECTIVITY_CHANGED'
  | 'ROBOT_STATE_UPDATED'
  | 'ROBOT_TELEMETRY_UPDATED'
  | 'COMMAND_STATUS_CHANGED'

export interface RobotRealtimeEvent {
  eventType: RobotRealtimeEventType
  robotId: string
  mqttId: string
  observedAt: string
}

export type RobotStreamStatus =
  | 'connecting'
  | 'connected'
  | 'reconnecting'

export type RobotLogLevel = 'warning' | 'info' | 'success'

export interface RobotLogEntry {
  id: string
  robotId: string
  timestamp: string
  level: RobotLogLevel
  message: string
}
