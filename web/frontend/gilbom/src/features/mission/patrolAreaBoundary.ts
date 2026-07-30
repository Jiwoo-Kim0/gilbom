import type {
  GeoJsonCoordinate,
  GeoJsonPolygon,
  PatrolAreaBoundary,
} from './missionTypes'

const METERS_PER_LATITUDE_DEGREE = 111_320

function roundCoordinate(value: number): number {
  return Number(value.toFixed(7))
}

export function createSquareBoundary(
  centerLatitude: number,
  centerLongitude: number,
  widthMeters: number,
): GeoJsonPolygon {
  const halfWidthMeters = widthMeters / 2
  const latitudeOffset = halfWidthMeters / METERS_PER_LATITUDE_DEGREE
  const longitudeScale = Math.cos(centerLatitude * Math.PI / 180)
  const longitudeOffset = halfWidthMeters
    / (METERS_PER_LATITUDE_DEGREE * longitudeScale)
  const south = roundCoordinate(centerLatitude - latitudeOffset)
  const north = roundCoordinate(centerLatitude + latitudeOffset)
  const west = roundCoordinate(centerLongitude - longitudeOffset)
  const east = roundCoordinate(centerLongitude + longitudeOffset)

  return {
    type: 'Polygon',
    coordinates: [[
      [west, south],
      [east, south],
      [east, north],
      [west, north],
      [west, south],
    ]],
  }
}

export function createPolygonBoundary(
  points: GeoJsonCoordinate[],
): GeoJsonPolygon {
  const ring = points.map(
    ([longitude, latitude]): GeoJsonCoordinate => [longitude, latitude],
  )
  ring.push([...ring[0]])
  return {
    type: 'Polygon',
    coordinates: [ring],
  }
}

export function polygonHasArea(points: GeoJsonCoordinate[]): boolean {
  const twiceArea = points.reduce((area, point, index) => {
    const nextPoint = points[(index + 1) % points.length]
    return area
      + point[0] * nextPoint[1]
      - nextPoint[0] * point[1]
  }, 0)
  return Math.abs(twiceArea) > 1e-12
}

export function getBoundaryPolygons(
  boundary: PatrolAreaBoundary,
): GeoJsonCoordinate[][][] {
  return boundary.type === 'Polygon'
    ? [boundary.coordinates]
    : boundary.coordinates
}

function withoutClosingCoordinate(
  ring: GeoJsonCoordinate[],
): GeoJsonCoordinate[] {
  if (ring.length < 2) {
    return ring
  }
  const first = ring[0]
  const last = ring.at(-1)
  return last?.[0] === first[0] && last[1] === first[1]
    ? ring.slice(0, -1)
    : ring
}

export function getBoundaryOuterPoints(
  boundary: PatrolAreaBoundary,
): GeoJsonCoordinate[] {
  return getBoundaryPolygons(boundary).flatMap((polygon) => (
    withoutClosingCoordinate(polygon[0] ?? [])
  ))
}

export function calculateBoundaryAverageCenter(
  boundary: PatrolAreaBoundary,
): GeoJsonCoordinate | null {
  const points = getBoundaryOuterPoints(boundary)
  if (points.length === 0) {
    return null
  }
  const [longitudeTotal, latitudeTotal] = points.reduce(
    ([longitudeSum, latitudeSum], [longitude, latitude]) => [
      longitudeSum + longitude,
      latitudeSum + latitude,
    ],
    [0, 0],
  )
  return [
    longitudeTotal / points.length,
    latitudeTotal / points.length,
  ]
}
