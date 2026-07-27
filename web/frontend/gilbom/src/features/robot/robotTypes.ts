export type RobotStatus = 'online' | 'warning' | 'charging' | 'offline'

export interface RobotMarker {
  id: string
  lat: number
  lng: number
  status: RobotStatus
}

export interface RobotFleetItem {
  id: string
  status: RobotStatus
  batteryPercent: number
  cpuTempC: number
  speedMps: number
  uptimeLabel: string
}

export type RobotLogLevel = 'warning' | 'info' | 'success'

export interface RobotLogEntry {
  id: string
  robotId: string
  timestamp: string
  level: RobotLogLevel
  message: string
}
