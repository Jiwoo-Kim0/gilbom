import type {
  GeoJsonCoordinate,
  GeoJsonPolygon,
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
